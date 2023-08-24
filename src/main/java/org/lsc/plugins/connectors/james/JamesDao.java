/*
 ****************************************************************************
 * Ldap Synchronization Connector provides tools to synchronize
 * electronic identities from a list of data sources including
 * any database with a JDBC connector, another LDAP directory,
 * flat files...
 *
 *                  ==LICENSE NOTICE==
 * 
 * Copyright (c) 2008 - 2019 LSC Project 
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:

 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *     * Neither the name of the LSC Project nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 *                  ==LICENSE NOTICE==
 *
 *               (c) 2008 - 2019 LSC Project
 *         Raphael Ouazana <rouazana@linagora.com>
 ****************************************************************************
 */
package org.lsc.plugins.connectors.james;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.glassfish.jersey.jackson.JacksonFeature;
import org.lsc.configuration.TaskType;
import org.lsc.plugins.connectors.james.beans.AddressMapping;
import org.lsc.plugins.connectors.james.beans.AddressMappingDto;
import org.lsc.plugins.connectors.james.beans.Alias;
import org.lsc.plugins.connectors.james.beans.Contact;
import org.lsc.plugins.connectors.james.beans.Forward;
import org.lsc.plugins.connectors.james.beans.Identity;
import org.lsc.plugins.connectors.james.beans.User;
import org.lsc.plugins.connectors.james.beans.UserDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

public class JamesDao {
	
	public static final String ALIASES_PATH = "/address/aliases";
	public static final String FORWARDS_PATH = "/address/forwards";
	public static final String IDENTITIES_PATH = "/users/%s/identities";
	public static final String USERS_PATH = "/users";
	public static final String DOMAIN_CONTACT_PATH = "/domains/%s/contacts/%s";
	public static final String ADDRESS_MAPPING_PATH = "/mappings/address/%s/targets/%s";
	public static final String USER_MAPPING_PATH = "/mappings/user";
	public static final int HTTP_STATUS_CODE_USER_EXITS = Status.OK.getStatusCode();
	public static final int HTTP_STATUS_CODE_USER_DOES_NOT_EXITS = Status.NOT_FOUND.getStatusCode();

	protected static final Logger LOGGER = LoggerFactory.getLogger(JamesDao.class);

	private final WebTarget aliasesClient;
	private final WebTarget forwardsClient;
	private final WebTarget identitiesClient;
	private final WebTarget usersClient;
	private final WebTarget contactsClient;
	private final WebTarget addressMappingsClient;
	private final String authorizationBearer;
	private final ObjectMapper mapper;

	public static boolean synchronizeLocalCopyForwards(Forward ldapForward, String userMailAddress, boolean allowSynchronizeLocalCopyForwards) {
		if (allowSynchronizeLocalCopyForwards) {
			return true;
		} else {
			return !ldapForward.getMailAddress().equals(userMailAddress);
		}
	}

	public JamesDao(String url, String token, TaskType task) {
		authorizationBearer = "Bearer " + token;
		aliasesClient = ClientBuilder.newClient()
				.register(JacksonFeature.class)
				.target(url)
				.path(ALIASES_PATH);

		forwardsClient = ClientBuilder.newClient()
			.register(JacksonFeature.class)
			.target(url)
			.path(FORWARDS_PATH);

		identitiesClient = ClientBuilder.newClient()
			.register(JacksonFeature.class)
			.target(url);

		usersClient = ClientBuilder.newClient()
			.register(JacksonFeature.class)
			.target(url)
			.path(USERS_PATH);

		contactsClient = ClientBuilder.newClient()
			.register(JacksonFeature.class)
			.target(url);

		addressMappingsClient = ClientBuilder.newClient()
			.register(JacksonFeature.class)
			.target(url);

		mapper = new ObjectMapper().registerModule(new Jdk8Module());
	}

	public List<AddressMapping> getAddressMappings(String email) {
		WebTarget target = addressMappingsClient.path(USER_MAPPING_PATH).path(email);
		LOGGER.debug("GETting address mappings: " + target.getUri().toString());

		List<AddressMapping> addressMappings = target.request()
			.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
			.get(new GenericType<List<AddressMappingDto>>(){})
			.stream()
			.filter(filterAddressType())
			.map(addressMappingDto -> new AddressMapping(addressMappingDto.getMapping()))
			.collect(Collectors.toList());

		return addressMappings;
	}

	private Predicate<AddressMappingDto> filterAddressType() {
		// AddressMapping create/delete APIs only work with "Address" type. IMO we only manage this type via LSC.
		return addressMappingDto -> addressMappingDto.getType().equals("Address");
	}

	public boolean updateAddressMappings(User user, List<AddressMapping> ldapAddressMappings) {
		List<AddressMapping> jamesAddressMappings = getAddressMappings(user.email);
		List<AddressMapping> addressMappingsToAddToJames = computeAddressMappingsToAdd(ldapAddressMappings, jamesAddressMappings);
		List<AddressMapping> addressMappingsToRemoveToJames = computeAddressMappingToRemove(ldapAddressMappings, jamesAddressMappings);
		return createAddressMappings(user, addressMappingsToAddToJames) && removeAddressMappings(user, addressMappingsToRemoveToJames);
	}

	private List<AddressMapping> computeAddressMappingsToAdd(List<AddressMapping> ldapAddressMappings, List<AddressMapping> jamesAddressMappings) {
		return ldapAddressMappings.stream()
			.filter(addressMapping -> !jamesAddressMappings.contains(addressMapping))
			.collect(Collectors.toList());
	}

	private List<AddressMapping> computeAddressMappingToRemove(List<AddressMapping> ldapAddressMappings, List<AddressMapping> jamesAddressMappings) {
		return jamesAddressMappings.stream()
			.filter(addressMapping -> !ldapAddressMappings.contains(addressMapping))
			.collect(Collectors.toList());
	}

	private boolean createAddressMappings(User user, List<AddressMapping> addressMappingsToAdd) {
		return addressMappingsToAdd.stream()
			.reduce(true,
				(result, addressMapping) -> result && createAddressMapping(user, addressMapping),
				(result1, result2) -> result1 && result2);
	}

	private boolean createAddressMapping(User user, AddressMapping addressMapping) {
		WebTarget target = addressMappingsClient.path(String.format(ADDRESS_MAPPING_PATH, user.email, addressMapping.getMapping()));

		LOGGER.debug("Creating address mapping: " + target.getUri().toString());

		Response response = target.request()
			.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
			.post(Entity.text(""));
		String rawResponseBody = response.readEntity(String.class);
		response.close();
		if (checkResponse(response)) {
			LOGGER.debug("Created address mapping {} for user {} successfully", addressMapping.getMapping(), user.email);
			return true;
		} else {
			LOGGER.error(String.format("Error %d (%s - %s) while creating address mapping: %s",
				response.getStatus(),
				response.getStatusInfo(),
				rawResponseBody,
				target.getUri().toString()));
			return false;
		}
	}

	public boolean removeAddressMappings(User user) {
		List<AddressMapping> addressMappingsToRemove = getAddressMappings(user.email);
		return removeAddressMappings(user, addressMappingsToRemove);
	}

	public boolean removeAddressMappings(User user, List<AddressMapping> addressMappingsToRemove) {
		return addressMappingsToRemove.stream()
			.reduce(true,
				(result, addressMapping) -> result && removeAddressMapping(user, addressMapping),
				(result1, result2) -> result1 && result2);
	}

	private boolean removeAddressMapping(User user, AddressMapping addressMapping) {
		WebTarget target = addressMappingsClient.path(String.format(ADDRESS_MAPPING_PATH, user.email, addressMapping.getMapping()));

		LOGGER.debug("DELETEting address mapping: " + target.getUri().toString());

		Response response = target.request()
			.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
			.delete();
		String rawResponseBody = response.readEntity(String.class);
		response.close();
		if (checkResponse(response)) {
			LOGGER.debug("DELETE address mapping successfully");
			return true;
		} else {
			LOGGER.error(String.format("Error %d (%s - %s) while deleting address mapping: %s",
				response.getStatus(),
				response.getStatusInfo(),
				rawResponseBody,
				target.getUri().toString()));
			return false;
		}
	}

	public List<Alias> getAliases(String email) {
		WebTarget target = aliasesClient.path(email);
		LOGGER.debug("GETting aliases: " + target.getUri().toString());
		List<Alias> aliases = target.request()
				.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
				.get(new GenericType<List<Alias>>(){});
		if (aliases.isEmpty()) {
			throw new NotFoundException();
		}
		return aliases;
	}

	public List<Forward> getForwards(String email) {
		WebTarget target = forwardsClient.path(email);
		LOGGER.debug("GETting forwards: " + target.getUri().toString());
		List<Forward> forwards = target.request()
			.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
			.get(new GenericType<List<Forward>>(){});
		if (forwards.isEmpty()) {
			throw new NotFoundException();
		}
		return forwards;
	}

	public boolean createForwards(User user, List<Forward> forwardsToAdd) {
		return forwardsToAdd.stream()
			.reduce(true,
				(result, forward) -> result && createForward(user, forward),
				(result1, result2) -> result1 && result2);
	}

	private boolean createForward(User user, Forward forward) {
		try {
			WebTarget target = forwardsClient.path(user.email)
				.path("targets")
				.path(urlEncode(forward.getMailAddress()));

			LOGGER.debug("Creating forward: " + target.getUri().toString());

			Response response = target.request()
				.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
				.put(Entity.text(""));
			String rawResponseBody = response.readEntity(String.class);
			response.close();
			if (checkResponse(response)) {
				LOGGER.debug("Created forward {} for user {} successfully", forward.getMailAddress(), user.email);
				return true;
			} else {
				LOGGER.error(String.format("Error %d (%s - %s) while creating forward: %s",
					response.getStatus(),
					response.getStatusInfo(),
					rawResponseBody,
					target.getUri().toString()));
				return false;
			}
		} catch (UnsupportedEncodingException e) {
				LOGGER.error("Failed to URL encoding mail address {} with error {}", forward.getMailAddress(), e.toString());
				return false;
		}
	}

	private String urlEncode(String address) throws UnsupportedEncodingException {
		return URLEncoder.encode(address, StandardCharsets.UTF_8.toString());
	}

	public boolean updateForwards(User user, List<Forward> ldapForwards, boolean allowSynchronizeLocalCopyForwards) {
		List<Forward> jamesForwards = getForwards(user.email);
		List<Forward> forwardsToAddToJames = computeForwardsToAdd(user, ldapForwards, jamesForwards, allowSynchronizeLocalCopyForwards);
		// Removing forwards is not supported. In case User has a forward that does not exist in LDAP, we should not remove that forward, that could be user JMAP forward.
		return createForwards(user, forwardsToAddToJames);
	}

	private List<Forward> computeForwardsToAdd(User user, List<Forward> ldapForwards, List<Forward> jamesForwards, boolean allowSynchronizeLocalCopyForwards) {
		return ldapForwards.stream()
			.filter(ldapForward -> !jamesForwards.contains(ldapForward) && synchronizeLocalCopyForwards(ldapForward, user.email, allowSynchronizeLocalCopyForwards))
			.collect(Collectors.toList());
	}

	public boolean deleteForwards(User user) {
		List<Forward> forwardsToDelete = getForwards(user.email);
		return deleteForwards(user, forwardsToDelete);
	}

	private boolean deleteForwards(User user, List<Forward> forwardsToDelete) {
		return forwardsToDelete.stream()
			.reduce(true,
				(result, forward) -> result && deleteForward(user, forward),
				(result1, result2) -> result1 && result2);
	}

	private boolean deleteForward(User user, Forward forward) {
		try {
			WebTarget target = forwardsClient.path(user.email).path("targets").path(urlEncode(forward.getMailAddress()));
			LOGGER.debug("DELETEting forward: " + target.getUri().toString());
			Response response = target.request()
				.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
				.delete();
			String rawResponseBody = response.readEntity(String.class);
			response.close();
			if (checkResponse(response)) {
				LOGGER.debug("DELETE successfully");
				return true;
			} else {
				LOGGER.error(String.format("Error %d (%s - %s) while deleting forward: %s",
					response.getStatus(),
					response.getStatusInfo(),
					rawResponseBody,
					target.getUri().toString()));
				return false;
			}
		} catch (UnsupportedEncodingException e) {
			LOGGER.error("Failed to URL encoding mail address {} with error {}", forward.getMailAddress(), e.toString());
			return false;
		}
	}

	public Identity getDefaultIdentity(String email) throws IOException {
		WebTarget target = identitiesClient
			.path(String.format(IDENTITIES_PATH, email))
			.queryParam("default", true);
		LOGGER.debug("GETting default identity for user {} at {}", email, target.getUri().toString());
		List<Identity> identities = target.request()
			.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
			.get(new GenericType<List<Identity>>(){});
		if (identities.isEmpty()) {
			throw new NotFoundException();
		}
		return identities.get(0);
	}

	public boolean createDefaultIdentity(Identity identity) throws JsonProcessingException {
		Response response = identitiesClient
			.path(String.format(IDENTITIES_PATH, identity.getEmail()))
			.request()
			.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
			.post(Entity.text(mapper.writeValueAsString(identity)));

		String rawResponseBody = response.readEntity(String.class);
		response.close();
		if (checkResponse(response)) {
			LOGGER.debug("Create default identity for user {} successfully with display name {}", identity.getEmail(), identity.getName());
			return true;
		} else {
			LOGGER.error(String.format("Error %d (%s - %s) while creating default identity at %s",
				response.getStatus(),
				response.getStatusInfo(),
				rawResponseBody,
				identitiesClient.getUri().toString()));
			return false;
		}
	}

	public List<User> getUsersListViaAlias() {
		WebTarget target = aliasesClient.path("");
		LOGGER.debug("GETting users with alias list: " + target.getUri().toString());
		List<String> users = target.request()
				.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
				.get(new GenericType<List<String>>(){});
		return users.stream()
			.map(User::new)
			.collect(Collectors.toList());
	}

	public List<User> getUsersHaveForwards() {
		WebTarget target = forwardsClient.path("");
		LOGGER.debug("GETting users list that have forwards: " + target.getUri().toString());
		List<String> users = target.request()
			.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
			.get(new GenericType<List<String>>(){});
		return users.stream()
			.map(User::new)
			.collect(Collectors.toList());
	}

	public boolean createAliases(User user, List<Alias> aliasesToAdd) {
		return aliasesToAdd.stream()
			.reduce(true,
				(result, alias) -> result && createAlias(user, alias),
				(result1, result2) -> result1 && result2); 
	}

	private boolean createAlias(User user, Alias alias) {
		try {
			WebTarget target = aliasesClient.path(user.email).path("sources").path(urlEncode(alias.source));
			LOGGER.debug("PUTting alias: " + target.getUri().toString());
			Response response = target.request()
				.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
				.put(Entity.text(""));
			String rawResponseBody = response.readEntity(String.class);
			response.close();
			if (checkResponse(response)) {
				LOGGER.debug("PUT is successful");
				return true;
			} else {
				LOGGER.error(String.format("Error %d (%s - %s) while creating alias: %s",
					response.getStatus(),
					response.getStatusInfo(),
					rawResponseBody,
					target.getUri().toString()));
				return false;
			}
		} catch (UnsupportedEncodingException e) {
			LOGGER.error("Failed to URL encoding mail address {} with error {}", alias.source, e.toString());
			return false;
		}
	}

	public boolean removeAliases(User user, List<Alias> aliasesToRemove) {
		return aliasesToRemove.stream()
			.reduce(true,
				(result, alias) -> result && removeAlias(user, alias),
				(result1, result2) -> result1 && result2);
	}

	private boolean removeAlias(User user, Alias alias) {
		try {
			WebTarget target = aliasesClient.path(user.email).path("sources").path(urlEncode(alias.source));
			LOGGER.debug("DELETEting alias: " + target.getUri().toString());
			Response response = target.request()
				.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
				.delete();
			String rawResponseBody = response.readEntity(String.class);
			response.close();
			if (checkResponse(response)) {
				LOGGER.debug("DELETE is successful");
				return true;
			} else {
				LOGGER.error(String.format("Error %d (%s - %s) while deleting alias: %s",
					response.getStatus(),
					response.getStatusInfo(),
					rawResponseBody,
					target.getUri().toString()));
				return false;
			}
		} catch (UnsupportedEncodingException e) {
			LOGGER.error("Failed to URL encoding mail address {} with error {}", alias.source, e.toString());
			return false;
		}

	}

	private static boolean checkResponse(Response response) {
		return Status.Family.familyOf(response.getStatus()) == Status.Family.SUCCESSFUL;
	}

	public boolean updateAliases(User user, List<Alias> updatedAliases) {
		List<Alias> aliasesInDestination = getAliases(user.email);
		List<Alias> aliasesToAdd  =  computeAliasToAdd(updatedAliases, aliasesInDestination);
		List<Alias> aliasesToRemove  =  computeAliasToRemove(updatedAliases, aliasesInDestination);
		return removeAliases(user, aliasesToRemove) && createAliases(user, aliasesToAdd);
	}

	public boolean deleteAlias(User user) {
		List<Alias> aliasesToRemove = getAliases(user.email);
		return removeAliases(user, aliasesToRemove);
	}
	
	private List<Alias> computeAliasToAdd(List<Alias> sourceAliases, List<Alias> destinationAliases) {
		return sourceAliases.stream()
				.filter(alias -> !destinationAliases.contains(alias))
				.collect(Collectors.toList());
	}
	

	private List<Alias> computeAliasToRemove(List<Alias> sourceAliases, List<Alias> destinationAliases) {
		return destinationAliases.stream()
				.filter(alias -> !sourceAliases.contains(alias))
				.collect(Collectors.toList());
	}

	public boolean addUser(User user, String password) {
		Response response = usersClient
			.path(user.email)
			.request()
			.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
			.put(Entity.text("{\"password\":\"" + password + "\"}"));

		String rawResponseBody = response.readEntity(String.class);
		response.close();
		if (checkResponse(response)) {
			LOGGER.debug("Create user {} is successful", user.email);
			return true;
		}
		LOGGER.error(String.format("Error %d (%s - %s) while creating user: %s",
			response.getStatus(),
			response.getStatusInfo(),
			rawResponseBody,
			usersClient.getUri().toString()));
		return false;
	}

	public boolean removeUser(User user) {
		Response response = usersClient
			.path(user.email)
			.request()
			.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
			.delete();

		String rawResponseBody = response.readEntity(String.class);
		response.close();
		if (checkResponse(response)) {
			LOGGER.debug("Remove user {} is successful", user.email);
			return true;
		}
		LOGGER.error(String.format("Error %d (%s - %s) while removing user: %s",
			response.getStatus(),
			response.getStatusInfo(),
			rawResponseBody,
			usersClient.getUri().toString()));
		return false;
	}

	public List<User> getUserList() {
		List<UserDto> users = usersClient
			.path("")
			.request()
			.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
			.get(new GenericType<List<UserDto>>() {
			});
		return users.stream()
			.map(User::fromDto)
			.collect(Collectors.toList());
	}

	public boolean userExists(String user) {
		Response response = usersClient
			.path(user)
			.request()
			.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
			.head();
		String rawResponseBody = response.readEntity(String.class);
		response.close();
		if (response.getStatus() == HTTP_STATUS_CODE_USER_EXITS) {
			return true;
		} else if (response.getStatus() == HTTP_STATUS_CODE_USER_DOES_NOT_EXITS) {
			return false;
		}
		LOGGER.error(String.format("Error %d (%s - %s) while check exits user: %s",
			response.getStatus(),
			response.getStatusInfo(),
			rawResponseBody,
			user));

		throw new JamesClientException(usersClient.getUri(), HttpMethod.HEAD, response);
	}

	public boolean addDomainContact(Contact contact) throws JsonProcessingException {
		Response response = contactsClient
			.path(String.format("/domains/%s/contacts", contact.getDomain()))
			.request()
			.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
			.post(Entity.text(mapper.writeValueAsString(contact)));

		String rawResponseBody = response.readEntity(String.class);
		response.close();
		if (checkResponse(response)) {
			LOGGER.debug("Create domain contact {} is successful", contact.getEmailAddress());
			return true;
		} else {
			LOGGER.error(String.format("Error %d (%s - %s) while creating domain contact: %s",
				response.getStatus(),
				response.getStatusInfo(),
				rawResponseBody,
				contactsClient.getUri().toString()));
			return false;
		}
	}

	public List<User> getUsersListViaDomainContacts() {
		WebTarget target = contactsClient.path("/domains/contacts/all");
		LOGGER.debug("GETting users with domain contacts list: " + target.getUri().toString());
		List<String> users = target.request()
			.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
			.get(new GenericType<List<String>>(){});
		return users.stream()
			.map(User::new)
			.collect(Collectors.toList());
	}

	public Contact getContact(String email) throws IOException {
		WebTarget target = contactsClient.path(String.format(DOMAIN_CONTACT_PATH, Contact.extractDomainFromEmail(email),
			Contact.extractUsernameFromEmail(email)));
		LOGGER.debug("GETting contact: " + target.getUri().toString());
		Response response = target.request()
			.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
			.get();
		if (checkResponse(response)) {
			LOGGER.debug("Get domain contact {} is successful", email);
			return mapper.readValue(response.readEntity(String.class), Contact.class);
		} else {
			throw new NotFoundException();
		}
	}

	public boolean updateDomainContact(Contact contact) throws JsonProcessingException {
		Response response = contactsClient
			.path(String.format(DOMAIN_CONTACT_PATH, contact.getDomain(), contact.getUsernameFromEmail()))
			.request()
			.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
			.put(Entity.text(mapper.writeValueAsString(contact.getContactNames())));

		String rawResponseBody = response.readEntity(String.class);
		response.close();
		if (checkResponse(response)) {
			LOGGER.debug("Update domain contact {} is successful", contact.getEmailAddress());
			return true;
		} else {
			LOGGER.error(String.format("Error %d (%s - %s) while creating domain contact: %s",
				response.getStatus(),
				response.getStatusInfo(),
				rawResponseBody,
				contactsClient.getUri().toString()));
			return false;
		}
	}

	public boolean removeDomainContact(String email) {
		Response response = contactsClient
			.path(String.format(DOMAIN_CONTACT_PATH, Contact.extractDomainFromEmail(email), Contact.extractUsernameFromEmail(email)))
			.request()
			.header(HttpHeaders.AUTHORIZATION, authorizationBearer)
			.delete();

		String rawResponseBody = response.readEntity(String.class);
		response.close();
		if (checkResponse(response)) {
			LOGGER.debug("Remove domain contact {} is successful", email);
			return true;
		} else {
			LOGGER.error(String.format("Error %d (%s - %s) while removing user: %s",
				response.getStatus(),
				response.getStatusInfo(),
				rawResponseBody,
				usersClient.getUri().toString()));
			return false;
		}
	}
}
