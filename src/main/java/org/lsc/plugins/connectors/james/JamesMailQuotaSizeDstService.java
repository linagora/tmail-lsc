package org.lsc.plugins.connectors.james;

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
import org.lsc.plugins.connectors.james.beans.QuotaSize;
import org.lsc.plugins.connectors.james.beans.User;
import org.lsc.plugins.connectors.james.generated.JamesMailQuotaSizeService;
import org.lsc.plugins.connectors.james.generated.JamesService;
import org.lsc.service.IWritableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

public class JamesMailQuotaSizeDstService implements IWritableService {
	private static final Logger LOGGER = LoggerFactory.getLogger(JamesMailQuotaSizeDstService.class);
	private static final String MAIL_QUOTA_SIZE_ATTRIBUTE = "mailQuotaSize";

	private final Class<IBean> beanClass;
	private final JamesService service;
	private final PluginConnectionType connection;
	private final JamesDao jamesDao;

	public JamesMailQuotaSizeDstService(final TaskType task) throws LscServiceConfigurationException {
		try {
	        if (task.getPluginDestinationService().getAny() == null
				|| task.getPluginDestinationService().getAny().size() != 1
				|| !(task.getPluginDestinationService().getAny().get(0) instanceof JamesMailQuotaSizeService)) {
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
			Optional<QuotaSize> maybeQuotaSize = jamesDao.getQuotaSize(email);
			return quotaSizeToBean(email, maybeQuotaSize);
		} catch (ProcessingException e) {
			LOGGER.error(String.format("ProcessingException while getting bean %s/%s (%s)", pivotName, email, e));
			LOGGER.error(e.toString(), e);
			throw new LscServiceCommunicationException(e);
		} catch (NotFoundException e) {
			LOGGER.debug("User {} does not exist. Need to create user first.", email);
			return null;
		} catch (WebApplicationException e) {
			LOGGER.error(String.format("WebApplicationException while getting bean %s/%s (%s)", pivotName, email, e));
			LOGGER.debug(e.toString(), e);
			throw new LscServiceException(e);
		}
	}

	@Override
	public Map<String, LscDatasets> getListPivots() throws LscServiceException {
		try {
			List<User> userList = jamesDao.getUserList();
			LOGGER.debug("Get quotaSize's listPivots via list users webadmin API. userList size = {}", userList.size());

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
					LOGGER.warn("Trying to change ID of James quotaSize, which is not a supported operation, ignored.");
					return false;
				case CREATE_OBJECT:
					LOGGER.debug("User {} has no quota size in James. Creating quota size from LDAP source for the first time.", user.email);
					try {
						Optional<QuotaSize> quotaSizeToCreate = quotaSizeFromSource(lm);
						return quotaSizeToCreate.map(quotaSize -> jamesDao.setQuotaSize(user, quotaSize))
							.orElse(false);
					} catch (NumberFormatException e) {
						LOGGER.warn("Invalid quota size value from LDAP", e);
						return false;
					}
				case UPDATE_OBJECT:
					try {
						Optional<QuotaSize> quotaSizeToUpdate = quotaSizeFromSource(lm);
						return quotaSizeToUpdate.map(quotaSize -> jamesDao.setQuotaSize(user, quotaSize))
							.orElseGet(() -> jamesDao.deleteQuotaSize(user));
					} catch (NumberFormatException e) {
						LOGGER.warn("Invalid quota size value from LDAP", e);
						return false;
					}
				case DELETE_OBJECT:
					LOGGER.debug("User {} exists on James but not in LDAP. Deleting James quota size for this user.", user.email);
					return jamesDao.deleteQuotaSize(user);
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

	private IBean quotaSizeToBean(String email, Optional<QuotaSize> quotaSizeOptional) {
		return quotaSizeOptional.map(quotaSize -> {
			try {
				IBean bean = beanClass.newInstance();
				bean.setMainIdentifier(email);
				bean.setDatasets(toDataset(email, quotaSize));
				return bean;
			} catch (InstantiationException | IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		}).orElse(null);

	}

	private LscDatasets toDataset(String email, QuotaSize quotaSize) {
		LscDatasets datasets = new LscDatasets();
		datasets.put("email", email);
		datasets.put(MAIL_QUOTA_SIZE_ATTRIBUTE, String.valueOf(quotaSize.size));
		return datasets;
	}

	private Optional<QuotaSize> quotaSizeFromSource(LscModifications lm) {
		return Optional.ofNullable(lm.getModificationsItemsByHash()
				.get(MAIL_QUOTA_SIZE_ATTRIBUTE))
			.flatMap(quotaSize -> quotaSize
				.stream()
				.findFirst()
				.map(quotaSizeAsString -> new QuotaSize(Long.parseLong((String) quotaSizeAsString))));
	}

}
