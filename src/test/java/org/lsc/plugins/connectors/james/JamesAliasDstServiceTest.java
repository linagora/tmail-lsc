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
import java.security.KeyFactory;
import java.security.Security;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpStatus;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.lsc.plugins.connectors.james.generated.JamesAliasService;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

public class JamesAliasDstServiceTest {
	private static final URL PRIVATE_KEY = ClassLoader.getSystemResource("conf/private.pem");
	private static final URL PUBLIC_KEY = ClassLoader.getSystemResource("conf/jwt_publickey");
	private static final String DOMAIN = "james.org";
	private static final int JAMES_WEBADMIN_PORT = 8000;
	private static int MAPPED_JAMES_WEBADMIN_PORT;
	private static final boolean FROM_SAME_SERVICE = true;

	private static TaskType task;
	private static GenericContainer<?> james;

	private JamesAliasDstService testee;

	@BeforeAll
	static void setup() throws Exception {
		james = new GenericContainer<>("linagora/james-memory:tmail-0.2.0");
		String webadmin = ClassLoader.getSystemResource("conf/webadmin.properties").getFile();
		james.withExposedPorts(JAMES_WEBADMIN_PORT)
			.withFileSystemBind(PUBLIC_KEY.getFile(), "/root/conf/jwt_publickey", BindMode.READ_ONLY)
			.withFileSystemBind(webadmin, "/root/conf/webadmin.properties", BindMode.READ_ONLY)
			.start();

		MAPPED_JAMES_WEBADMIN_PORT = james.getMappedPort(JAMES_WEBADMIN_PORT);
		JamesAliasService jamesAliasService = mock(JamesAliasService.class);
		PluginDestinationServiceType pluginDestinationService = mock(PluginDestinationServiceType.class);
		PluginConnectionType jamesConnection = mock(PluginConnectionType.class);
		Connection connection = mock(Connection.class);
		task = mock(TaskType.class);

		when(jamesConnection.getUrl()).thenReturn("http://localhost:" + MAPPED_JAMES_WEBADMIN_PORT);
		when(jamesConnection.getPassword()).thenReturn(jwtToken());
		when(connection.getReference()).thenReturn(jamesConnection);
		when(jamesAliasService.getConnection()).thenReturn(connection);
		when(task.getBean()).thenReturn("org.lsc.beans.SimpleBean");
		when(task.getPluginDestinationService()).thenReturn(pluginDestinationService);
		when(pluginDestinationService.getAny()).thenReturn(ImmutableList.of(jamesAliasService));

		RestAssured.requestSpecification = new RequestSpecBuilder().setPort(MAPPED_JAMES_WEBADMIN_PORT)
				.setContentType(ContentType.JSON).setAccept(ContentType.JSON)
				.setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
				.addHeader("Authorization", "Bearer " + jwtToken())
				.setBasePath("/address/aliases").build();
		RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

		with().basePath("/domains").put(DOMAIN).then().statusCode(HttpStatus.SC_NO_CONTENT);
	}
	
	private static String jwtToken() throws Exception {
		Security.addProvider(new BouncyCastleProvider());
		KeyFactory factory = KeyFactory.getInstance("RSA", "BC");

		RSAPublicKey publicKey = getPublicKey(factory);
		RSAPrivateKey privateKey = getPrivateKey(factory);

		Algorithm algorithm = Algorithm.RSA256(publicKey, privateKey);
		return JWT.create()
			.withSubject("admin@james.org")
			.withClaim("admin", true)
			.withIssuedAt(Date.from(Instant.now()))
			.sign(algorithm);
	}

	private static RSAPublicKey getPublicKey(KeyFactory keyFactory) throws Exception {
		try (PEMReader pemReader = new PEMReader(new FileReader(PUBLIC_KEY.getFile()))) {
			Object readObject = pemReader.readObject();
			return (org.bouncycastle.jce.provider.JCERSAPublicKey)readObject;
		}
	}

	private static RSAPrivateKey getPrivateKey(KeyFactory keyFactory) throws Exception {
		try (PEMReader pemReader = new PEMReader(new FileReader(PRIVATE_KEY.getFile()), () -> "james".toCharArray())) {
	        Object readObject = pemReader.readObject();
	        return (RSAPrivateKey)((java.security.KeyPair)readObject).getPrivate();
		}
	}


	@AfterEach
	void cleanAllAliases() {
		List<String> usersWithAliases = with().get("").jsonPath().getList("");
		usersWithAliases.forEach(this::deleteUserWithAliases);
	}

	private void deleteUserWithAliases(String id) {
		List<String> aliases = with().get(id).jsonPath().getList("source");
		aliases.forEach(alias -> removeAlias(id, alias));
	}

	private void removeAlias(String id, String alias) {
		with().delete("/{id}/sources/{alias}", id, alias).then().statusCode(HttpStatus.SC_NO_CONTENT);
	}

	@AfterAll
	static void close() {
		james.close();
	}

	@Test
	void jamesAliasesApiShouldReturnEmptyByDefault() throws Exception {
		given().when().get("").then().statusCode(HttpStatus.SC_OK).body("", hasSize(0));
	}

	@Test
	public void getListPivotsShouldReturnEmptyWhenNoAlias() throws Exception {
		testee = new JamesAliasDstService(task);

		Map<String, LscDatasets> listPivots = testee.getListPivots();

		assertThat(listPivots).isEmpty();
	}

	@Test
	void jamesAliasesApiShouldReturnOneUserWhenOneUserWithOneAlias() throws Exception {
		createAlias("user@james.org", "alias@james.org");
		given().when().get("").then().statusCode(HttpStatus.SC_OK).body("", hasSize(1));
	}

	@Test
	public void getListPivotsShouldReturnOneWhenOneAlias() throws Exception {
		String user = "user@james.org";
		String alias = "alias-to-user@james.org";
		createAlias(user, alias);

		testee = new JamesAliasDstService(task);

		Map<String, LscDatasets> listPivots = testee.getListPivots();

		assertThat(listPivots).containsOnlyKeys(user);
		assertThat(listPivots.get(user).getStringValueAttribute("email")).isEqualTo(user);
	}

	@Test
	public void getListPivotsShouldReturnTwoWhenTwoUsersWithAlias() throws Exception {
		String user = "user@james.org";
		String alias = "alias-to-user@james.org";
		createAlias(user, alias);

		String user2 = "user2@james.org";
		String aliasUser2 = "alias-to-user2@james.org";
		createAlias(user2, aliasUser2);

		testee = new JamesAliasDstService(task);

		Map<String, LscDatasets> listPivots = testee.getListPivots();

		assertThat(listPivots).containsOnlyKeys(user, user2);
		assertThat(listPivots.get(user).getStringValueAttribute("email")).isEqualTo(user);
		assertThat(listPivots.get(user2).getStringValueAttribute("email")).isEqualTo(user2);
	}

	@Test
	public void getBeanShouldReturnNullWhenEmptyDataset() throws Exception {
		testee = new JamesAliasDstService(task);

		assertThat(testee.getBean("email", new LscDatasets(), FROM_SAME_SERVICE)).isNull();
	}

	@Test
	public void getBeanShouldReturnNullWhenNoMatchingId() throws Exception {
		testee = new JamesAliasDstService(task);

		LscDatasets nonExistingIdDataset = new LscDatasets(ImmutableMap.of("email", "nonExistingEmail@james.org"));
		assertThat(testee.getBean("email", nonExistingIdDataset, FROM_SAME_SERVICE)).isNull();
	}

	@Test
	public void getBeanShouldReturnUserWhenUserWithAlias() throws Exception {
		testee = new JamesAliasDstService(task);
		String user = "user@james.org";
		String alias = "alias-to-user@james.org";

		createAlias(user, alias);

		LscDatasets nonExistingIdDataset = new LscDatasets(ImmutableMap.of("email", user));
		IBean bean = testee.getBean("email", nonExistingIdDataset, FROM_SAME_SERVICE);
		assertThat(bean.getMainIdentifier()).isEqualTo(user);
	}

	@Test
	public void getBeanShouldReturnAliasesWhenUserWithAlias() throws Exception {
		testee = new JamesAliasDstService(task);
		String user = "user@james.org";
		String alias = "alias-to-user@james.org";

		createAlias(user, alias);

		LscDatasets nonExistingIdDataset = new LscDatasets(ImmutableMap.of("email", user));
		IBean bean = testee.getBean("email", nonExistingIdDataset, FROM_SAME_SERVICE);

		assertThat(bean.getDatasetFirstValueById("email")).isEqualTo(user);
		assertThat(bean.getDatasetById("sources")).containsOnly(alias);
	}

	@Test
	public void getBeanShouldReturnAliasesWhenUserWithTwoAlias() throws Exception {
		testee = new JamesAliasDstService(task);
		String user = "user@james.org";
		String alias = "alias-to-user@james.org";
		String alias2 = "alias2-to-user@james.org";

		createAlias(user, alias);
		createAlias(user, alias2);

		LscDatasets nonExistingIdDataset = new LscDatasets(ImmutableMap.of("email", user));
		IBean bean = testee.getBean("email", nonExistingIdDataset, FROM_SAME_SERVICE);

		assertThat(bean.getDatasetFirstValueById("email")).isEqualTo(user);
		assertThat(bean.getDatasetById("sources")).containsOnly(alias, alias2);
	}

	@Test
	public void createShouldFailWhenAddressIsMissing() throws Exception {
		testee = new JamesAliasDstService(task);

		LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
		String alias = "alias-to-user@james.org";
		LscDatasetModification aliasesModification = new LscDatasetModification(
				LscDatasetModificationType.REPLACE_VALUES, "sources", ImmutableList.of(alias));
		modifications.setLscAttributeModifications(ImmutableList.of(aliasesModification));

		boolean applied = testee.apply(modifications);

		assertThat(applied).isFalse();
	}

	@Test
	public void createShouldSucceedWhenNoAlias() throws Exception {
		String email = "user@james.org";

		testee = new JamesAliasDstService(task);

		LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
		modifications.setMainIdentifer(email);

		modifications.setLscAttributeModifications(ImmutableList.of());

		boolean applied = testee.apply(modifications);

		assertThat(applied).isTrue();
		with()
		.get(email)
		.then()
			.body("source", hasSize(0));
	}

	@Test
	public void createShouldSucceedWhenEmptyAliasList() throws Exception {
		String email = "user@james.org";

		testee = new JamesAliasDstService(task);

		LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
		modifications.setMainIdentifer(email);

		LscDatasetModification aliasesModification = new LscDatasetModification(
				LscDatasetModificationType.REPLACE_VALUES, "sources", ImmutableList.of());

		modifications.setLscAttributeModifications(ImmutableList.of(aliasesModification));

		boolean applied = testee.apply(modifications);
		assertThat(applied).isTrue();
		with()
		.get(email)
		.then()
			.body("source", hasSize(0));
	}

	@Test
	public void createShouldSucceedWhenAddingOneUserWithOneAlias() throws Exception {
		String email = "user@james.org";
		String alias = "alias-to-user@james.org";

		testee = new JamesAliasDstService(task);

		LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
		modifications.setMainIdentifer(email);

		LscDatasetModification aliasesModification = new LscDatasetModification(
				LscDatasetModificationType.REPLACE_VALUES, "sources", ImmutableList.of(alias));

		modifications.setLscAttributeModifications(ImmutableList.of(aliasesModification));

		boolean applied = testee.apply(modifications);

		assertThat(applied).isTrue();
		with()
		.get(email)
		.then()
			.body("source", hasSize(1))
			.body("[0].source", equalTo(alias));
	}


	@Test
	public void createShouldSucceedWhenAddingOneUserWithTwoAlias() throws Exception {
		String email = "user@james.org";
		String alias1 = "alias1-to-user@james.org";
		String alias2 = "alias2-to-user@james.org";
		testee = new JamesAliasDstService(task);

		LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
		modifications.setMainIdentifer(email);

		LscDatasetModification aliasesModification = new LscDatasetModification(
				LscDatasetModificationType.REPLACE_VALUES, "sources", ImmutableList.of(alias1, alias2));

		modifications.setLscAttributeModifications(ImmutableList.of(aliasesModification));

		boolean applied = testee.apply(modifications);

		assertThat(applied).isTrue();
		with()
		.get(email)
		.then()
			.body("source", hasSize(2))
			.body("[0].source", equalTo(alias1))
			.body("[1].source", equalTo(alias2));
	}
	
	@Test
	public void updateWithNoSourceAttributeModificationShouldFail() throws Exception {
		String email = "user@james.org";
		String alias = "alias-to-user@james.org";
		
		createAlias(email, alias);
		
		testee = new JamesAliasDstService(task);

		LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
		modifications.setMainIdentifer(email);

		modifications.setLscAttributeModifications(ImmutableList.of());

		boolean applied = testee.apply(modifications);

		assertThat(applied).isFalse();
		
		with()
		.get(email)
		.then()
			.body("source", hasSize(1));
	}

	
	
	@Test
	public void updateWithNoAliasShouldClearAliasListOfUser() throws Exception {
		String email = "user@james.org";
		String alias = "alias-to-user@james.org";
		
		createAlias(email, alias);
		
		testee = new JamesAliasDstService(task);

		LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
		modifications.setMainIdentifer(email);
		LscDatasetModification aliasesModification = new LscDatasetModification(
				LscDatasetModificationType.REPLACE_VALUES, "sources", ImmutableList.of());
		modifications.setLscAttributeModifications(ImmutableList.of(aliasesModification));

		boolean applied = testee.apply(modifications);

		assertThat(applied).isTrue();
		with()
		.get(email)
		.then()
			.body("source", hasSize(0));
	}

	@Test
	public void updateToAnAddressWithoutAliasesShouldFail() throws Exception {
		String email = "user@james.org";
		String alias = "alias-to-user@james.org";
		
		testee = new JamesAliasDstService(task);

		LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
		modifications.setMainIdentifer(email);

		LscDatasetModification aliasesModification = new LscDatasetModification(
				LscDatasetModificationType.REPLACE_VALUES, "sources", ImmutableList.of(alias));
		
		modifications.setLscAttributeModifications(ImmutableList.of(aliasesModification));

		boolean applied = testee.apply(modifications);

		assertThat(applied).isFalse();
		with()
		.get(email)
		.then()
			.body("source", hasSize(0));
	}
	
	@Test
	public void updateWithPreviousAliasPlusOneShouldAddTheNewAlias() throws Exception {
		String email = "user@james.org";
		String alias = "alias-to-user@james.org";
		String aliasToAdd = "alias-to-user_bis@james.org";
		createAlias(email, alias);
		
		testee = new JamesAliasDstService(task);

		LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
		modifications.setMainIdentifer(email);

		LscDatasetModification aliasesModification = new LscDatasetModification(
				LscDatasetModificationType.REPLACE_VALUES, "sources", ImmutableList.of(alias, aliasToAdd));
		
		modifications.setLscAttributeModifications(ImmutableList.of(aliasesModification));

		boolean applied = testee.apply(modifications);

		assertThat(applied).isTrue();
		with()
		.get(email)
		.then()
			.body("source", hasSize(2))
			.body("[0].source", equalTo(alias))
			.body("[1].source", equalTo(aliasToAdd));
	}
	
	@Test
	public void updateWithPreviousAliasesMinusOneShouldRemoveTheRemovedOne() throws Exception {
		String email = "user@james.org";
		String alias1 = "alias1-to-user@james.org";
		String alias2 = "alias2-to-user@james.org";
		String alias3 = "alias3-to-user@james.org";
		createAlias(email, alias1);
		createAlias(email, alias2);
		createAlias(email, alias3);
		
		testee = new JamesAliasDstService(task);

		LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
		modifications.setMainIdentifer(email);

		LscDatasetModification aliasesModification = new LscDatasetModification(
				LscDatasetModificationType.REPLACE_VALUES, "sources", ImmutableList.of(alias1, alias3));
		
		modifications.setLscAttributeModifications(ImmutableList.of(aliasesModification));

		boolean applied = testee.apply(modifications);

		assertThat(applied).isTrue();
		with()
		.get(email)
		.then()
			.body("source", hasSize(2))
			.body("[0].source", equalTo(alias1))
			.body("[1].source", equalTo(alias3));
	}
	
	@Test
	public void deleteShouldRemoveTheAliasesOfTheUser() throws Exception {
		String email = "user@james.org";
		String alias1 = "alias1-to-user@james.org";
		String alias2 = "alias2-to-user@james.org";
		createAlias(email, alias1);
		createAlias(email, alias2);
		
		testee = new JamesAliasDstService(task);

		LscModifications modifications = new LscModifications(LscModificationType.DELETE_OBJECT);
		modifications.setMainIdentifer(email);

	    modifications.setLscAttributeModifications(ImmutableList.of());

		boolean applied = testee.apply(modifications);

		assertThat(applied).isTrue();
		with()
		.get(email)
		.then()
			.body("source", hasSize(0));
	}
	
	@Test
	public void deleteShouldNotRemoveTheAliasesOfOthers() throws Exception {
		String email = "user@james.org";
		String alias1 = "alias1-to-user@james.org";
		String alias2 = "alias2-to-user@james.org";
		createAlias(email, alias1);
		createAlias(email, alias2);
		
		String emailUser2 = "user2@james.org";
		String alias1User2 = "alias1-to-user2@james.org";
		String alias2User2 = "alias2-to-user2@james.org";
		createAlias(emailUser2, alias1User2);
		createAlias(emailUser2, alias2User2);
		
		testee = new JamesAliasDstService(task);

		LscModifications modifications = new LscModifications(LscModificationType.DELETE_OBJECT);
		modifications.setMainIdentifer(email);

	    modifications.setLscAttributeModifications(ImmutableList.of());

		boolean applied = testee.apply(modifications);

		assertThat(applied).isTrue();
		with()
		.get(email)
		.then()
			.body("source", hasSize(0));
		
		with()
		.get(emailUser2)
		.then()
			.body("source", hasSize(2));
	}
	
	private void createAlias(String user, String alias) {
		with()
		.put("/{user}/sources/{alias}", user, alias)
		.then()
			.statusCode(HttpStatus.SC_NO_CONTENT);
	}

}
