package org.lsc.plugins.connectors.james.integration;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.core.Is.is;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.apache.http.HttpStatus;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

class WebadminPasswordIntegrationTest {
    private static final String DOMAIN = "james.org";
    private static final String BOB = "bob@james.org";
    private static final String ALICE = "alice@james.org";
    private static final String JAMES_WEBADMIN_PASSWORD = "verybigsecret";
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
    void setup() {
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
            .addHeader("Authorization", "Bearer " + JAMES_WEBADMIN_PASSWORD)
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
        james.withCopyFileToContainer(MountableFile.forClasspathResource("conf/webadmin-password/webadmin.properties"), "/root/conf/");
        // JWT key for JMAP...
        james.withCopyFileToContainer(MountableFile.forClasspathResource("conf/jwt_publickey"), "/root/conf/");
        james.withCopyFileToContainer(MountableFile.forClasspathResource("conf/jwt_privatekey"), "/root/conf/");
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
            .withCommand("./lsc JAVA_OPTS=\"-DLSC.PLUGINS.PACKAGEPATH=org.lsc.plugins.connectors.james.generated\" --config /opt/lsc/conf/ --synchronize all --threads 1")
            .withCopyFileToContainer(MountableFile.forClasspathResource("lsc-conf/logback.xml"), "/opt/lsc/conf/")
            .withCopyFileToContainer(MountableFile.forClasspathResource("lsc-conf/identity/webadmin-password/lsc.xml"), "/opt/lsc/conf/")
            .withCreateContainerCmdModifier(createContainerCmd -> createContainerCmd.withName("tmail-lsc-testing" + UUID.randomUUID()));
    }

    private void runLscContainer() {
        lscContainer
            .waitingFor(new LogMessageWaitStrategy().withRegEx(".*successfully modified entries:.*\\n")
                .withTimes(1)
                .withStartupTimeout(Duration.ofMinutes(2)))
            .start();
    }

    @Test
    void lscJobShouldCreateDefaultIdentityWhenWebadminPasswordEnabled() {
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
    }

}
