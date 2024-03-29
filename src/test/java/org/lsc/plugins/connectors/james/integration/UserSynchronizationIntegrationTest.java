package org.lsc.plugins.connectors.james.integration;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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

import org.apache.commons.lang.RandomStringUtils;
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

class UserSynchronizationIntegrationTest {
    private static final String DOMAIN = "james.org";
    private static final String BOB = "bob@james.org";
    private static final String ALICE = "alice@james.org";
    private static final String MISSING_FIRSTNAME_USER = "missingfirstname@james.org";
    private static final String ONLY_EXISTING_IN_JAMES_USER = "onlyexistinginjames@james.org";
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

        lscContainer = createLscContainer(network)
            .dependsOn(jamesContainer, ldapContainer);

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
        return james.waitingFor(new LogMessageWaitStrategy().withRegEx(".*JAMES server started.*\\n").withTimes(1)
            .withStartupTimeout(Duration.ofMinutes(3)));
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
            .withCommand("./lsc JAVA_OPTS=\"-DLSC.PLUGINS.PACKAGEPATH=org.lsc.plugins.connectors.james.generated\" --config /opt/lsc/conf/ --synchronize all --clean all --threads 1")
            .withCopyFileToContainer(MountableFile.forClasspathResource("lsc-conf/logback.xml"), "/opt/lsc/conf/")
            .withCopyFileToContainer(MountableFile.forClasspathResource("lsc-conf/user/lsc.xml"), "/opt/lsc/conf/")
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

    private void runLscContainer() {
        lscContainer
            .waitingFor(new LogMessageWaitStrategy().withRegEx(".*successfully modified entries:.*\\n")
                .withTimes(1)
                .withStartupTimeout(Duration.ofMinutes(2)))
            .start();
    }

    private void createUser(String user) {
        given(requestSpecification)
            .body("{\"password\":\"" + RandomStringUtils.randomAlphanumeric(24) + "\"}")
            .basePath("/users")
            .put("/{user}", user)
            .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);
    }

    @Test
    void userSyncJobShouldAddUsersFromLDAPToTMail() {
        // Run the LSC user sync job (LDAP entries: bob@james.org, alice@james.org, missingFirstname@james.org)
        runLscContainer();

        await.untilAsserted(() -> {
            String response = given(requestSpecification)
                .get("/users")
            .then()
                .statusCode(HttpStatus.SC_OK)
                .extract()
                .jsonPath()
                .getString("");

            assertThat(response).contains(BOB, ALICE, MISSING_FIRSTNAME_USER);
        });
    }

    @Test
    void userSyncJobShouldRemoveDanglingUserInTMail() {
        createUser(ONLY_EXISTING_IN_JAMES_USER);

        // Run the LSC user sync job (onlyexistinginjames@james.org is not in the LDAP entries)
        runLscContainer();

        // THEN the user ONLY_EXISTING_IN_JAMES_USER should be deleted out of TMail
        await.untilAsserted(() -> {
            String response = given(requestSpecification)
                .get("/users")
            .then()
                .statusCode(HttpStatus.SC_OK)
                .extract()
                .jsonPath()
                .getString("");

            assertThat(response).doesNotContain(ONLY_EXISTING_IN_JAMES_USER);
        });
    }
}
