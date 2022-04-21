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
 *          Tung Tran Van <vttran@linagora.com>
 ****************************************************************************
 */
package org.lsc.plugins.connectors.james;

import static io.restassured.RestAssured.with;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
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
import java.util.Map;
import java.util.stream.Stream;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.lsc.LscDatasets;
import org.lsc.LscModificationType;
import org.lsc.LscModifications;
import org.lsc.configuration.PluginConnectionType;
import org.lsc.configuration.PluginDestinationServiceType;
import org.lsc.configuration.ServiceType.Connection;
import org.lsc.configuration.TaskType;
import org.lsc.plugins.connectors.james.generated.JamesUsersService;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

public class JamesUserDstServiceTest {
    private static final URL PRIVATE_KEY = ClassLoader.getSystemResource("conf/private.pem");
    private static final URL PUBLIC_KEY = ClassLoader.getSystemResource("conf/jwt_publickey");
    private static final String DOMAIN = "james.org";
    public static final String USER = "bob@" + DOMAIN;
    private static final int JAMES_WEBADMIN_PORT = 8000;
    private static final boolean FROM_SAME_SERVICE = true;

    private static GenericContainer<?> james;
    private static TaskType task;
    private static int MAPPED_JAMES_WEBADMIN_PORT;
    private static JamesUserDstService testee;

    @BeforeAll
    static void setup() throws Exception {
        james = new GenericContainer<>("linagora/james-memory:tmail-0.2.0");
        String webadmin = ClassLoader.getSystemResource("conf/webadmin.properties").getFile();
        james.withExposedPorts(JAMES_WEBADMIN_PORT)
            .withFileSystemBind(PUBLIC_KEY.getFile(), "/root/conf/jwt_publickey", BindMode.READ_ONLY)
            .withFileSystemBind(webadmin, "/root/conf/webadmin.properties", BindMode.READ_ONLY)
            .start();

        MAPPED_JAMES_WEBADMIN_PORT = james.getMappedPort(JAMES_WEBADMIN_PORT);

        JamesUsersService jamesUsersService = mock(JamesUsersService.class);
        PluginDestinationServiceType pluginDestinationService = mock(PluginDestinationServiceType.class);
        PluginConnectionType jamesConnection = mock(PluginConnectionType.class);
        Connection connection = mock(Connection.class);
        task = mock(TaskType.class);

        when(jamesConnection.getUrl()).thenReturn("http://localhost:" + MAPPED_JAMES_WEBADMIN_PORT);
        when(jamesConnection.getPassword()).thenReturn(jwtToken());
        when(connection.getReference()).thenReturn(jamesConnection);
        when(jamesUsersService.getConnection()).thenReturn(connection);
        when(task.getBean()).thenReturn("org.lsc.beans.SimpleBean");
        when(task.getPluginDestinationService()).thenReturn(pluginDestinationService);
        when(pluginDestinationService.getAny()).thenReturn(ImmutableList.of(jamesUsersService));
        testee = new JamesUserDstService(task);

        RestAssured.requestSpecification = new RequestSpecBuilder().setPort(MAPPED_JAMES_WEBADMIN_PORT)
            .setContentType(ContentType.JSON).setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .addHeader("Authorization", "Bearer " + jwtToken())
            .setBasePath("/users").build();
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
            return (org.bouncycastle.jce.provider.JCERSAPublicKey) readObject;
        }
    }

    private static RSAPrivateKey getPrivateKey(KeyFactory keyFactory) throws Exception {
        try (PEMReader pemReader = new PEMReader(new FileReader(PRIVATE_KEY.getFile()), () -> "james".toCharArray())) {
            Object readObject = pemReader.readObject();
            return (RSAPrivateKey) ((java.security.KeyPair) readObject).getPrivate();
        }
    }

    @AfterAll
    static void close() {
        james.close();
    }

    @AfterEach
    void removeAllUser() throws Exception {
        JamesDao jamesDao = new JamesDao("http://localhost:" + MAPPED_JAMES_WEBADMIN_PORT, jwtToken(), task);
        jamesDao.getUserList()
            .forEach(jamesDao::removeUser);
    }

    @Test
    void jamesUserListShouldReturnEmptyWhenNoUser() throws Exception {
        with()
            .get("")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("", hasSize(0));
    }

    @Test
    void getListPivotsShouldReturnEmptyWhenNoUser() throws Exception {
        Map<String, LscDatasets> listPivots = testee.getListPivots();

        assertThat(listPivots).isEmpty();
    }

    @Test
    void getListPivotsShouldReturnOneWhenOneUser() throws Exception {
        createUsers(USER);

        Map<String, LscDatasets> listPivots = testee.getListPivots();

        assertSoftly(softly -> {
            softly.assertThat(listPivots).containsOnlyKeys(USER);
            softly.assertThat(listPivots.get(USER).getStringValueAttribute("email")).isEqualTo(USER);
        });
    }

    @Test
    void getListPivotsShouldReturnTwoWhenTwoUsers() throws Exception {
        String user1 = "user1@james.org";
        String user2 = "user2@james.org";
        createUsers(user1);
        createUsers(user2);

        Map<String, LscDatasets> listPivots = testee.getListPivots();

        assertSoftly(softly -> {
            softly.assertThat(listPivots).hasSize(2);
            softly.assertThat(listPivots).containsOnlyKeys(user1, user2);
        });
    }

    @Test
    void createUserShouldSuccessWhenMissingUser() throws Exception {
        LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
        modifications.setMainIdentifer(USER);
        modifications.setLscAttributeModifications(ImmutableList.of());

        boolean applied = testee.apply(modifications);
        assertThat(applied).isTrue();

        with()
            .head(USER)
            .then()
            .statusCode(HttpStatus.SC_OK);
    }

    @Test
    void createUserShouldReturnFalseWhenUserIsExisting() throws Exception {
        createUsers(USER);
        LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
        modifications.setMainIdentifer(USER);
        modifications.setLscAttributeModifications(ImmutableList.of());

        boolean applied = testee.apply(modifications);
        assertThat(applied).isFalse();
    }

    @Test
    void createUserShouldReturnFalseWhenMissingDomainPart() throws Exception {
        LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
        modifications.setMainIdentifer("user");
        modifications.setLscAttributeModifications(ImmutableList.of());

        boolean applied = testee.apply(modifications);
        assertThat(applied).isFalse();
    }

    @Test
    void removeUserShouldSuccessWhenDanglingUser() throws Exception {
        createUsers(USER);

        LscModifications modifications = new LscModifications(LscModificationType.DELETE_OBJECT);
        modifications.setMainIdentifer(USER);
        modifications.setLscAttributeModifications(ImmutableList.of());

        boolean applied = testee.apply(modifications);
        assertThat(applied).isTrue();
        with()
            .get(USER)
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND);
    }

    @Test
    void removeUserShouldSucceedWhenUserDoesNotExist() throws Exception {
        LscModifications modifications = new LscModifications(LscModificationType.DELETE_OBJECT);
        modifications.setMainIdentifer(USER);
        modifications.setLscAttributeModifications(ImmutableList.of());

        boolean applied = testee.apply(modifications);
        assertThat(applied).isTrue();
        with()
            .get(USER)
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND);
    }

    @Test
    void removeUserShouldNotRemoveAllUsers() throws Exception {
        String user1 = "user1@james.org";
        String user2 = "user2@james.org";
        createUsers(user1);
        createUsers(user2);

        LscModifications modifications = new LscModifications(LscModificationType.DELETE_OBJECT);
        modifications.setMainIdentifer(user1);
        modifications.setLscAttributeModifications(ImmutableList.of());

        boolean applied = testee.apply(modifications);
        assertThat(applied).isTrue();

        with()
            .head(user2)
        .then()
            .statusCode(HttpStatus.SC_OK);
    }

    @ParameterizedTest
    @MethodSource("nonSupportedOperations")
    void nonsupportOperationShouldNotRemoveUser(LscModificationType lscModificationType) throws Exception {
        createUsers(USER);

        LscModifications modifications = new LscModifications(lscModificationType);
        modifications.setMainIdentifer(USER);
        modifications.setLscAttributeModifications(ImmutableList.of());

        boolean applied = testee.apply(modifications);
        assertThat(applied).isTrue();

        with()
            .head(USER)
        .then()
            .statusCode(HttpStatus.SC_OK);
    }

    @ParameterizedTest
    @MethodSource("nonSupportedOperations")
    void nonsupportOperationShouldNotAddUser(LscModificationType lscModificationType) throws Exception {
        LscModifications modifications = new LscModifications(lscModificationType);
        modifications.setMainIdentifer(USER);
        modifications.setLscAttributeModifications(ImmutableList.of());

        boolean applied = testee.apply(modifications);
        assertThat(applied).isTrue();

        with()
            .head(USER)
            .then()
            .statusCode(HttpStatus.SC_NOT_FOUND);
    }

    @Test
    void getBeanShouldReturnNullWhenUserDoesNotExist() throws Exception {
        LscDatasets datasets = new LscDatasets(ImmutableMap.of("email", USER));
        assertThat(testee.getBean("email", datasets, FROM_SAME_SERVICE)).isNull();
    }

    @Test
    void getBeanShouldReturnNotNullWhenUserExists() throws Exception {
        createUsers(USER);

        LscDatasets datasets = new LscDatasets(ImmutableMap.of("email", USER));
        assertThat(testee.getBean("email", datasets, FROM_SAME_SERVICE)).isNotNull();
    }

    static Stream<Arguments> nonSupportedOperations() {
        return Stream.of(
            LscModificationType.UPDATE_OBJECT,
            LscModificationType.CHANGE_ID)
            .map(Arguments::of);
    }

    private void createUsers(String user) {
        with()
            .body("{\"password\":\"" + RandomStringUtils.randomAlphanumeric(24) + "\"}")
            .put("/{user}", user)
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);
    }
}
