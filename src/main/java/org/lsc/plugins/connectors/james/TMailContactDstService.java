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
import org.lsc.plugins.connectors.james.beans.Contact;
import org.lsc.plugins.connectors.james.beans.ContactDTO;
import org.lsc.plugins.connectors.james.beans.User;
import org.lsc.plugins.connectors.james.generated.JamesService;
import org.lsc.plugins.connectors.james.generated.TMailContactService;
import org.lsc.service.IWritableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TMailContactDstService implements IWritableService {
    public static final String EMAIL_KEY = "email";
    public static final String FIRSTNAME_KEY = "firstname";
    public static final String SURNAME_KEY = "surname";
    private static final Logger LOGGER = LoggerFactory.getLogger(TMailContactDstService.class);

    private final JamesDao jamesDao;
    private final JamesService service;
    private final Class<IBean> beanClass;

    public TMailContactDstService(final TaskType task) throws LscServiceConfigurationException {
        try {
            if (task.getPluginDestinationService().getAny() == null
                || task.getPluginDestinationService().getAny().size() != 1
                || !((task.getPluginDestinationService().getAny().get(0) instanceof TMailContactService))) {
                throw new LscServiceConfigurationException("Unable to identify the James service configuration inside the plugin source node of the task: " + task.getName());
            } else {
                this.service = (JamesService) task.getPluginDestinationService().getAny().get(0);
                this.beanClass = (Class<IBean>) Class.forName(task.getBean());
                LOGGER.debug("Task bean is: " + task.getBean());
                PluginConnectionType connection = (PluginConnectionType) service.getConnection().getReference();
                this.jamesDao = new JamesDao(connection.getUrl(), connection.getPassword(), task);
            }
        } catch (ClassNotFoundException e) {
            throw new LscServiceConfigurationException(e);
        }
    }

    @Override
    public boolean apply(LscModifications lscModifications) throws LscServiceException {
        if (lscModifications.getMainIdentifier() == null) {
            LOGGER.error("MainIdentifier is needed to update");
            return false;
        }
        User user = new User(lscModifications.getMainIdentifier());
        LOGGER.debug("User: {}, Operation: {}", user.email, lscModifications.getOperation());

        try {
            switch (lscModifications.getOperation()) {
                case CREATE_OBJECT:
                    return jamesDao.addDomainContact(extractContact(lscModifications));
                case UPDATE_OBJECT:
                    return jamesDao.updateDomainContact(extractContact(lscModifications));
                case DELETE_OBJECT:
                    return jamesDao.removeDomainContact(extractContact(lscModifications).getEmail());
                default:
                    LOGGER.debug("{} operation, ignored.", lscModifications.getOperation());
                    return true;
            }
        } catch (ProcessingException exception) {
            LOGGER.error(String.format("ProcessingException while writing (%s)", exception));
            LOGGER.debug(exception.toString(), exception);
            return false;
        }
    }

    @Override
    public List<String> getWriteDatasetIds() {
        return service.getWritableAttributes().getString();
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
            ContactDTO contactDTO = jamesDao.getContactDTO(email);
            return contactDTOToBean(contactDTO);
        } catch (ProcessingException e) {
            LOGGER.error(String.format("ProcessingException while getting bean %s/%s (%s)",
                pivotName, email, e));
            LOGGER.error(e.toString(), e);
            throw new LscServiceCommunicationException(e);
        } catch (NotFoundException e) {
            LOGGER.debug(String.format("%s/%s not found", pivotName, email));
            return null;
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
            List<User> userList = jamesDao.getUsersListViaDomainContacts();
            Map<String, LscDatasets> listPivots = new HashMap<>();
            for (User user : userList) {
                listPivots.put(user.email, user.toDatasets());
            }
            return listPivots;
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

    private Contact extractContact(LscModifications lscModifications) {
        return new Contact(lscModifications.getMainIdentifier(), extractFirstname(lscModifications), extractSurname(lscModifications));
    }

    private Optional<String> extractFirstname(LscModifications lscModifications) {
        return Optional.ofNullable(lscModifications.getModificationsItemsByHash().get(FIRSTNAME_KEY).get(0)).map(String.class::cast);
    }

    private Optional<String> extractSurname(LscModifications lscModifications) {
        return Optional.ofNullable(lscModifications.getModificationsItemsByHash().get(SURNAME_KEY).get(0)).map(String.class::cast);
    }

    private IBean contactDTOToBean(ContactDTO contactDTO) throws InstantiationException, IllegalAccessException {
        IBean bean = beanClass.newInstance();
        bean.setMainIdentifier(contactDTO.getEmailAddress());
        bean.setDatasets(toDataset(contactDTO));
        return bean;
    }

    private LscDatasets toDataset(ContactDTO contactDTO) {
        LscDatasets datasets = new LscDatasets();
        datasets.put(EMAIL_KEY, contactDTO.getEmailAddress());
        datasets.put(FIRSTNAME_KEY, contactDTO.getFirstname());
        datasets.put(SURNAME_KEY, contactDTO.getSurname());
        return datasets;
    }
}
