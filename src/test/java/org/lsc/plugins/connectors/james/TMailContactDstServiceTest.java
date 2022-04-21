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
import org.lsc.plugins.connectors.james.generated.TMailContactService;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

public class TMailContactDstServiceTest {
    public static final String ADD_CONTACT_PATH = "/domains/%s/contacts";
    public static final String GET_ALL_CONTACT_PATH = "/domains/contacts";
    public static final String GET_CONTACT_PATH = "/domains/%s/contacts/%s";
    public static final String UPDATE_CONTACT_PATH = "/domains/%s/contacts/%";
    public static final String DELETE_CONTACT_PATH = "/domains/%s/contacts/%";
    public static final String DOMAIN = "james.org";
    public static final String USER = "bob@" + DOMAIN;
    private static final URL PRIVATE_KEY = ClassLoader.getSystemResource("conf/jwt_privatekey");
    private static final URL PUBLIC_KEY = ClassLoader.getSystemResource("conf/jwt_publickey");
    private static final int JAMES_WEBADMIN_PORT = 8000;
    private static final boolean FROM_SAME_SERVICE = true;

    private static GenericContainer<?> james;
    private static TaskType task;
    private static int MAPPED_JAMES_WEBADMIN_PORT;
    private static TMailContactDstService testee;

    @BeforeAll
    static void setup() throws Exception {
        james = new GenericContainer<>("linagora/tmail-backend:memory-branch-master");
        String webadmin = ClassLoader.getSystemResource("conf/webadmin.properties").getFile();
        james.withExposedPorts(JAMES_WEBADMIN_PORT)
            .withFileSystemBind(PUBLIC_KEY.getFile(), "/root/conf/jwt_publickey", BindMode.READ_ONLY)
            .withFileSystemBind(PRIVATE_KEY.getFile(), "/root/conf/jwt_privatekey", BindMode.READ_ONLY)
            .withFileSystemBind(webadmin, "/root/conf/webadmin.properties", BindMode.READ_ONLY)
            .start();

        MAPPED_JAMES_WEBADMIN_PORT = james.getMappedPort(JAMES_WEBADMIN_PORT);

        TMailContactService tmailContactService = mock(TMailContactService.class);
        PluginDestinationServiceType pluginDestinationService = mock(PluginDestinationServiceType.class);
        PluginConnectionType jamesConnection = mock(PluginConnectionType.class);
        Connection connection = mock(Connection.class);
        task = mock(TaskType.class);

        when(jamesConnection.getUrl()).thenReturn("http://localhost:" + MAPPED_JAMES_WEBADMIN_PORT);
        when(jamesConnection.getPassword()).thenReturn(jwtToken());
        when(connection.getReference()).thenReturn(jamesConnection);
        when(tmailContactService.getConnection()).thenReturn(connection);
        when(task.getBean()).thenReturn("org.lsc.beans.SimpleBean");
        when(task.getPluginDestinationService()).thenReturn(pluginDestinationService);
        when(pluginDestinationService.getAny()).thenReturn(ImmutableList.of(tmailContactService));
        testee = new TMailContactDstService(task);

        RestAssured.requestSpecification = new RequestSpecBuilder().setPort(MAPPED_JAMES_WEBADMIN_PORT)
            .setContentType(ContentType.JSON).setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .addHeader("Authorization", "Bearer " + jwtToken())
            .build();
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

        with().basePath("/domains").put(DOMAIN).then().statusCode(HttpStatus.SC_NO_CONTENT);
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
            return (org.bouncycastle.jce.provider.JCERSAPublicKey) readObject;
        }
    }

    private static RSAPrivateKey getPrivateKey() throws Exception {
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
    void removeAllContact() throws Exception {
        JamesDao jamesDao = new JamesDao("http://localhost:" + MAPPED_JAMES_WEBADMIN_PORT, jwtToken(), task);
        jamesDao.getUsersListViaDomainContacts()
                .forEach(user -> jamesDao.removeDomainContact(user.email));
    }

    // TODO rewrite tests for contact case

    @Test
    void jamesUserListShouldReturnEmptyWhenNoUser() {
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
