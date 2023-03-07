package org.lsc.plugins.connectors.james;

import static org.lsc.plugins.connectors.james.TMailContactDstService.FIRSTNAME_KEY;
import static org.lsc.plugins.connectors.james.TMailContactDstService.SURNAME_KEY;
import static org.lsc.plugins.connectors.james.beans.Identity.DEFAULT_IDENTITY_SORT_ORDER;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import org.lsc.plugins.connectors.james.beans.Identity;
import org.lsc.plugins.connectors.james.beans.User;
import org.lsc.plugins.connectors.james.generated.JamesIdentityService;
import org.lsc.plugins.connectors.james.generated.JamesService;
import org.lsc.service.IWritableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableMap;

public class JamesIdentityDstService implements IWritableService {
	private static final Logger LOGGER = LoggerFactory.getLogger(JamesIdentityDstService.class);

	private final Class<IBean> beanClass;
	private final JamesService service;
	private final PluginConnectionType connection;
	private final JamesDao jamesDao;

	public JamesIdentityDstService(final TaskType task) throws LscServiceConfigurationException {
		try {
	        if (task.getPluginDestinationService().getAny() == null
				|| task.getPluginDestinationService().getAny().size() != 1
				|| !(task.getPluginDestinationService().getAny().get(0) instanceof JamesIdentityService)) {
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
			Identity defaultIdentity = jamesDao.getDefaultIdentity(email);
			return identityToBean(email, defaultIdentity);
		} catch (ProcessingException e) {
			LOGGER.error(String.format("ProcessingException while getting bean %s/%s (%s)", pivotName, email, e));
			LOGGER.error(e.toString(), e);
			throw new LscServiceCommunicationException(e);
		} catch (NotFoundException e) {
			LOGGER.debug("User {} does not have a default identity yet.", email);
			return null;
		} catch (IOException e) {
			LOGGER.error(String.format("IOException while getting bean %s/%s (%s)",
				pivotName, email, e));
			LOGGER.debug(e.toString(), e);
			throw new LscServiceException(e);
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
			LOGGER.debug("Get identity's listPivots via list users webadmin API. userList size = {}", userList.size());
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
				LOGGER.warn("Trying to change ID of users identity, which is not a supported operation, ignored.");
				return true;
			case CREATE_OBJECT:
				LOGGER.debug("Creating default identity for user {}", lm.getMainIdentifier());
				return jamesDao.createDefaultIdentity(extractIdentityFromLDAPBean(lm));
			case UPDATE_OBJECT:
				LOGGER.warn("Trying to update users identity, which is not a supported operation, ignored.");
				return true;
			case DELETE_OBJECT:
				LOGGER.warn("Trying to delete users identity, which is not a supported operation, ignored.");
				return true;
			default:
				LOGGER.error("Unknown operation {}", lm.getOperation());
				return false;
			}
		} catch (NotFoundException e) {
			LOGGER.error(String.format("NotFoundException while writing (%s)", e));
			LOGGER.debug(e.toString(), e);
			return false;
		} catch (ProcessingException | JsonProcessingException e) {
			LOGGER.error(String.format("ProcessingException while writing (%s)", e));
			LOGGER.debug(e.toString(), e);
			return false;
		}
	}

	@Override
	public List<String> getWriteDatasetIds() {
		return service.getWritableAttributes().getString();
	}

	private IBean identityToBean(String email, Identity identity) throws InstantiationException, IllegalAccessException {
		IBean bean = beanClass.newInstance();
		bean.setMainIdentifier(email);
		bean.setDatasets(toDataset(email, identity));
		return bean;
	}

	private LscDatasets toDataset(String email, Identity identity) {
		LscDatasets datasets = new LscDatasets();
		datasets.put("email", email);
		return datasets;
	}

	private Identity extractIdentityFromLDAPBean(LscModifications lscModifications) {
		return new Identity(lscModifications.getMainIdentifier(),
			Identity.toDisplayName(extractFirstnameFromLDAPBean(lscModifications), extractSurnameFromLDAPBean(lscModifications), lscModifications.getMainIdentifier()),
			DEFAULT_IDENTITY_SORT_ORDER);
	}

	private Optional<String> extractFirstnameFromLDAPBean(LscModifications lscModifications) {
		return Optional.ofNullable(lscModifications.getModificationsItemsByHash().get(FIRSTNAME_KEY))
			.filter(list -> !list.isEmpty())
			.map(list -> (String) list.get(0));
	}

	private Optional<String> extractSurnameFromLDAPBean(LscModifications lscModifications) {
		return Optional.ofNullable(lscModifications.getModificationsItemsByHash().get(SURNAME_KEY))
			.filter(list -> !list.isEmpty())
			.map(list -> (String) list.get(0));
	}
}
