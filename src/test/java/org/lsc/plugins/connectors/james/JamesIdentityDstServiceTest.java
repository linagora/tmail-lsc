package org.lsc.plugins.connectors.james;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;
import static org.lsc.plugins.connectors.james.TMailContactDstService.FIRSTNAME_KEY;
import static org.lsc.plugins.connectors.james.TMailContactDstService.SURNAME_KEY;
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

import org.apache.commons.lang.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lsc.LscDatasetModification;
import org.lsc.LscDatasets;
import org.lsc.LscModificationType;
import org.lsc.LscModifications;
import org.lsc.beans.IBean;
import org.lsc.configuration.PluginConnectionType;
import org.lsc.configuration.PluginDestinationServiceType;
import org.lsc.configuration.ServiceType.Connection;
import org.lsc.configuration.TaskType;
import org.lsc.plugins.connectors.james.generated.JamesIdentityService;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.MountableFile;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

class JamesIdentityDstServiceTest {
    public static final String DOMAIN = "james.org";
    public static final String BOB = "bob@" + DOMAIN;
    public static final String ALICE = "alice@" + DOMAIN;
    private static final URL PRIVATE_KEY = ClassLoader.getSystemResource("conf/jwt_privatekey");
    private static final URL PUBLIC_KEY = ClassLoader.getSystemResource("conf/jwt_publickey");
    private static final int JAMES_WEBADMIN_PORT = 8000;
    private static final boolean FROM_SAME_SERVICE = true;

    private GenericContainer<?> james;
    private JamesIdentityDstService testee;
    private RequestSpecification requestSpecification;

    @BeforeEach
    void setup() throws Exception {
        james = new GenericContainer<>("linagora/tmail-backend:memory-branch-master");
        james.withCopyFileToContainer(MountableFile.forClasspathResource("conf/jwt_publickey"), "/root/conf/");
        james.withCopyFileToContainer(MountableFile.forClasspathResource("conf/jwt_privatekey"), "/root/conf/");
        james.withCopyFileToContainer(MountableFile.forClasspathResource("conf/webadmin.properties"), "/root/conf/");
        james.withExposedPorts(JAMES_WEBADMIN_PORT).start();

        int MAPPED_JAMES_WEBADMIN_PORT = james.getMappedPort(JAMES_WEBADMIN_PORT);

        JamesIdentityService jamesIdentityService = mock(JamesIdentityService.class);
        PluginDestinationServiceType pluginDestinationService = mock(PluginDestinationServiceType.class);
        PluginConnectionType jamesConnection = mock(PluginConnectionType.class);
        Connection connection = mock(Connection.class);
        TaskType task = mock(TaskType.class);

        when(jamesConnection.getUrl()).thenReturn("http://localhost:" + MAPPED_JAMES_WEBADMIN_PORT);
        when(jamesConnection.getPassword()).thenReturn(jwtToken());
        when(connection.getReference()).thenReturn(jamesConnection);
        when(jamesIdentityService.getConnection()).thenReturn(connection);
        when(task.getBean()).thenReturn("org.lsc.beans.SimpleBean");
        when(task.getPluginDestinationService()).thenReturn(pluginDestinationService);
        when(pluginDestinationService.getAny()).thenReturn(ImmutableList.of(jamesIdentityService));
        testee = new JamesIdentityDstService(task);

        requestSpecification = new RequestSpecBuilder().setPort(MAPPED_JAMES_WEBADMIN_PORT)
            .setContentType(ContentType.JSON).setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .addHeader("Authorization", "Bearer " + jwtToken())
            .setBasePath("")
            .build();
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

        given(requestSpecification).basePath("/domains").put(DOMAIN).then().statusCode(HttpStatus.SC_NO_CONTENT);
    }

    @AfterEach
    void tearDown() {
        james.close();
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

    private void createUser(String user) {
        given(requestSpecification)
            .body("{\"password\":\"" + RandomStringUtils.randomAlphanumeric(24) + "\"}")
            .put("/users/{user}", user)
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);
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

    @Test
    void getUserDefaultIdentityShouldReturn404ByDefault() {
        createUser(BOB);

        given(requestSpecification)
            .get(String.format("/users/%s/identities?default=true", BOB))
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND)
            .body("message", is("Default identity can not be found"));
    }

    @Test
    void getListPivotsShouldReturnUserList() throws Exception {
        createUser(BOB);
        createUser(ALICE);

        Map<String, LscDatasets> listPivots = testee.getListPivots();

        assertSoftly(softly -> {
            softly.assertThat(listPivots).containsOnlyKeys(BOB, ALICE);
            softly.assertThat(listPivots.get(BOB).getStringValueAttribute("email")).isEqualTo(BOB);
            softly.assertThat(listPivots.get(ALICE).getStringValueAttribute("email")).isEqualTo(ALICE);
        });
    }

    @Test
    void getBeanShouldReturnNullWhenEmptyPivotOnBothJamesAndLDAP() throws Exception {
        assertThat(testee.getBean("email", new LscDatasets(), FROM_SAME_SERVICE)).isNull();
    }

    @Test
    void getBeanShouldReturnNullWhenEmptyPivotOnJames() throws Exception {
        LscDatasets ldapDataset = new LscDatasets(ImmutableMap.of("email", "nonExistingEmail@james.org"));
        assertThat(testee.getBean("email", ldapDataset, FROM_SAME_SERVICE)).isNull();
    }

    @Test
    void getBeanShouldReturnNullWhenEmptyPivotOnLDAP() throws Exception {
        createUser(BOB);
        createUserIdentity(BOB, "Bob John");

        assertThat(testee.getBean("email", new LscDatasets(), FROM_SAME_SERVICE)).isNull();
    }

    @Test
    void getBeanShouldNotReturnNullWhenUserHasDefaultIdentityAndHasPivotOnLDAP() throws Exception {
        createUser(BOB);
        createUserIdentity(BOB, "Bob John");

        LscDatasets ldapDataset = new LscDatasets(ImmutableMap.of("email", BOB));
        IBean bean = testee.getBean("email", ldapDataset, FROM_SAME_SERVICE);
        assertThat(bean.getMainIdentifier()).isEqualTo(BOB);
        assertThat(bean.getDatasetFirstValueById("email")).isEqualTo(BOB);
    }

    @Test
    void updateOperationShouldNotBeSupported() throws Exception {
        createUser(BOB);
        createUserIdentity(BOB, "Bob John");

        LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
        modifications.setMainIdentifer(BOB);
        LscDatasetModification firstname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.REPLACE_VALUES, FIRSTNAME_KEY, ImmutableList.of("whatever firstname"));
        LscDatasetModification surname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.REPLACE_VALUES, SURNAME_KEY, ImmutableList.of("whatever surname"));
        modifications.setLscAttributeModifications(ImmutableList.of(firstname, surname));

        boolean applied = testee.apply(modifications);
        assertThat(applied).isTrue();

        given(requestSpecification)
            .get(String.format("/users/%s/identities?default=true", BOB))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("[0].name", is("Bob John"));
    }

    @Test
    void deleteOperationShouldNotBeSupported() throws Exception {
        createUser(BOB);
        createUserIdentity(BOB, "Bob John");

        LscModifications modifications = new LscModifications(LscModificationType.DELETE_OBJECT);
        modifications.setMainIdentifer(BOB);
        modifications.setLscAttributeModifications(ImmutableList.of());

        boolean applied = testee.apply(modifications);
        assertThat(applied).isTrue();

        given(requestSpecification)
            .get(String.format("/users/%s/identities?default=true", BOB))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("[0].name", is("Bob John"));
    }

    @Test
    void createShouldFailWhenMainIdentifierIsMissing() throws Exception {
        LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
        modifications.setLscAttributeModifications(ImmutableList.of());

        boolean applied = testee.apply(modifications);

        assertThat(applied).isFalse();
    }

    @Test
    void givenUserHasNoDefaultIdentityThenTaskShouldCreateIdentityEmptyFirstnameAndEmptySurnameCase() throws Exception {
        createUser(BOB);

        LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
        modifications.setMainIdentifer(BOB);
        modifications.setLscAttributeModifications(ImmutableList.of());

        boolean applied = testee.apply(modifications);

        assertThat(applied).isTrue();
        given(requestSpecification)
            .get(String.format("/users/%s/identities?default=true", BOB))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("", hasSize(1))
            .body("[0].name", is(BOB))
            .body("[0].email", is(BOB))
            .body("[0].mayDelete", is(true))
            .body("[0].sortOrder", is(0));
    }

    @Test
    void givenUserHasNoDefaultIdentityThenTaskShouldCreateIdentityNonEmptyFirstnameAndEmptySurnameCase() throws Exception {
        createUser(BOB);

        LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
        modifications.setMainIdentifer(BOB);
        LscDatasetModification firstname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.ADD_VALUES, FIRSTNAME_KEY, ImmutableList.of("Firstname"));
        modifications.setLscAttributeModifications(ImmutableList.of(firstname));

        boolean applied = testee.apply(modifications);

        assertThat(applied).isTrue();
        given(requestSpecification)
            .get(String.format("/users/%s/identities?default=true", BOB))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("", hasSize(1))
            .body("[0].name", is("Firstname"))
            .body("[0].email", is(BOB))
            .body("[0].mayDelete", is(true))
            .body("[0].sortOrder", is(0));
    }

    @Test
    void givenUserHasNoDefaultIdentityThenTaskShouldCreateIdentityEmptyFirstnameAndNonEmptySurnameCase() throws Exception {
        createUser(BOB);

        LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
        modifications.setMainIdentifer(BOB);
        LscDatasetModification surname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.ADD_VALUES, SURNAME_KEY, ImmutableList.of("Surname"));
        modifications.setLscAttributeModifications(ImmutableList.of(surname));

        boolean applied = testee.apply(modifications);

        assertThat(applied).isTrue();
        given(requestSpecification)
            .get(String.format("/users/%s/identities?default=true", BOB))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("", hasSize(1))
            .body("[0].name", is("Surname"))
            .body("[0].email", is(BOB))
            .body("[0].mayDelete", is(true))
            .body("[0].sortOrder", is(0));
    }

    @Test
    void givenUserHasNoDefaultIdentityThenTaskShouldCreateIdentityNonEmptyFirstnameAndNonEmptySurnameCase() throws Exception {
        createUser(BOB);

        LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
        modifications.setMainIdentifer(BOB);
        LscDatasetModification firstname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.ADD_VALUES, FIRSTNAME_KEY, ImmutableList.of("Firstname"));
        LscDatasetModification surname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.ADD_VALUES, SURNAME_KEY, ImmutableList.of("Surname"));
        modifications.setLscAttributeModifications(ImmutableList.of(firstname, surname));

        boolean applied = testee.apply(modifications);

        assertThat(applied).isTrue();
        given(requestSpecification)
            .get(String.format("/users/%s/identities?default=true", BOB))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("", hasSize(1))
            .body("[0].name", is("Firstname Surname"))
            .body("[0].email", is(BOB))
            .body("[0].mayDelete", is(true))
            .body("[0].sortOrder", is(0));
    }

    @Test
    void shouldProvisionDefaultIdentityManyUsersCase() throws Exception {
        createUser(BOB);
        createUser(ALICE);

        LscModifications bobModifications = new LscModifications(LscModificationType.CREATE_OBJECT);
        bobModifications.setMainIdentifer(BOB);
        LscDatasetModification bobFirstname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.ADD_VALUES, FIRSTNAME_KEY, ImmutableList.of("Bob"));
        LscDatasetModification bobSurname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.ADD_VALUES, SURNAME_KEY, ImmutableList.of("Surname"));
        bobModifications.setLscAttributeModifications(ImmutableList.of(bobFirstname, bobSurname));

        LscModifications aliceModifications = new LscModifications(LscModificationType.CREATE_OBJECT);
        aliceModifications.setMainIdentifer(ALICE);
        LscDatasetModification aliceFirstname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.ADD_VALUES, FIRSTNAME_KEY, ImmutableList.of("Alice"));
        LscDatasetModification aliceSurname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.ADD_VALUES, SURNAME_KEY, ImmutableList.of("Surname"));
        aliceModifications.setLscAttributeModifications(ImmutableList.of(aliceFirstname, aliceSurname));

        testee.apply(bobModifications);
        testee.apply(aliceModifications);

        given(requestSpecification)
            .get(String.format("/users/%s/identities?default=true", BOB))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("", hasSize(1))
            .body("[0].name", is("Bob Surname"))
            .body("[0].email", is(BOB))
            .body("[0].mayDelete", is(true))
            .body("[0].sortOrder", is(0));

        given(requestSpecification)
            .get(String.format("/users/%s/identities?default=true", ALICE))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("", hasSize(1))
            .body("[0].name", is("Alice Surname"))
            .body("[0].email", is(ALICE))
            .body("[0].mayDelete", is(true))
            .body("[0].sortOrder", is(0));
    }

    @Test
    void mixedCase() throws Exception {
        // GIVEN Bob has default identity, ALICE has no default identity
        createUser(BOB);
        createUserIdentity(BOB, "Bob John");
        createUser(ALICE);

        LscModifications bobModifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
        bobModifications.setMainIdentifer(BOB);
        LscDatasetModification bobFirstname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.ADD_VALUES, FIRSTNAME_KEY, ImmutableList.of("Bob"));
        LscDatasetModification bobSurname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.ADD_VALUES, SURNAME_KEY, ImmutableList.of("Surname"));
        bobModifications.setLscAttributeModifications(ImmutableList.of(bobFirstname, bobSurname));

        LscModifications aliceModifications = new LscModifications(LscModificationType.CREATE_OBJECT);
        aliceModifications.setMainIdentifer(ALICE);
        LscDatasetModification aliceFirstname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.ADD_VALUES, FIRSTNAME_KEY, ImmutableList.of("Alice"));
        LscDatasetModification aliceSurname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.ADD_VALUES, SURNAME_KEY, ImmutableList.of("Surname"));
        aliceModifications.setLscAttributeModifications(ImmutableList.of(aliceFirstname, aliceSurname));

        testee.apply(bobModifications);
        testee.apply(aliceModifications);

        // Then Alice default identity should be provisioned, while Bob default identity should not be changed
        given(requestSpecification)
            .get(String.format("/users/%s/identities?default=true", BOB))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("", hasSize(1))
            .body("[0].name", is("Bob John"))
            .body("[0].email", is(BOB));

        given(requestSpecification)
            .get(String.format("/users/%s/identities?default=true", ALICE))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("", hasSize(1))
            .body("[0].name", is("Alice Surname"))
            .body("[0].email", is(ALICE));
    }

    @Test
    void shouldPreProvisionDefaultIdentityEvenWhenUserDoesNotExistYet() throws Exception {
        // IMO this is an edge case, and we can accept pre-provision for that user, as well as solve the user existence with the user synchronization LSC job.

        LscModifications bobModifications = new LscModifications(LscModificationType.CREATE_OBJECT);
        bobModifications.setMainIdentifer(BOB);
        LscDatasetModification bobFirstname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.ADD_VALUES, FIRSTNAME_KEY, ImmutableList.of("Bob"));
        LscDatasetModification bobSurname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.ADD_VALUES, SURNAME_KEY, ImmutableList.of("Surname"));
        bobModifications.setLscAttributeModifications(ImmutableList.of(bobFirstname, bobSurname));

        testee.apply(bobModifications);

        given(requestSpecification)
            .get(String.format("/users/%s/identities?default=true", BOB))
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("", hasSize(1))
            .body("[0].name", is("Bob Surname"))
            .body("[0].email", is(BOB))
            .body("[0].mayDelete", is(true))
            .body("[0].sortOrder", is(0));
    }

}
