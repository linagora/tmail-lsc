package org.lsc.plugins.connectors.james;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;

import org.lsc.LscDatasets;
import org.lsc.LscModifications;
import org.lsc.beans.IBean;
import org.lsc.configuration.PluginConnectionType;
import org.lsc.configuration.TaskType;
import org.lsc.exception.LscServiceCommunicationException;
import org.lsc.exception.LscServiceConfigurationException;
import org.lsc.exception.LscServiceException;
import org.lsc.plugins.connectors.james.beans.AddressMapping;
import org.lsc.plugins.connectors.james.beans.User;
import org.lsc.plugins.connectors.james.generated.JamesAddressMappingService;
import org.lsc.plugins.connectors.james.generated.JamesService;
import org.lsc.service.IWritableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

public class JamesAddressMappingDstService implements IWritableService {
	private static final Logger LOGGER = LoggerFactory.getLogger(JamesAddressMappingDstService.class);
	private static final String ADDRESS_MAPPING_ATTRIBUTE = "addressMappings";

	private final Class<IBean> beanClass;
	private final JamesService service;
	private final PluginConnectionType connection;
	private final JamesDao jamesDao;

	public JamesAddressMappingDstService(final TaskType task) throws LscServiceConfigurationException {
		try {
	        if (task.getPluginDestinationService().getAny() == null
				|| task.getPluginDestinationService().getAny().size() != 1
				|| !(task.getPluginDestinationService().getAny().get(0) instanceof JamesAddressMappingService)) {
	            throw new LscServiceConfigurationException("Unable to identify the James service configuration inside the plugin source node of the task: " + task.getName());
	        }
        	service = (JamesService) task.getPluginDestinationService().getAny().get(0);
			beanClass = (Class<IBean>) Class.forName(task.getBean());
			LOGGER.debug("Task bean is: " + task.getBean());
			connection = (PluginConnectionType) service.getConnection().getReference();
			jamesDao = new JamesDao(connection.getUrl(), connection.getPassword(), task);
		} catch (ClassNotFoundException e) {
			throw new LscServiceConfigurationException(e);
		}
	}
	
	@Override
	public IBean getBean(String pivotName, LscDatasets pivotAttributes, boolean fromSameService) throws LscServiceException {
		LOGGER.debug(String.format("Call to getBean(%s, %s, %b)", pivotName, pivotAttributes, fromSameService));
		if (pivotAttributes.getAttributesNames().isEmpty()) {
			return null;
		}
		String pivotAttribute = pivotAttributes.getAttributesNames().get(0);
		String email = pivotAttributes.getStringValueAttribute(pivotAttribute);
		if (email == null) {
			return null;
		}
		try {
			if (jamesDao.userExists(email)) {
				List<AddressMapping> addressMappings = jamesDao.getAddressMappings(email);
				return addressMappingsToBean(email, addressMappings);
			}
			return null;
		} catch (ProcessingException e) {
			LOGGER.error(String.format("ProcessingException while getting bean %s/%s (%s)", pivotName, email, e));
			LOGGER.error(e.toString(), e);
			throw new LscServiceCommunicationException(e);
		} catch (WebApplicationException e) {
			LOGGER.error(String.format("WebApplicationException while getting bean %s/%s (%s)",
					pivotName, email, e));
			LOGGER.debug(e.toString(), e);
			throw new LscServiceException(e);
		} catch (InstantiationException | IllegalAccessException e) {
			LOGGER.error("Bad class name: " + beanClass.getName() + "(" + e + ")");
			LOGGER.debug(e.toString(), e);
			throw new LscServiceException(e);
		}

	}

	@Override
	public Map<String, LscDatasets> getListPivots() throws LscServiceException {
		try {
			List<User> userList = jamesDao.getUserList();
			LOGGER.debug("Get address mapping's listPivots via list users webadmin API. userList size = {}", userList.size());

			Map<String, LscDatasets> listPivots = new HashMap<String, LscDatasets>();
			for (User user: userList) {
				listPivots.put(user.email, user.toDatasets());
			}
			return ImmutableMap.copyOf(listPivots);
		} catch (ProcessingException e) {
			LOGGER.error(String.format("ProcessingException while getting pivot list (%s)", e));
			LOGGER.debug(e.toString(), e);
			throw new LscServiceCommunicationException(e);
		} catch (WebApplicationException e) {
			LOGGER.error(String.format("WebApplicationException while getting pivot list (%s)", e));
			LOGGER.debug(e.toString(), e);
			throw new LscServiceException(e);
		}
	}
	
	@Override
	public boolean apply(LscModifications lm) throws LscServiceException {
		if (lm.getMainIdentifier() == null) {
			LOGGER.error("MainIdentifier is needed to update");
			return false;
		}
		User user = new User(lm.getMainIdentifier());
		LOGGER.debug("User: {}, Operation: {}", user.email, lm.getOperation());
		try {
			switch(lm.getOperation()) {
				case CHANGE_ID:
					LOGGER.warn("Trying to change ID of James address mappings, which is not a supported operation, ignored.");
					return true;
				case CREATE_OBJECT:
					LOGGER.debug("User {} does not exist yet in James. Please create this user before provisioning his address mappings.", user.email);
					return true;
				case UPDATE_OBJECT:
					Optional<List<AddressMapping>> ldapAddressMappings = addressMappingsFromLDAP(lm);

					return ldapAddressMappings.map(ldapMappings -> {
						LOGGER.debug("Updating James address mappings of user {}", user.email);
						return jamesDao.updateAddressMappings(user, ldapMappings);
					}).orElse(false);
				case DELETE_OBJECT:
					LOGGER.debug("User {} exists on James but not in LDAP. Deleting James address mappings of this user.", user.email);
					return jamesDao.removeAddressMappings(user);
				default:
					LOGGER.error("Unknown operation {}", lm.getOperation());
					return false;
			}
		} catch (NotFoundException e) {
			LOGGER.error(String.format("NotFoundException while writing (%s)", e));
			LOGGER.debug(e.toString(), e);
			return false;
		} catch (ProcessingException e) {
			LOGGER.error(String.format("ProcessingException while writing (%s)", e));
			LOGGER.debug(e.toString(), e);
			return false;
		}
	}

	@Override
	public List<String> getWriteDatasetIds() {
		return service.getWritableAttributes().getString();
	}

	private IBean addressMappingsToBean(String email, List<AddressMapping> addressMappings) throws InstantiationException, IllegalAccessException {
		IBean bean = beanClass.newInstance();
		bean.setMainIdentifier(email);
		bean.setDatasets(toDataset(email, addressMappings));
		return bean;
	}

	private LscDatasets toDataset(String email, List<AddressMapping> addressMappings) {
		LscDatasets datasets = new LscDatasets();
		datasets.put("email", email);
		List<String> addressMappingsAsStringList = addressMappings.stream()
			.map(AddressMapping::getMapping)
			.collect(Collectors.toList());
		datasets.put(ADDRESS_MAPPING_ATTRIBUTE, addressMappingsAsStringList);
		return datasets;
	}

	private Optional<List<AddressMapping>> addressMappingsFromLDAP(LscModifications lm) {
		return Optional.ofNullable(lm.getModificationsItemsByHash()
				.get(ADDRESS_MAPPING_ATTRIBUTE))
			.map(addressMappings -> addressMappings.stream()
				.map(addressMapping -> new AddressMapping(((String) addressMapping)))
				.collect(Collectors.toList()));
	}

}
