/*
 ****************************************************************************
 * Ldap Synchronization Connector provides tools to synchronize
 * electronic identities from a list of data sources including
 * any database with a JDBC connector, another LDAP directory,
 * flat files...
 *
 *                  ==LICENSE NOTICE==
 *
 * Copyright (c) 2008 - 2019 LSC Project
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:

 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *     * Neither the name of the LSC Project nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 *                  ==LICENSE NOTICE==
 *
 *               (c) 2008 - 2019 LSC Project
 *           Tung Tran Van <vttran@linagora.com>
 ****************************************************************************
 */
package org.lsc.plugins.connectors.james;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;

import org.apache.commons.lang.RandomStringUtils;
import org.lsc.LscDatasets;
import org.lsc.LscModifications;
import org.lsc.beans.IBean;
import org.lsc.configuration.PluginConnectionType;
import org.lsc.configuration.TaskType;
import org.lsc.exception.LscServiceCommunicationException;
import org.lsc.exception.LscServiceConfigurationException;
import org.lsc.exception.LscServiceException;
import org.lsc.plugins.connectors.james.beans.User;
import org.lsc.plugins.connectors.james.generated.JamesService;
import org.lsc.plugins.connectors.james.generated.JamesUsersService;
import org.lsc.service.IWritableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

public class JamesUserDstService implements IWritableService {
    private static final Logger LOGGER = LoggerFactory.getLogger(JamesUserDstService.class);
    public static final int USER_PASSWORD_LENGTH = 24;

    private final JamesDao jamesDao;
    private final JamesService service;
    private final Class<IBean> beanClass;

    public JamesUserDstService(final TaskType task) throws LscServiceConfigurationException {
        try {
            if (task.getPluginDestinationService().getAny() == null
                || task.getPluginDestinationService().getAny().size() != 1
                || !((task.getPluginDestinationService().getAny().get(0) instanceof JamesUsersService))) {
                throw new LscServiceConfigurationException("Unable to identify the James service configuration " + "inside the plugin source node of the task: " + task.getName());
            }

            this.service = (JamesService) task.getPluginDestinationService().getAny().get(0);
            this.beanClass = (Class<IBean>) Class.forName(task.getBean());
            LOGGER.debug("Task bean is: " + task.getBean());
            PluginConnectionType connection = (PluginConnectionType) service.getConnection().getReference();
            this.jamesDao = new JamesDao(connection.getUrl(), connection.getPassword(), task);
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
                    return jamesDao.addUser(user, RandomStringUtils.randomAlphanumeric(USER_PASSWORD_LENGTH));
                case DELETE_OBJECT:
                    return jamesDao.removeUser(user);
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
        if (pivotAttributes.getAttributesNames().size() < 1) {
            return null;
        }
        String pivotAttribute = pivotAttributes.getAttributesNames().get(0);
        String email = pivotAttributes.getStringValueAttribute(pivotAttribute);
        if (email == null) {
            return null;
        }
        try {
            if (jamesDao.userExists(email)) {
                IBean bean = beanClass.newInstance();
                bean.setMainIdentifier(email);
                bean.setDatasets(new User(email).toDatasets());
                return bean;
            }
            return null;
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
            List<User> userList = jamesDao.getUserList();
            LOGGER.debug("Get ListPivots. userList size = {}", userList.size());
            Map<String, LscDatasets> listPivots = new HashMap<>();
            for (User user : userList) {
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
}
