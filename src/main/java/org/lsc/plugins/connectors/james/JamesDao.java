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

import java.util.List;
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
import org.lsc.plugins.connectors.james.beans.Alias;
import org.lsc.plugins.connectors.james.beans.User;
import org.lsc.plugins.connectors.james.beans.UserDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JamesDao {
	
	public static final String ALIASES_PATH = "/address/aliases"; 
	public static final String USERS_PATH = "/users";
	public static final int HTTP_STATUS_CODE_USER_EXITS = Status.OK.getStatusCode();
	public static final int HTTP_STATUS_CODE_USER_DOES_NOT_EXITS = Status.NOT_FOUND.getStatusCode();

	protected static final Logger LOGGER = LoggerFactory.getLogger(JamesDao.class);

	private final WebTarget aliasesClient;
	private final WebTarget usersClient;

	private final String authorizationBearer;
	
	public JamesDao(String url, String token, TaskType task) {
		authorizationBearer = "Bearer " + token;
		aliasesClient = ClientBuilder.newClient()
				.register(JacksonFeature.class)
				.target(url)
				.path(ALIASES_PATH);

		usersClient = ClientBuilder.newClient()
			.register(JacksonFeature.class)
			.target(url)
			.path(USERS_PATH);
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

	public boolean createAliases(User user, List<Alias> aliasesToAdd) {
		return aliasesToAdd.stream()
			.reduce(true,
				(result, alias) -> result && createAlias(user, alias),
				(result1, result2) -> result1 && result2); 
	}

	private boolean createAlias(User user, Alias alias) {
		WebTarget target = aliasesClient.path(user.email).path("sources").path(alias.source);
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
	}

	public boolean removeAliases(User user, List<Alias> aliasesToRemove) {
		return aliasesToRemove.stream()
			.reduce(true,
				(result, alias) -> result && removeAlias(user, alias),
				(result1, result2) -> result1 && result2); 
	}

	private boolean removeAlias(User user, Alias alias) {
		WebTarget target = aliasesClient.path(user.email).path("sources").path(alias.source);
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
}
