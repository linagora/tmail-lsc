package org.lsc.plugins.connectors.james;

import static io.restassured.RestAssured.with;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.hasSize;
import static org.lsc.plugins.connectors.james.TMailContactDstService.EMAIL_KEY;
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
import java.util.Optional;

import org.apache.http.HttpStatus;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.lsc.plugins.connectors.james.beans.Contact;
import org.lsc.plugins.connectors.james.generated.TMailContactService;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

class TMailContactDstServiceTest {
    public static final String GET_ALL_CONTACT_PATH = "/domains/contacts/all";
    public static final String DOMAIN = "james.org";
    private static final URL PRIVATE_KEY = ClassLoader.getSystemResource("conf/jwt_privatekey");
    private static final URL PUBLIC_KEY = ClassLoader.getSystemResource("conf/jwt_publickey");
    private static final int JAMES_WEBADMIN_PORT = 8000;
    private static final boolean FROM_SAME_SERVICE = true;
    private static final Contact CONTACT_RENE = new Contact("renecordier@james.org", Optional.of("Rene"), Optional.of("Cordier"));
    private static final Contact CONTACT_RENE_WITHOUT_NAMES = new Contact("renecordier@james.org", Optional.empty(), Optional.empty());
    private static final Contact CONTACT_TUNG = new Contact("tungtranvan@james.org", Optional.of("Tung"), Optional.of("Tran Van"));
    private static final Contact CONTACT_WITHOUT_NAMES = new Contact("nonnames@james.org", Optional.empty(), Optional.empty());
    private static final Contact CONTACT_WITH_NAMES = new Contact("nonnames@james.org", Optional.of("Firstname"), Optional.of("Surname"));

    private static GenericContainer<?> james;
    private static TMailContactDstService testee;
    private static JamesDao jamesDao;

    @BeforeAll
    static void setup() throws Exception {
        james = new GenericContainer<>("linagora/tmail-backend:memory-branch-master");
        String webadmin = ClassLoader.getSystemResource("conf/webadmin.properties").getFile();
        james.withExposedPorts(JAMES_WEBADMIN_PORT)
            .withFileSystemBind(PUBLIC_KEY.getFile(), "/root/conf/jwt_publickey", BindMode.READ_ONLY)
            .withFileSystemBind(PRIVATE_KEY.getFile(), "/root/conf/jwt_privatekey", BindMode.READ_ONLY)
            .withFileSystemBind(webadmin, "/root/conf/webadmin.properties", BindMode.READ_ONLY)
            .start();

        int MAPPED_JAMES_WEBADMIN_PORT = james.getMappedPort(JAMES_WEBADMIN_PORT);

        TMailContactService tmailContactService = mock(TMailContactService.class);
        PluginDestinationServiceType pluginDestinationService = mock(PluginDestinationServiceType.class);
        PluginConnectionType jamesConnection = mock(PluginConnectionType.class);
        Connection connection = mock(Connection.class);
        TaskType task = mock(TaskType.class);

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
        jamesDao = new JamesDao("http://localhost:" + MAPPED_JAMES_WEBADMIN_PORT, jwtToken(), task);
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
        try (PEMReader pemReader = new PEMReader(new FileReader(PRIVATE_KEY.getFile()), "james"::toCharArray)) {
            Object readObject = pemReader.readObject();
            return (RSAPrivateKey) ((java.security.KeyPair) readObject).getPrivate();
        }
    }

    @AfterAll
    static void close() {
        james.close();
    }

    @AfterEach
    void removeAllContact() {
        jamesDao.getUsersListViaDomainContacts()
                .forEach(user -> jamesDao.removeDomainContact(user.email));
    }

    @Test
    void getAllContactShouldReturnEmptyByDefault() {
        with()
            .get(GET_ALL_CONTACT_PATH)
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("", hasSize(0));
    }

    @Test
    void getListPivotsShouldReturnEmptyWhenNoContact() throws Exception {
        Map<String, LscDatasets> listPivots = testee.getListPivots();

        assertThat(listPivots).isEmpty();
    }

    @Test
    void getListPivotsShouldReturnOneWhenOneContact() throws Exception {
        createContact(CONTACT_RENE);

        Map<String, LscDatasets> listPivots = testee.getListPivots();

        assertSoftly(softly -> {
            softly.assertThat(listPivots).containsOnlyKeys(CONTACT_RENE.getEmailAddress());
            softly.assertThat(listPivots.get(CONTACT_RENE.getEmailAddress()).getStringValueAttribute("email")).isEqualTo(CONTACT_RENE.getEmailAddress());
        });
    }

    @Test
    void getListPivotsShouldReturnTwoWhenTwoContacts() throws Exception {
        createContact(CONTACT_RENE);
        createContact(CONTACT_TUNG);

        Map<String, LscDatasets> listPivots = testee.getListPivots();

        assertSoftly(softly -> {
            softly.assertThat(listPivots).hasSize(2);
            softly.assertThat(listPivots).containsOnlyKeys(CONTACT_RENE.getEmailAddress(), CONTACT_TUNG.getEmailAddress());
        });
    }

    @Test
    void createShouldFailWhenMainIdentifierIsMissing() throws Exception {
        LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
        modifications.setLscAttributeModifications(ImmutableList.of());

        boolean applied = testee.apply(modifications);

        assertThat(applied).isFalse();
    }

    @Test
    void createContactShouldSucceedWhenMissingContactNames() throws Exception {
        LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
        modifications.setMainIdentifer(CONTACT_WITHOUT_NAMES.getEmailAddress());
        modifications.setLscAttributeModifications(ImmutableList.of());

        boolean applied = testee.apply(modifications);

        assertThat(applied).isTrue();
        assertThat(jamesDao.getContact(CONTACT_WITHOUT_NAMES.getEmailAddress())).isEqualTo(CONTACT_WITHOUT_NAMES);
    }

    @Test
    void createContactShouldSucceedWhenHavingContactNames() throws Exception {
        LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
        modifications.setMainIdentifer(CONTACT_RENE.getEmailAddress());
        LscDatasetModification firstname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.ADD_VALUES, FIRSTNAME_KEY, ImmutableList.of(CONTACT_RENE.getFirstname().get()));
        LscDatasetModification surname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.ADD_VALUES, SURNAME_KEY, ImmutableList.of(CONTACT_RENE.getSurname().get()));
        modifications.setLscAttributeModifications(ImmutableList.of(firstname, surname));

        boolean applied = testee.apply(modifications);

        assertThat(applied).isTrue();
        assertThat(jamesDao.getContact(CONTACT_RENE.getEmailAddress())).isEqualTo(CONTACT_RENE);
    }

    @Test
    void updateWithMissingContactModificationShouldFail() throws Exception {
        createContact(CONTACT_RENE);

        LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
        modifications.setMainIdentifer(CONTACT_RENE.getEmailAddress());
        modifications.setLscAttributeModifications(ImmutableList.of());

        boolean applied = testee.apply(modifications);

        assertThat(applied).isFalse();
        assertThat(jamesDao.getContact(CONTACT_RENE.getEmailAddress())).isEqualTo(CONTACT_RENE);
    }

    @Test
    void updateWithEmptyNamesShouldClearNamesOfThatContact() throws Exception {
        createContact(CONTACT_RENE);

        LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
        modifications.setMainIdentifer(CONTACT_RENE.getEmailAddress());
        LscDatasetModification emptyFirstname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.REPLACE_VALUES, FIRSTNAME_KEY, ImmutableList.of(""));
        LscDatasetModification emptySurname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.REPLACE_VALUES, SURNAME_KEY, ImmutableList.of(""));
        modifications.setLscAttributeModifications(ImmutableList.of(emptyFirstname, emptySurname));

        boolean applied = testee.apply(modifications);

        assertThat(applied).isTrue();
        assertThat(jamesDao.getContact(CONTACT_RENE.getEmailAddress())).isEqualTo(CONTACT_RENE_WITHOUT_NAMES);
    }

    @Test
    void updateWithOtherNamesShouldSucceed() throws Exception {
        createContact(CONTACT_WITHOUT_NAMES);

        LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
        modifications.setMainIdentifer(CONTACT_WITHOUT_NAMES.getEmailAddress());
        LscDatasetModification firstname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.REPLACE_VALUES, FIRSTNAME_KEY, ImmutableList.of("Firstname"));
        LscDatasetModification surname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.REPLACE_VALUES, SURNAME_KEY, ImmutableList.of("Surname"));
        modifications.setLscAttributeModifications(ImmutableList.of(firstname, surname));

        boolean applied = testee.apply(modifications);

        assertThat(applied).isTrue();
        assertThat(jamesDao.getContact(CONTACT_WITHOUT_NAMES.getEmailAddress())).isEqualTo(CONTACT_WITH_NAMES);
    }

    @Test
    void shouldSupportPartialUpdate() throws Exception {
        createContact(CONTACT_RENE);

        LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
        modifications.setMainIdentifer(CONTACT_RENE.getEmailAddress());
        LscDatasetModification surname = new LscDatasetModification(LscDatasetModification.LscDatasetModificationType.REPLACE_VALUES, SURNAME_KEY, ImmutableList.of("Surname"));
        modifications.setLscAttributeModifications(ImmutableList.of(surname));

        boolean applied = testee.apply(modifications);

        assertThat(applied).isTrue();
        assertThat(jamesDao.getContact(CONTACT_RENE.getEmailAddress())).isEqualTo(new Contact(CONTACT_RENE.getEmailAddress(),
            CONTACT_RENE.getFirstname(), Optional.of("Surname")));
    }

    @Test
    void getBeanShouldReturnNullWhenEmptyDataset() throws Exception {
        assertThat(testee.getBean("email", new LscDatasets(), FROM_SAME_SERVICE)).isNull();
    }

    @Test
    void getBeanShouldReturnNullWhenNoMatchingContact() throws Exception {
        LscDatasets nonExistingIdDataset = new LscDatasets(ImmutableMap.of("email", "nonExistingEmail@james.org"));

        assertThat(testee.getBean("email", nonExistingIdDataset, FROM_SAME_SERVICE)).isNull();
    }

    @Test
    void getBeanShouldNotReturnNullWhenContactExists() throws Exception {
        createContact(CONTACT_RENE);

        LscDatasets datasets = new LscDatasets(ImmutableMap.of("email", CONTACT_RENE.getEmailAddress()));
        assertThat(testee.getBean("email", datasets, FROM_SAME_SERVICE)).isNotNull();
    }

    @Test
    void getBeanShouldReturnContactWithNamesWhenContactExistsAndHaveNames() throws Exception {
        createContact(CONTACT_RENE);

        LscDatasets datasets = new LscDatasets(ImmutableMap.of("email", CONTACT_RENE.getEmailAddress()));
        IBean bean = testee.getBean("email", datasets, FROM_SAME_SERVICE);

        assertThat(bean.getMainIdentifier()).isEqualTo(CONTACT_RENE.getEmailAddress());
        assertThat(bean.getDatasetFirstValueById(EMAIL_KEY)).isEqualTo(CONTACT_RENE.getEmailAddress());
        assertThat(bean.getDatasetFirstValueById(FIRSTNAME_KEY)).isEqualTo(CONTACT_RENE.getFirstname().get());
        assertThat(bean.getDatasetFirstValueById(SURNAME_KEY)).isEqualTo(CONTACT_RENE.getSurname().get());
    }

    @Test
    void getBeanShouldReturnContactWithoutNamesWhenContactExistsAndHaveNoNames() throws Exception {
        createContact(CONTACT_WITHOUT_NAMES);

        LscDatasets datasets = new LscDatasets(ImmutableMap.of("email", CONTACT_WITHOUT_NAMES.getEmailAddress()));
        IBean bean = testee.getBean("email", datasets, FROM_SAME_SERVICE);

        assertThat(bean.getMainIdentifier()).isEqualTo(CONTACT_WITHOUT_NAMES.getEmailAddress());
        assertThat(bean.getDatasetFirstValueById(EMAIL_KEY)).isEqualTo(CONTACT_WITHOUT_NAMES.getEmailAddress());
        assertThat(bean.getDatasetFirstValueById(FIRSTNAME_KEY)).isEmpty();
        assertThat(bean.getDatasetFirstValueById(SURNAME_KEY)).isEmpty();
    }

    private void createContact(Contact contact) throws JsonProcessingException {
        boolean isCreatedSucceed = jamesDao.addDomainContact(contact);
        assertThat(isCreatedSucceed).isTrue();
    }
}
