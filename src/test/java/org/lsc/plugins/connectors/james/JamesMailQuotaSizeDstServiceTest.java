package org.lsc.plugins.connectors.james;

import static io.restassured.RestAssured.requestSpecification;
import static io.restassured.RestAssured.with;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static org.assertj.core.api.Assertions.assertThat;
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
import org.lsc.plugins.connectors.james.generated.JamesMailQuotaSizeService;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.MountableFile;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

class JamesMailQuotaSizeDstServiceTest {
	private static final URL PRIVATE_KEY = ClassLoader.getSystemResource("conf/jwt_privatekey");
	private static final URL PUBLIC_KEY = ClassLoader.getSystemResource("conf/jwt_publickey");
	private static final String DOMAIN = "james.org";
	private static final String BOB = "bob@" + DOMAIN;
	private static final String ALICE = "alice@" + DOMAIN;
	private static final String MARIE = "marie@" + DOMAIN;
	private static final int JAMES_WEBADMIN_PORT = 8000;
	private static final boolean FROM_SAME_SERVICE = true;
	private static final long UNLIMITED_QUOTA_SIZE = -1;
	private static final String UNLIMITED_QUOTA_SIZE_AS_STRING = String.valueOf(UNLIMITED_QUOTA_SIZE);
	private static final long LIMITED_QUOTA_SIZE = 4000;
	private static final String LIMITED_QUOTA_SIZE_AS_STRING = String.valueOf(LIMITED_QUOTA_SIZE);
	private static final long NEW_LIMITED_QUOTA_SIZE = 5000;
	private static final String NEW_LIMITED_QUOTA_SIZE_AS_STRING = String.valueOf(NEW_LIMITED_QUOTA_SIZE);

	private static TaskType task;
	private static GenericContainer<?> james;

	private JamesMailQuotaSizeDstService testee;
	private static JamesDao jamesDao;

	@BeforeAll
	static void beforeAll() throws Exception {
		james = new GenericContainer<>("linagora/tmail-backend:memory-branch-master");
		james.withCopyFileToContainer(MountableFile.forClasspathResource("conf/jwt_publickey"), "/root/conf/");
		james.withCopyFileToContainer(MountableFile.forClasspathResource("conf/jwt_privatekey"), "/root/conf/");
		james.withCopyFileToContainer(MountableFile.forClasspathResource("conf/webadmin.properties"), "/root/conf/");
		james.withExposedPorts(JAMES_WEBADMIN_PORT).start();

		int MAPPED_JAMES_WEBADMIN_PORT = james.getMappedPort(JAMES_WEBADMIN_PORT);
		JamesMailQuotaSizeService jamesMailQuotaSizeService = mock(JamesMailQuotaSizeService.class);
		PluginDestinationServiceType pluginDestinationService = mock(PluginDestinationServiceType.class);
		PluginConnectionType jamesConnection = mock(PluginConnectionType.class);
		Connection connection = mock(Connection.class);
		task = mock(TaskType.class);

		when(jamesConnection.getUrl()).thenReturn("http://localhost:" + MAPPED_JAMES_WEBADMIN_PORT);
		when(jamesConnection.getPassword()).thenReturn(jwtToken());
		when(connection.getReference()).thenReturn(jamesConnection);
		when(jamesMailQuotaSizeService.getConnection()).thenReturn(connection);
		when(task.getBean()).thenReturn("org.lsc.beans.SimpleBean");
		when(task.getPluginDestinationService()).thenReturn(pluginDestinationService);
		when(pluginDestinationService.getAny()).thenReturn(ImmutableList.of(jamesMailQuotaSizeService));

		jamesDao = new JamesDao(jamesConnection.getUrl(), jamesConnection.getPassword(), task);

		requestSpecification = new RequestSpecBuilder().setPort(MAPPED_JAMES_WEBADMIN_PORT)
				.setContentType(ContentType.JSON).setAccept(ContentType.JSON)
				.setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
				.addHeader("Authorization", "Bearer " + jwtToken())
				.setBasePath("/quota/users").build();
		RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

		with().basePath("/domains").put(DOMAIN).then().statusCode(HttpStatus.SC_NO_CONTENT);
	}

	@BeforeEach
	void beforeEach() throws Exception {
		testee = new JamesMailQuotaSizeDstService(task);
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
	void cleanup() {
		jamesDao.getUserList()
			.forEach(user -> {
				jamesDao.deleteQuotaSize(user);
				jamesDao.removeUser(user);
			});
	}

	@AfterAll
	static void close() {
		james.close();
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
	void getBeanShouldReturnNullWhenUserDoesNotExistOnJames() throws Exception {
		// Bob exists on LDAP, but not on James
		LscDatasets dataset = new LscDatasets(ImmutableMap.of("email", BOB));

		assertThat(testee.getBean("email", dataset, FROM_SAME_SERVICE)).isNull();
	}

	@Test
	void getBeanShouldReturnNullWhenUserDoesNotHaveAnyQuotaSize() throws Exception {
		createUser(BOB);
		LscDatasets dataset = new LscDatasets(ImmutableMap.of("email", BOB));

		assertThat(testee.getBean("email", dataset, FROM_SAME_SERVICE)).isNull();
	}

	@Test
	void getBeanShouldReturnUserAsMainIdentifierWhenUserHasQuotaSize() throws Exception {
		createUser(BOB);
		setQuotaSize(BOB, LIMITED_QUOTA_SIZE);

		LscDatasets datasets = new LscDatasets(ImmutableMap.of("email", BOB));
		IBean bean = testee.getBean("email", datasets, FROM_SAME_SERVICE);

		assertThat(bean.getMainIdentifier()).isEqualTo(BOB);
	}

	@Test
	void getBeanShouldReflectLimitedQuotaSizeOfUser() throws Exception {
		// GIVEN Bob has quota size on James
		createUser(BOB);
		setQuotaSize(BOB, LIMITED_QUOTA_SIZE);

		LscDatasets datasets = new LscDatasets(ImmutableMap.of("email", BOB));
		IBean bean = testee.getBean("email", datasets, FROM_SAME_SERVICE);

		// LSC bean should reflect that quota size value
		assertThat(bean.getDatasetFirstValueById("email")).isEqualTo(BOB);
		assertThat(bean.getDatasetById("mailQuotaSize")).containsOnly("4000");
	}

	@Test
	void getBeanShouldReflectUnlimitedQuotaSizeOfUser() throws Exception {
		// GIVEN Bob has unlimited quota size on James
		createUser(BOB);
		setQuotaSize(BOB, UNLIMITED_QUOTA_SIZE);

		LscDatasets datasets = new LscDatasets(ImmutableMap.of("email", BOB));
		IBean bean = testee.getBean("email", datasets, FROM_SAME_SERVICE);

		// LSC bean should reflect that quota size value
		assertThat(bean.getDatasetFirstValueById("email")).isEqualTo(BOB);
		assertThat(bean.getDatasetById("mailQuotaSize")).containsOnly("-1");
	}

	@Test
	void createShouldNotCreateQuotaSizeWhenUserDoesNotExistInJames() throws Exception {
		String nonExistingUser = "nonExistingUser@james.org";

		LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
		modifications.setMainIdentifer(nonExistingUser);
		LscDatasetModification lscDatasetModification = new LscDatasetModification(
				LscDatasetModificationType.ADD_VALUES, "mailQuotaSize", ImmutableList.of(LIMITED_QUOTA_SIZE_AS_STRING));
		modifications.setLscAttributeModifications(ImmutableList.of(lscDatasetModification));

		boolean applied = testee.apply(modifications);

		assertThat(applied).isFalse();

		with()
			.get("/{user}/size", nonExistingUser)
		.then()
			.statusCode(HttpStatus.SC_NOT_FOUND);
	}

	@Test
	void createShouldCreateLimitedQuotaSizeWhenUserExistsInJamesAndLimitedQuotaCase() throws Exception {
		createUser(BOB);

		LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
		modifications.setMainIdentifer(BOB);
		LscDatasetModification modification = new LscDatasetModification(
			LscDatasetModificationType.ADD_VALUES, "mailQuotaSize", ImmutableList.of(LIMITED_QUOTA_SIZE_AS_STRING));
		modifications.setLscAttributeModifications(ImmutableList.of(modification));

		boolean applied = testee.apply(modifications);
		assertThat(applied).isTrue();
		assertUserHasQuotaSize(BOB, LIMITED_QUOTA_SIZE);
	}

	@Test
	void createShouldCreateUnlimitedQuotaSizeWhenUserExistsInJamesAndUnlimitedQuotaCase() throws Exception {
		createUser(BOB);

		LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
		modifications.setMainIdentifer(BOB);
		LscDatasetModification modification = new LscDatasetModification(
			LscDatasetModificationType.ADD_VALUES, "mailQuotaSize", ImmutableList.of(UNLIMITED_QUOTA_SIZE_AS_STRING));
		modifications.setLscAttributeModifications(ImmutableList.of(modification));

		boolean applied = testee.apply(modifications);
		assertThat(applied).isTrue();
		assertUserHasQuotaSize(BOB, UNLIMITED_QUOTA_SIZE);
	}

	@Test
	void createShouldFailWhenInvalidQuotaSizeOnLdap() throws Exception {
		createUser(BOB);

		LscModifications modifications = new LscModifications(LscModificationType.CREATE_OBJECT);
		modifications.setMainIdentifer(BOB);
		LscDatasetModification modification = new LscDatasetModification(
			LscDatasetModificationType.ADD_VALUES, "mailQuotaSize", ImmutableList.of("invalid_quota_size_from_ldap"));
		modifications.setLscAttributeModifications(ImmutableList.of(modification));

		boolean applied = testee.apply(modifications);
		assertThat(applied).isFalse();
		assertUserHasNoQuotaSize(BOB);
	}

	@Test
	void updateShouldSetTheNewQuotaSizeFromLDAPOntoJames() throws Exception {
		createUser(BOB);
		setQuotaSize(BOB, LIMITED_QUOTA_SIZE);

		LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
		modifications.setMainIdentifer(BOB);
		LscDatasetModification modification = new LscDatasetModification(
			LscDatasetModificationType.REPLACE_VALUES, "mailQuotaSize", ImmutableList.of(NEW_LIMITED_QUOTA_SIZE_AS_STRING));
		modifications.setLscAttributeModifications(ImmutableList.of(modification));

		boolean applied = testee.apply(modifications);
		assertThat(applied).isTrue();
		assertUserHasQuotaSize(BOB, NEW_LIMITED_QUOTA_SIZE);
	}

	@Test
	void updateWithNoQuotaSizeOnLdapShouldDeleteQuotaSizeOfTheUserOnJames() throws Exception {
		createUser(BOB);
		setQuotaSize(BOB, LIMITED_QUOTA_SIZE);

		LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
		modifications.setMainIdentifer(BOB);
		LscDatasetModification modification = new LscDatasetModification(
				LscDatasetModificationType.REPLACE_VALUES, "mailQuotaSize", ImmutableList.of());
		modifications.setLscAttributeModifications(ImmutableList.of(modification));

		boolean applied = testee.apply(modifications);
		assertThat(applied).isTrue();
		assertUserHasNoQuotaSize(BOB);
	}

	@Test
	void updateShouldFailAndDoNothingWhenInvalidQuotaSizeOnLdap() throws Exception {
		// Given Bob has already quota size on James
		createUser(BOB);
		setQuotaSize(BOB, LIMITED_QUOTA_SIZE);

		// LDAP has some new invalid quota size (could be admin mistake)
		LscModifications modifications = new LscModifications(LscModificationType.UPDATE_OBJECT);
		modifications.setMainIdentifer(BOB);
		LscDatasetModification modification = new LscDatasetModification(
			LscDatasetModificationType.REPLACE_VALUES, "mailQuotaSize", ImmutableList.of("invalid_quota_size_from_ldap"));
		modifications.setLscAttributeModifications(ImmutableList.of(modification));

		// Then LSC should not override the current quota size on James
		boolean applied = testee.apply(modifications);
		assertThat(applied).isFalse();
		assertUserHasQuotaSize(BOB, LIMITED_QUOTA_SIZE);
	}
	
	@Test
	void deleteOperationShouldDeleteQuotaSizeWhenUserDoesNotExistAnymoreOnLDAP() throws Exception {
		createUser(BOB);
		setQuotaSize(BOB, LIMITED_QUOTA_SIZE);

		LscModifications modifications = new LscModifications(LscModificationType.DELETE_OBJECT);
		modifications.setMainIdentifer(BOB);
	    modifications.setLscAttributeModifications(ImmutableList.of());

		boolean applied = testee.apply(modifications);

		assertThat(applied).isTrue();
		assertUserHasNoQuotaSize(BOB);
	}
	
	@Test
	void deleteShouldNotDeleteQuotaSizeOfOtherUsers() throws Exception {
		createUser(BOB);
		setQuotaSize(BOB, LIMITED_QUOTA_SIZE);
		createUser(MARIE);
		setQuotaSize(MARIE, LIMITED_QUOTA_SIZE);

		LscModifications modifications = new LscModifications(LscModificationType.DELETE_OBJECT);
		modifications.setMainIdentifer(BOB);
	    modifications.setLscAttributeModifications(ImmutableList.of());

		boolean applied = testee.apply(modifications);
		assertThat(applied).isTrue();
		assertUserHasNoQuotaSize(BOB);
		assertUserHasQuotaSize(MARIE, LIMITED_QUOTA_SIZE);
	}

	private static void createUser(String user) {
		jamesDao.addUser(new User(user), "password");
	}

	private void setQuotaSize(String user, long quotaSize) {
		with()
			.body(quotaSize)
			.put("/{user}/size", user)
		.then()
			.statusCode(HttpStatus.SC_NO_CONTENT);
	}

	private void assertUserHasQuotaSize(String username, long quotaSize) {
		with()
			.get("/{user}/size", username)
		.then()
			.statusCode(HttpStatus.SC_OK)
			.body(Matchers.is(String.valueOf(quotaSize)));
	}

	private void assertUserHasNoQuotaSize(String username) {
		with()
			.get("/{user}/size", username)
		.then()
			.statusCode(HttpStatus.SC_NO_CONTENT);
	}
}
