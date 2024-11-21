package org.lsc.plugins.connectors.james.integration;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.MinimumDurationRunningStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.utility.MountableFile;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

class QuotaSizeSynchronizationIntegrationTest {
    private static final String DOMAIN = "james.org";
    private static final String BOB = "bob@james.org";
    private static final String ALICE = "alice@james.org";
    private static final String ANDRE = "missingFirstname@james.org";
    private static final String NOT_EXISTING_IN_LDAP_USER = "onlyExistingInJames@james.org";
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
        return new GenericContainer<>("osixia/openldap:1.5.0")
            .withClasspathResourceMapping("prepopulated-ldap/populate.ldif",
                "/container/service/slapd/assets/config/bootstrap/ldif/data.ldif", BindMode.READ_ONLY)
            .withClasspathResourceMapping("prepopulated-ldap/mailQuotaSize.ldif",
                "/container/service/slapd/assets/config/bootstrap/ldif/quotaSize.ldif", BindMode.READ_ONLY)
            .withClasspathResourceMapping("prepopulated-ldap/custom-schemas/mailQuotaSize.schema",
                "/container/service/slapd/assets/config/bootstrap/schema/custom.schema", BindMode.READ_ONLY)
            .withNetworkAliases("ldap")
            .withNetwork(network)
            .withEnv("LDAP_DOMAIN", "james.org")
            .withEnv("LDAP_ADMIN_PASSWORD", "mysecretpassword")
            .withEnv("LDAP_CONFIG_PASSWORD", "mysecretpassword")
            .withCreateContainerCmdModifier(createContainerCmd -> createContainerCmd.withName("team-mail-openldap-testing" + UUID.randomUUID()))
            .withExposedPorts(LDAP_PORT)
            .withCommand("--copy-service", "--loglevel", "debug")
            .waitingFor(new LogMessageWaitStrategy().withRegEx(".*slapd starting\\n").withTimes(1)
                .withStartupTimeout(Duration.ofMinutes(1)))
            .withStartupCheckStrategy(new MinimumDurationRunningStartupCheckStrategy(Duration.ofSeconds(10)));
    }

    private GenericContainer<?> createLscContainer(Network network) {
        return new GenericContainer<>("linagora/tmail-lsc:latest")
            .withNetworkAliases("lsc")
            .withNetwork(network)
            .withCommand("./lsc JAVA_OPTS=\"-DLSC.PLUGINS.PACKAGEPATH=org.lsc.plugins.connectors.james.generated\" --config /opt/lsc/conf/ --synchronize all --clean all --threads 1")
            .withCopyFileToContainer(MountableFile.forClasspathResource("lsc-conf/logback.xml"), "/opt/lsc/conf/")
            .withCopyFileToContainer(MountableFile.forClasspathResource("lsc-conf/quotaSize/lsc.xml"), "/opt/lsc/conf/")
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

    private void removeUser(String user) {
        given(requestSpecification)
            .basePath("/users")
            .delete("/{user}", user)
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);
    }

    private void setQuotaSize(String user, long quotaSize) {
        given(requestSpecification)
            .body(quotaSize)
            .put("/quota/users/{usernameToBeUsed}/size", user)
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);
    }

    private void assertUserHasQuotaSize(String username, long quotaSize) {
        given(requestSpecification)
            .get("/quota/users/{usernameToBeUsed}/size", username)
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body(Matchers.is(String.valueOf(quotaSize)));
    }

    private void assertUserHasNoQuotaSize(String username) {
        given(requestSpecification)
            .get("/quota/users/{usernameToBeUsed}/size", username)
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);
    }

    @Test
    void lscJobShouldCreateQuotaSizeInJames() {
        // GIVEN BOB and ALICE entries in LDAP. BOB has mailQuotaSize: 4000000000 and ALICE has mailQuotaSize: -1 in LDAP but no in James.
        createUser(BOB);
        createUser(ALICE);

        // RUN the LSC quotaSize sync job
        runLscContainer();

        // THEN BOB and ALICE should have quota size created in James
        await.untilAsserted(() -> {
            assertThatCode(() -> {
                assertUserHasQuotaSize(BOB, 4000000000L);
                assertUserHasQuotaSize(ALICE, -1L);
            }).doesNotThrowAnyException();
        });
    }

    @Test
    void lscJobShouldNotCreateQuotaSizeInJamesIfUserDoesNotHaveQuotaSizeOnLDAP() {
        // GIVEN ANDRE in LDAP, and he does not have mailQuotaSize.
        createUser(ANDRE);

        // RUN the LSC quota size job
        runLscContainer();

        // THEN Andre's quota size should stay empty
        await.untilAsserted(() -> assertThatCode(() -> assertUserHasNoQuotaSize(ANDRE))
            .doesNotThrowAnyException());
    }

    @Test
    void lscJobWouldNotCreateQuotaSizeWhenUserDoesNotExistYetInJames() {
        // GIVEN BOB entry in LDAP. BOB has mailQuotaSize: 4000000000 in LDAP, but BOB does not exist yet in James.
        removeUser(BOB);

        // RUN the LSC quota size job
        runLscContainer();

        // THEN BOB would not be created yet (wait for BOB user to be created first).
        await.untilAsserted(() -> assertThatCode(() ->
            given(requestSpecification)
                .get("/quota/users/{usernameToBeUsed}/size", BOB)
            .then()
                .statusCode(HttpStatus.SC_NOT_FOUND))
            .doesNotThrowAnyException());
    }

    @Test
    void lscJobShouldUpdateQuotaSizeInJamesLimitedQuotaCase() {
        // GIVEN BOB entry in LDAP. BOB has an old quota size 1000000000 in James while in LDAP he has mailQuotaSize: 4000000000
        createUser(BOB);
        setQuotaSize(BOB, 1000000000L);

        // RUN the LSC quota size job
        runLscContainer();

        // THEN Bob's quota size should be updated
        await.untilAsserted(() -> assertThatCode(() -> assertUserHasQuotaSize(BOB, 4000000000L))
            .doesNotThrowAnyException());
    }

    @Test
    void lscJobShouldUpdateQuotaSizeInJamesUnlimitedQuotaCase() {
        // GIVEN ALICE entry in LDAP. ALICE has an old quota size 1000000000 in James while in LDAP she has unlimited mailQuotaSize: -1
        createUser(ALICE);
        setQuotaSize(ALICE, 1000000000L);

        // RUN the LSC quota size job
        runLscContainer();

        // THEN Alice's quota size should be updated to unlimited
        await.untilAsserted(() -> assertThatCode(() -> assertUserHasQuotaSize(ALICE, -1L))
            .doesNotThrowAnyException());
    }

    @Test
    void lscJobShouldDeleteQuotaSizeInJamesIfNonQuotaSizeOnLDAP() {
        // GIVEN ANDRE entry in LDAP. ANDRE has an old quota size 1000000000 in James while in LDAP he does not have mailQuotaSize anymore.
        createUser(ANDRE);
        setQuotaSize(ANDRE, 1000000000L);

        // RUN the LSC quota size job
        runLscContainer();

        // THEN Andre's quota size should be removed
        await.untilAsserted(() -> assertThatCode(() -> assertUserHasNoQuotaSize(ANDRE))
            .doesNotThrowAnyException());
    }

    @Test
    void lscJobShouldRemoveDanglingQuotaSizeInJames() {
        // GIVEN NOT_EXISTING_IN_LDAP_USER in JAMES while she does not exist in LDAP.
        createUser(NOT_EXISTING_IN_LDAP_USER);
        setQuotaSize(NOT_EXISTING_IN_LDAP_USER, 1000000000L);

        // RUN the LSC quota size job
        runLscContainer();

        // THEN her quota size on James would be deleted
        await.untilAsserted(() -> assertThatCode(() -> assertUserHasNoQuotaSize(NOT_EXISTING_IN_LDAP_USER))
            .doesNotThrowAnyException());
    }

}
