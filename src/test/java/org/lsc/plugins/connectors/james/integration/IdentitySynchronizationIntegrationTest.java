package org.lsc.plugins.connectors.james.integration;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;

import java.io.FileReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.apache.http.HttpStatus;
import org.awaitility.core.ConditionFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

class IdentitySynchronizationIntegrationTest {
    private static final String DOMAIN = "james.org";
    private static final String BOB = "bob@james.org";
    private static final String ALICE = "alice@james.org";
    private static final String MISSING_FIRSTNAME_USER = "missingFirstname@james.org";
    private static final String ONLY_EXISTING_IN_JAMES_USER = "onlyExistingInJames@james.org";
    private static final URL PRIVATE_KEY = ClassLoader.getSystemResource("conf/jwt_privatekey");
    private static final URL PUBLIC_KEY = ClassLoader.getSystemResource("conf/jwt_publickey");
    private static final int JAMES_WEBADMIN_PORT = 8000;
    private static final int LDAP_PORT = 389;
    private static final ConditionFactory await = await()
        .pollDelay(Duration.ofSeconds(1))
        .timeout(Duration.ofSeconds(10));

    private GenericContainer<?> jamesContainer;
    private GenericContainer<?> ldapContainer;
    private GenericContainer<?> lscContainer;
    private RequestSpecification requestSpecification;

    @BeforeEach
    void setup() throws Exception {
        Network network = Network.newNetwork();
        jamesContainer = createJamesContainer(network);
        jamesContainer.start();

        ldapContainer = createLdapContainer(network);
        ldapContainer.start();

        lscContainer = createLscContainer(network);

        requestSpecification = new RequestSpecBuilder().setPort(jamesContainer.getMappedPort(JAMES_WEBADMIN_PORT))
            .setContentType(ContentType.JSON).setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .addHeader("Authorization", "Bearer " + jwtToken())
            .setBasePath("")
            .build();

        given(requestSpecification).basePath("/domains").put(DOMAIN).then().statusCode(HttpStatus.SC_NO_CONTENT);
    }

    @AfterEach
    void tearDown() {
        jamesContainer.close();
        ldapContainer.close();
        if (lscContainer.isRunning()) {
            lscContainer.close();
        }
    }

    private GenericContainer<?> createJamesContainer(Network network) {
        GenericContainer<?> james = new GenericContainer<>("linagora/tmail-backend:memory-branch-master")
            .withNetworkAliases("james")
            .withNetwork(network)
            .withExposedPorts(JAMES_WEBADMIN_PORT);
        james.withCopyFileToContainer(MountableFile.forClasspathResource("conf/jwt_publickey"), "/root/conf/");
        james.withCopyFileToContainer(MountableFile.forClasspathResource("conf/jwt_privatekey"), "/root/conf/");
        james.withCopyFileToContainer(MountableFile.forClasspathResource("conf/webadmin.properties"), "/root/conf/");
        return james;
    }

    private GenericContainer<?> createLdapContainer(Network network) {
        return new GenericContainer<>(
            new ImageFromDockerfile()
                .withFileFromClasspath("populate.ldif", "prepopulated-ldap/populate.ldif")
                .withFileFromClasspath("Dockerfile", "prepopulated-ldap/Dockerfile"))
            .withNetworkAliases("ldap")
            .withNetwork(network)
            .withEnv("SLAPD_DOMAIN", "james.org")
            .withEnv("SLAPD_PASSWORD", "mysecretpassword")
            .withEnv("SLAPD_CONFIG_PASSWORD", "mysecretpassword")
            .withCreateContainerCmdModifier(createContainerCmd -> createContainerCmd.withName("team-mail-openldap-testing" + UUID.randomUUID()))
            .withExposedPorts(LDAP_PORT)
            .waitingFor(new LogMessageWaitStrategy().withRegEx(".*slapd starting*\\n")
            .withTimes(1)
            .withStartupTimeout(Duration.ofMinutes(3)));
    }

    private GenericContainer<?> createLscContainer(Network network) {
        return new GenericContainer<>("linagora/tmail-lsc:latest")
            .withNetworkAliases("lsc")
            .withNetwork(network)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512000000L))
            .withCommand("./lsc JAVA_OPTS=\"-DLSC.PLUGINS.PACKAGEPATH=org.lsc.plugins.connectors.james.generated\" --config /opt/lsc/conf/ --synchronize all --threads 1")
            .withCopyFileToContainer(MountableFile.forClasspathResource("lsc-conf/logback.xml"), "/opt/lsc/conf/")
            .withCopyFileToContainer(MountableFile.forClasspathResource("lsc-conf/identity/lsc.xml"), "/opt/lsc/conf/")
            .withCreateContainerCmdModifier(createContainerCmd -> createContainerCmd.withName("tmail-lsc-testing" + UUID.randomUUID()));
    }

    private String jwtToken() throws Exception {
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

    private RSAPublicKey getPublicKey() throws Exception {
        try (PEMReader pemReader = new PEMReader(new FileReader(PUBLIC_KEY.getFile()))) {
            Object readObject = pemReader.readObject();
            return (org.bouncycastle.jce.provider.JCERSAPublicKey) readObject;
        }
    }

    private RSAPrivateKey getPrivateKey() throws Exception {
        try (PEMReader pemReader = new PEMReader(new FileReader(PRIVATE_KEY.getFile()), "james"::toCharArray)) {
            Object readObject = pemReader.readObject();
            return (RSAPrivateKey) ((java.security.KeyPair) readObject).getPrivate();
        }
    }

    private void createUserIdentity(String user, String displayName) {
        given(requestSpecification)
            .body(String.format("{\n" +
                "\t\"name\": \"%s\",\n" +
                "\t\"email\": \"%s\"\n" +
                "}", displayName, user))
            .post("/users/{user}/identities", user)
        .then()
            .statusCode(HttpStatus.SC_CREATED);
    }

    private void runLscContainer() {
        lscContainer
            .waitingFor(new LogMessageWaitStrategy().withRegEx(".*successfully modified entries:.*\\n")
                .withTimes(1)
                .withStartupTimeout(Duration.ofMinutes(2)))
            .start();
    }

    @Test
    void lscJobShouldCreateDefaultIdentity() {
        // GIVEN BOB and ALICE entries in LDAP, while they have no default identity on James yet

        // RUN the LSC identity job
        runLscContainer();

        // THEN BOB and ALICE should have default identity provisioned
        await.untilAsserted(() -> {
            assertThatCode(() -> {
                given(requestSpecification)
                    .get(String.format("/users/%s/identities?default=true", BOB))
                .then()
                    .statusCode(HttpStatus.SC_OK)
                    .body("[0].name", is("bobFirstname bobSurname"));

                given(requestSpecification)
                    .get(String.format("/users/%s/identities?default=true", ALICE))
                .then()
                    .statusCode(HttpStatus.SC_OK)
                    .body("[0].name", is("aliceFirstname aliceSurname"));
            }).doesNotThrowAnyException();
        });

        System.out.println(123);
    }

    @Test
    void shouldNotCreateNewIdentityWhileUserAlreadyHasADefaultIdentity() {
        // GIVEN BOB in LDAP, BOB already has a default identity
        createUserIdentity(BOB, "BOB original display name");

        // 1 server-set identity and 1 user identity
        given(requestSpecification)
            .get(String.format("/users/%s/identities", BOB))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("", hasSize(2));

        // RUN the LSC identity job
        runLscContainer();

        // THEN LSC job should not create new identity for BOB
        await.untilAsserted(() ->
            assertThatCode(() ->
                given(requestSpecification)
                    .get(String.format("/users/%s/identities", BOB))
                .then()
                    .statusCode(HttpStatus.SC_OK)
                    .body("", hasSize(2))).doesNotThrowAnyException());
    }

    @Test
    void shouldNotOverrideDefaultIdentityWhileUserAlreadyHasADefaultIdentity() {
        // GIVEN BOB in LDAP, BOB already has a default identity
        createUserIdentity(BOB, "BOB original display name");

        // RUN the LSC identity job
        runLscContainer();

        // THEN BOB 's default identity should not be overridden by the LSC job
        await.untilAsserted(() ->
            assertThatCode(() ->
                given(requestSpecification)
                    .get(String.format("/users/%s/identities?default=true", BOB))
                .then()
                    .statusCode(HttpStatus.SC_OK)
                    .body("[0].name", not("bobFirstname bobSurname"))
                    .body("[0].name", is("BOB original display name"))).doesNotThrowAnyException());
    }

    @Test
    void mixedCase() {
        // GIVEN BOB and ALICE entries in LDAP, BOB already has a default identity while ALICE does not.
        createUserIdentity(BOB, "BOB original display name");

        // RUN the LSC identity job
        runLscContainer();

        // THEN BOB default identity should not be overridden, while ALICE default identity should be created
        await.untilAsserted(() -> {
            assertThatCode(() -> {
                given(requestSpecification)
                    .get(String.format("/users/%s/identities?default=true", BOB))
                .then()
                    .statusCode(HttpStatus.SC_OK)
                    .body("[0].name", not("bobFirstname bobSurname"))
                    .body("[0].name", is("BOB original display name"));

                given(requestSpecification)
                    .get(String.format("/users/%s/identities?default=true", ALICE))
                .then()
                    .statusCode(HttpStatus.SC_OK)
                    .body("[0].name", is("aliceFirstname aliceSurname"));
            }).doesNotThrowAnyException();
        });
    }

    @Test
    void givenAnUserOnlyExistsInJamesThenLscJobShouldNotDeleteDefaultIdentityOfThatUser() {
        // GIVEN ONLY_EXISTING_IN_JAMES_USER only exists in James but not in LDAP, with default identity created
        createUserIdentity(ONLY_EXISTING_IN_JAMES_USER, "default display name");

        // RUN the LSC identity job
        runLscContainer();

        // THEN LSC job should not delete the default identity of the user
        await.untilAsserted(() ->
            assertThatCode(() ->
                given(requestSpecification)
                    .get(String.format("/users/%s/identities?default=true", ONLY_EXISTING_IN_JAMES_USER))
                .then()
                    .statusCode(HttpStatus.SC_OK)
                    .body("[0].name", is("default display name"))).doesNotThrowAnyException());

    }

    @Test
    void givenAnUserDoesNotExistInLDAPThenLscJobShouldNotCreateDefaultIdentityForThatUser() {
        // GIVEN ONLY_EXISTING_IN_JAMES_USER only exists in James but not in LDAP

        // RUN the LSC identity job
        runLscContainer();

        // THEN LSC job should not create the default identity for the user
        await.untilAsserted(() ->
            assertThatCode(() ->
                given(requestSpecification)
                    .get(String.format("/users/%s/identities?default=true", ONLY_EXISTING_IN_JAMES_USER))
                .then()
                    .statusCode(HttpStatus.SC_NOT_FOUND)
                    .body("message", is("Default identity can not be found"))).doesNotThrowAnyException());
    }

    @Test
    void missingGivenNameOnLDAPShouldSetSurnameAsDisplayName() {
        // GIVEN MISSING_FIRSTNAME_USER entry in LDAP which does not have `givenName` attribute

        // RUN the LSC identity job
        runLscContainer();

        // THEN LSC job should set surname as identity display name
        await.untilAsserted(() ->
            assertThatCode(() ->
                given(requestSpecification)
                    .get(String.format("/users/%s/identities?default=true", MISSING_FIRSTNAME_USER))
                .then()
                    .statusCode(HttpStatus.SC_OK)
                    .body("[0].name", is("missingFirstname_surname"))).doesNotThrowAnyException());
    }
}
