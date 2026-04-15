/*
 * Copyright (c) 2020-2026, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.custom.user.store.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.wso2.carbon.user.api.UserStoreManager;
import org.wso2.custom.user.store.CustomUserStoreManager;

/**
 * Bundle activator for the Custom JDBC User Store Manager.
 * Registers the custom user store manager as an OSGi service so that
 * WSO2 Identity Server discovers it via UserStoreManagerRegistry.
 */
public class CustomUserStoreBundleActivator implements BundleActivator {

    private static final Log log = LogFactory.getLog(CustomUserStoreBundleActivator.class);

    @Override
    public void start(BundleContext context) throws Exception {

        context.registerService(UserStoreManager.class.getName(), new CustomUserStoreManager(), null);
        log.info("CustomUserStoreManager bundle activated successfully.");
    }

    @Override
    public void stop(BundleContext context) throws Exception {

        if (log.isDebugEnabled()) {
            log.debug("CustomUserStoreManager bundle deactivated.");
        }
    }
}
