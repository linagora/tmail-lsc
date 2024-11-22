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

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.requestSpecification;
import static io.restassured.RestAssured.with;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.FileReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import org.apache.http.HttpStatus;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMReader;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lsc.LscDatasetModification;
import org.lsc.LscDatasetModification.LscDatasetModificationType;
import org.lsc.LscDatasets;
import org.lsc.LscModificationType;
import org.lsc.LscModifications;
import org.lsc.beans.IBean;
import org.lsc.configuration.PluginConnectionType;
import org.lsc.configuration.PluginDestinationServiceType;
import org.lsc.configuration.ServiceType.Connection;
import org.lsc.configuration.TaskType;
import org.lsc.plugins.connectors.james.beans.User;
import org.lsc.plugins.connectors.james.generated.JamesAddressMappingService;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.MountableFile;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

class JamesAddressMappingDstServiceTest {
	private static final URL PRIVATE_KEY = ClassLoader.getSystemResource("conf/jwt_privatekey");
	private static final URL PUBLIC_KEY = ClassLoader.getSystemResource("conf/jwt_publickey");
	private static final String DOMAIN = "james.org";
	private static final String BOB = "bob@" + DOMAIN;
	private static final String ALICE = "alice@" + DOMAIN;
	private static final String ANDRE = "andre@" + DOMAIN;
	private static final String MARIE = "marie@" + DOMAIN;
	private static final int JAMES_WEBADMIN_PORT = 8000;
	private static final boolean FROM_SAME_SERVICE = true;

	private static TaskType task;
	private static GenericContainer<?> james;

	private JamesAddressMappingDstService testee;
	private static JamesDao jamesDao;

	@BeforeAll
	static void beforeAll() throws Exception {
		james = new GenericContainer<>("linagora/tmail-backend:memory-branch-master");
		james.withCopyFileToContainer(MountableFile.forClasspathResource("conf/jwt_publickey"), "/root/conf/");
		james.withCopyFileToContainer(MountableFile.forClasspathResource("conf/jwt_privatekey"), "/root/conf/");
		james.withCopyFileToContainer(MountableFile.forClasspathResource("conf/webadmin.properties"), "/root/conf/");
		james.withExposedPorts(JAMES_WEBADMIN_PORT).start();

		int MAPPED_JAMES_WEBADMIN_PORT = james.getMappedPort(JAMES_WEBADMIN_PORT);
		JamesAddressMappingService jamesAddressMappingService = mock(JamesAddressMappingService.class);
		PluginDestinationServiceType pluginDestinationService = mock(PluginDestinationServiceType.class);
		PluginConnectionType jamesConnection = mock(PluginConnectionType.class);
		Connection connection = mock(Connection.class);
		task = mock(TaskType.class);

		when(jamesConnection.getUrl()).thenReturn("http://localhost:" + MAPPED_JAMES_WEBADMIN_PORT);
		when(jamesConnection.getPassword()).thenReturn(jwtToken());
		when(connection.getReference()).thenReturn(jamesConnection);
		when(jamesAddressMappingService.getConnection()).thenReturn(connection);
		when(task.getBean()).thenReturn("org.lsc.beans.SimpleBean");
		when(task.getPluginDestinationService()).thenReturn(pluginDestinationService);
		when(pluginDestinationService.getAny()).thenReturn(ImmutableList.of(jamesAddressMappingService));

		jamesDao = new JamesDao(jamesConnection.getUrl(), jamesConnection.getPassword(), task);

		requestSpecification = new RequestSpecBuilder().setPort(MAPPED_JAMES_WEBADMIN_PORT)
				.setContentType(ContentType.JSON).setAccept(ContentType.JSON)
				.setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
				.addHeader("Authorization", "Bearer " + jwtToken())
				.setBasePath("/mappings/address").build();
		RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

		with().basePath("/domains").put(DOMAIN).then().statusCode(HttpStatus.SC_NO_CONTENT);
	}

	@BeforeEach
	void beforeEach() throws Exception {
		testee = new JamesAddressMappingDstService(task);
	}

	private static String jwtToken() throws Exception {
		Security.addProvider(new BouncyCastleProvider());

		RSAPublicKey publicKey = getPublicKey();
		RSAPrivateKey privateKey = getPrivateKey();

		Algorithm algorithm = Algorithm.RSA256(publicKey, privateKey);
		return JWT.create()
			.withSubject("admin@james.org")
			.withClaim("admin", true)
			.withIssuedAt(Date.from(Instant.now()))
			.sign(algorithm);
	}

	private static RSAPublicKey getPublicKey() throws Exception {
		try (PEMReader pemReader = new PEMReader(new FileReader(PUBLIC_KEY.getFile()))) {
			Object readObject = pemReader.readObject();
			return (org.bouncycastle.jce.provider.JCERSAPublicKey)readObject;
		}
	}

	private static RSAPrivateKey getPrivateKey() throws Exception {
		try (PEMReader pemReader = new PEMReader(new FileReader(PRIVATE_KEY.getFile()), "james"::toCharArray)) {
	        Object readObject = pemReader.readObject();
	        return (RSAPrivateKey)((java.security.KeyPair)readObject).getPrivate();
		}
	}

	@AfterEach
	void cleanAllAddressMappings() {
		jamesDao.getUserList()
			.forEach(user -> {
				jamesDao.removeAddressMappings(user);
				jamesDao.removeUser(user);
			});
	}

	@AfterAll
	static void close() {
		james.close();
	}

	@Test
	void allAddressMappingsShouldReturnEmptyByDefault() {
		given().basePath("/mappings")
			.when()
			.get("")
		.then()
			.statusCode(HttpStatus.SC_OK)
			.body(Matchers.is("{}"));
	}

	@Test
	void getListPivotsShouldReturnEmptyWhenNoUsersInJames() throws Exception {
		Map<String, LscDatasets> listPivots = testee.getListPivots();
		assertThat(listPivots).isEmpty();
	}

	@Test
	void getListPivotsShouldReturnOnePivotWhenOneUser() throws Exception {
		createUser(BOB);

		Map<String, LscDatasets> listPivots = testee.getListPivots();

		assertThat(listPivots).containsOnlyKeys(BOB);
		assertThat(listPivots.get(BOB).getStringValueAttribute("email")).isEqualTo(BOB);
	}

	@Test
	void getListPivotsShouldReturnTwoWhenTwoUsers() throws Exception {
		createUser(BOB);
		createUser(ALICE);

		Map<String, LscDatasets> listPivots = testee.getListPivots();

		assertThat(listPivots).containsOnlyKeys(BOB, ALICE);
		assertThat(listPivots.get(BOB).getStringValueAttribute("email")).isEqualTo(BOB);
		assertThat(listPivots.get(ALICE).getStringValueAttribute("email")).isEqualTo(ALICE);
	}

	@Test
	void getBeanShouldReturnNullWhenEmptyDataset() throws Exception {
		assertThat(testee.getBean("email", new LscDatasets(), FROM_SAME_SERVICE)).isNull();
	}

	@Test
	void getBeanShouldReturnNullWhenUserDoesNotExist() throws Exception {
		LscDatasets dataset = new LscDatasets(ImmutableMap.of("email", BOB));

		assertThat(testee.getBean("email", dataset, FROM_SAME_SERVICE)).isNull();
	}

	@Test
	void getBeanShouldReturnUserWhenUserDoesNotHaveAnyAddressMapping() throws Exception {
		createUser(BOB);

		LscDatasets dataset = new LscDatasets(ImmutableMap.of("email", BOB));
		IBean bean = testee.getBean("email", dataset, FROM_SAME_SERVICE);

		assertThat(bean.getMainIdentifier()).isEqualTo(BOB);
		assertThat(bean.getDatasetById("addressMappings")).isEmpty();
	}

	@Test
	void getBeanShouldReturnUserWhenUserWithAddressMapping() throws Exception {
		createUser(BOB);
		createAddressMapping(BOB, ALICE);

		LscDatasets datasets = new LscDatasets(ImmutableMap.of("email", BOB));
		IBean bean = testee.getBean("email", datasets, FROM_SAME_SERVICE);

		assertThat(bean.getMainIdentifier()).isEqualTo(BOB);
	}

	@Test
	void getBeanShouldReturnOneAddressMappingWhenUserWithOneAddressMapping() throws Exception {
		createUser(BOB);
		createAddressMapping(BOB, ALICE);

		LscDatasets datasets = new LscDatasets(ImmutableMap.of("email", BOB));
		IBean bean = testee.getBean("email", datasets, FROM_SAME_SERVICE);

		assertThat(bean.getDatasetFirstValueById("email")).isEqualTo(BOB);
		assertThat(bean.getDatasetById("addressMappings")).containsOnly(ALICE);
	}

	@Test
	void getBeanShouldReturnTwoAddressMappingsWhenUserWithTwoAddressMappings() throws Exception {
		createUser(BOB);
		createAddressMapping(BOB, ALICE);
		createAddressMapping(BOB, ANDRE);

		LscDatasets datasets = new LscDatasets(ImmutableMap.of("email", BOB));
		IBean bean = testee.getBean("email", datasets, FROM_SAME_SERVICE);

		assertThat(bean.getDatasetFirstValueById("email")).isEqualTo(BOB);
		assertThat(bean.getDatasetById("addressMappings")).containsExactlyInAnyOrder(ALICE, ANDRE);
	}

	@Test
	void createShouldNotCreateAddressMappingWhenUserDoesNotExistInJames() throws Exception {
		String nonExistingUser = "nonExistingUser@james.org";

		LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
		modifications.setMainIdentifer(nonExistingUser);
		LscDatasetModification lscDatasetModification = new LscDatasetModification(
				LscDatasetModificationType.ADD_VALUES, "addressMappings", ImmutableList.of(ALICE));
		modifications.setLscAttributeModifications(ImmutableList.of(lscDatasetModification));

		boolean applied = testee.apply(modifications);

		assertThat(applied).isTrue();

		with()
			.basePath("/mappings/user")
			.get(nonExistingUser)
		.then()
			.statusCode(HttpStatus.SC_OK)
			.body("",  hasSize(0));
	}
	
	@Test
	void updateWithNoSourceAttributeModificationShouldFail() throws Exception {
		createUser(BOB);
		createAddressMapping(BOB, ALICE);

		LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
		modifications.setMainIdentifer(BOB);
		modifications.setLscAttributeModifications(ImmutableList.of());

		boolean applied = testee.apply(modifications);

		assertThat(applied).isFalse();
	}

	@Test
	void updateWithNoAddressMappingOnLdapShouldRemoveAddressMappingOfTheUserOnJames() throws Exception {
		createUser(BOB);
		createAddressMapping(BOB, ALICE);

		LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
		modifications.setMainIdentifer(BOB);
		LscDatasetModification modification = new LscDatasetModification(
				LscDatasetModificationType.REPLACE_VALUES, "addressMappings", ImmutableList.of());
		modifications.setLscAttributeModifications(ImmutableList.of(modification));

		boolean applied = testee.apply(modifications);

		assertThat(applied).isTrue();

		with()
			.basePath("/mappings/user")
			.get(BOB)
		.then()
			.body("mapping", hasSize(0));
	}

	@Test
	void updateWhenAUserWithoutAddressMappingsInJamesButAddressMappingInLdapShouldCreateAddressMapping() throws Exception {
		createUser(BOB);

		LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
		modifications.setMainIdentifer(BOB);
		LscDatasetModification modification = new LscDatasetModification(
				LscDatasetModificationType.REPLACE_VALUES, "addressMappings", ImmutableList.of(ALICE));
		modifications.setLscAttributeModifications(ImmutableList.of(modification));

		boolean applied = testee.apply(modifications);

		assertThat(applied).isTrue();

		with()
			.basePath("/mappings/user")
		.get(BOB)
			.then()
			.statusCode(HttpStatus.SC_OK)
			.body("mapping",  hasSize(1))
			.body("[0].mapping", equalTo(ALICE));
	}

	@Test
	void updateWhenAUserWithoutAddressMappingsInJamesButSubAddressMappingInLdapShouldCreateTheSubAddressMappingSuccessfully() throws Exception {
		createUser(BOB);

		LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
		modifications.setMainIdentifer(BOB);
		LscDatasetModification modification = new LscDatasetModification(
				LscDatasetModificationType.REPLACE_VALUES, "addressMappings", ImmutableList.of("alice+tag@domain.tld"));
		modifications.setLscAttributeModifications(ImmutableList.of(modification));

		boolean applied = testee.apply(modifications);

		assertThat(applied).isTrue();

		with()
			.basePath("/mappings/user")
		.get(BOB)
			.then()
			.statusCode(HttpStatus.SC_OK)
			.body("mapping",  hasSize(1))
			.body("[0].mapping", equalTo("alice+tag@domain.tld"));
	}
	
	@Test
	void updateWithPreviousAddressMappingPlusOneShouldAddTheNewAddressMapping() throws Exception {
		createUser(BOB);
		createAddressMapping(BOB, ALICE);

		LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
		modifications.setMainIdentifer(BOB);
		LscDatasetModification modification = new LscDatasetModification(
				LscDatasetModificationType.REPLACE_VALUES, "addressMappings", ImmutableList.of(ALICE, ANDRE));
		modifications.setLscAttributeModifications(ImmutableList.of(modification));

		boolean applied = testee.apply(modifications);

		assertThat(applied).isTrue();
		with()
			.basePath("/mappings/user")
		.get(BOB)
			.then()
			.statusCode(HttpStatus.SC_OK)
			.body("mapping",  hasSize(2))
			.body("[0].mapping", equalTo(ALICE))
			.body("[1].mapping", equalTo(ANDRE));
	}
	
	@Test
	void updateWithPreviousAddressMappingsMinusOneShouldRemoveTheMinusOne() throws Exception {
		createUser(BOB);
		createAddressMapping(BOB, ALICE);
		createAddressMapping(BOB, ANDRE);

		with()
			.basePath("/mappings/user")
		.get(BOB)
			.then()
			.statusCode(HttpStatus.SC_OK)
			.body("mapping",  hasSize(2))
			.body("[0].mapping", equalTo(ALICE))
			.body("[1].mapping", equalTo(ANDRE));

		// In LDAP there is only ALICE AddressMapping
		LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
		modifications.setMainIdentifer(BOB);
		LscDatasetModification modification = new LscDatasetModification(
				LscDatasetModificationType.REPLACE_VALUES, "addressMappings", ImmutableList.of(ALICE));
		modifications.setLscAttributeModifications(ImmutableList.of(modification));

		boolean applied = testee.apply(modifications);

		// Then the ANDRE AddressMapping should be removed
		assertThat(applied).isTrue();

		with()
			.basePath("/mappings/user")
		.get(BOB)
			.then()
			.statusCode(HttpStatus.SC_OK)
			.body("mapping",  hasSize(1))
			.body("[0].mapping", equalTo(ALICE));
	}
	
	@Test
	void deleteOperationShouldRemoveTheAddressMappingsOfTheUser() throws Exception {
		createUser(BOB);
		createAddressMapping(BOB, ALICE);
		createAddressMapping(BOB, ANDRE);

		LscModifications modifications = new LscModifications(LscModificationType.DELETE_OBJECT);
		modifications.setMainIdentifer(BOB);
	    modifications.setLscAttributeModifications(ImmutableList.of());

		boolean applied = testee.apply(modifications);

		assertThat(applied).isTrue();
		with()
			.basePath("/mappings/user")
		.get(BOB)
			.then()
			.statusCode(HttpStatus.SC_OK)
			.body("mapping",  hasSize(0));
	}

	@Test
	void deleteOperationShouldRemoveSubAddressMapping() throws Exception {
		createUser(BOB);
		createAddressMapping(BOB, "alice+tag@domain.tld");

		LscModifications modifications = new LscModifications(LscModificationType.DELETE_OBJECT);
		modifications.setMainIdentifer(BOB);
	    modifications.setLscAttributeModifications(ImmutableList.of());

		boolean applied = testee.apply(modifications);

		assertThat(applied).isTrue();
		with()
			.basePath("/mappings/user")
		.get(BOB)
			.then()
			.statusCode(HttpStatus.SC_OK)
			.body("mapping",  hasSize(0));
	}
	
	@Test
	void deleteShouldNotRemoveTheAddressMappingsOfOtherUsers() throws Exception {
		createUser(BOB);
		createAddressMapping(BOB, ALICE);
		createUser(MARIE);
		createAddressMapping(MARIE, ALICE);

		LscModifications modifications = new LscModifications(LscModificationType.DELETE_OBJECT);
		modifications.setMainIdentifer(BOB);
	    modifications.setLscAttributeModifications(ImmutableList.of());

		boolean applied = testee.apply(modifications);

		assertThat(applied).isTrue();

		with()
			.basePath("/mappings/user")
		.get(BOB)
			.then()
			.statusCode(HttpStatus.SC_OK)
			.body("mapping",  hasSize(0));
		
		with()
			.basePath("/mappings/user")
		.get(MARIE)
			.then()
			.statusCode(HttpStatus.SC_OK)
			.body("mapping",  hasSize(1));
	}

	private static void createUser(String user) {
		jamesDao.addUser(new User(user), "password");
	}

	private void createAddressMapping(String user, String addressMapping) {
		with()
			.post("/{user}/targets/{addressMapping}", user, addressMapping)
		.then()
			.statusCode(HttpStatus.SC_NO_CONTENT);
	}

}
