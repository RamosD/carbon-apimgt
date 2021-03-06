/*
 *  Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.wso2.carbon.apimgt.rest.api.admin.v1.impl;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.cxf.jaxrs.ext.MessageContext;
import org.wso2.carbon.apimgt.api.APIAdmin;
import org.wso2.carbon.apimgt.api.APIConsumer;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.Application;
import org.wso2.carbon.apimgt.api.model.Subscriber;
import org.wso2.carbon.apimgt.impl.APIAdminImpl;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerFactory;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.rest.api.admin.v1.ApplicationsApiService;
import org.wso2.carbon.apimgt.rest.api.admin.v1.dto.ApplicationListDTO;
import org.wso2.carbon.apimgt.rest.api.admin.v1.utils.mappings.ApplicationMappingUtil;
import org.wso2.carbon.apimgt.rest.api.util.RestApiConstants;
import org.wso2.carbon.apimgt.rest.api.util.utils.RestAPIStoreUtils;
import org.wso2.carbon.apimgt.rest.api.util.utils.RestApiUtil;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import javax.ws.rs.core.Response;

public class ApplicationsApiServiceImpl implements ApplicationsApiService {

    private static final Log log = LogFactory.getLog(ApplicationsApiServiceImpl.class);

    @Override
    public Response applicationsApplicationIdChangeOwnerPost(String owner, String applicationId,
                                                             MessageContext messageContext) {

        APIConsumer apiConsumer = null;
        try {
            apiConsumer = APIManagerFactory.getInstance().getAPIConsumer(owner);
            Application application = apiConsumer.getApplicationByUUID(applicationId);
            boolean applicationUpdated = apiConsumer.updateApplicationOwner(owner, application);
            if (applicationUpdated) {
                return Response.ok().build();
            } else {
                RestApiUtil.handleInternalServerError("Error while updating application owner " + applicationId, log);
            }

        } catch (APIManagementException e) {
            RestApiUtil.handleInternalServerError("Error while updating application owner " + applicationId, e, log);
        }

        return null;
    }

    @Override
    public Response applicationsApplicationIdDelete(String applicationId, String ifMatch, MessageContext messageContext) throws APIManagementException {
        String username = RestApiUtil.getLoggedInUsername();
        try {
            APIConsumer apiConsumer = APIManagerFactory.getInstance().getAPIConsumer(username);
            Application application = apiConsumer.getApplicationByUUID(applicationId);
            if (application != null) {
                apiConsumer.removeApplication(application, application.getOwner());
                return Response.ok().build();
            } else {
                RestApiUtil.handleResourceNotFoundError(RestApiConstants.RESOURCE_APPLICATION, applicationId, log);
            }
        } catch (APIManagementException e) {
            RestApiUtil.handleInternalServerError("Error while deleting application " + applicationId, e, log);
        }
        return null;
    }

    @Override
    public Response applicationsGet(String user, Integer limit, Integer offset, String accept, String ifNoneMatch,
                                    String name, String appTenantDomain, MessageContext messageContext) {

        // To store the initial value of the user (specially if it is null or empty)
        String givenUser = user;

        // if no username provided user associated with access token will be used
        if (user == null || StringUtils.isEmpty(user)) {
            user = RestApiUtil.getLoggedInUsername();
        }

        limit = limit != null ? limit : RestApiConstants.PAGINATION_LIMIT_DEFAULT;
        offset = offset != null ? offset : RestApiConstants.PAGINATION_OFFSET_DEFAULT;

        ApplicationListDTO applicationListDTO;
        try {
            Application[] allMatchedApps;
            boolean migrationMode = Boolean.getBoolean(RestApiConstants.MIGRATION_MODE);
            if (!migrationMode) { // normal non-migration flow
                if (!MultitenantUtils.getTenantDomain(user).equals(RestApiUtil.getLoggedInUserTenantDomain())) {
                    String errorMsg = "User " + user + " is not available for the current tenant domain";
                    log.error(errorMsg);
                    return Response.status(Response.Status.FORBIDDEN).entity(errorMsg).build();
                }
                APIConsumer apiConsumer = APIManagerFactory.getInstance().getAPIConsumer(user);

                // If no user is passed, get the applications for the tenant (not only for the user)
                if ((givenUser == null || StringUtils.isEmpty(givenUser)) &&
                        (name == null || StringUtils.isEmpty(name))) {
                    APIAdmin apiAdmin = new APIAdminImpl();
                    int tenantId = APIUtil.getTenantId(user);
                    allMatchedApps = apiAdmin.getApplicationsByTenantIdWithPagination(tenantId, 0, limit, "", "",
                            APIConstants.APPLICATION_NAME, RestApiConstants.DEFAULT_SORT_ORDER).toArray(new Application[0]);
                } else if (givenUser != null && (name == null || StringUtils.isEmpty(name))) {
                    allMatchedApps = apiConsumer.getApplicationsByOwner(user);
                } else {
                    allMatchedApps = apiConsumer.getApplicationsWithPagination(new Subscriber(user), "",
                            offset, limit, name, APIConstants.APPLICATION_NAME, RestApiConstants.DEFAULT_SORT_ORDER);
                }
            } else { // flow at migration process
                if (StringUtils.isEmpty(appTenantDomain)) {
                    appTenantDomain = MultitenantUtils.getTenantDomain(user);
                }
                RestApiUtil.handleMigrationSpecificPermissionViolations(appTenantDomain,
                        RestApiUtil.getLoggedInUsername());
                APIAdmin apiAdmin = new APIAdminImpl();
                allMatchedApps = apiAdmin.getAllApplicationsOfTenantForMigration(appTenantDomain);
            }
            //allMatchedApps are already sorted to application name
            applicationListDTO = ApplicationMappingUtil.fromApplicationsToDTO(allMatchedApps, limit, offset);
            ApplicationMappingUtil.setPaginationParams(applicationListDTO, limit, offset, allMatchedApps.length);

            return Response.ok().entity(applicationListDTO).build();
        } catch (APIManagementException e) {
            RestApiUtil.handleInternalServerError("Error while retrieving applications of the user " + user, e, log);
        }
        return null;
    }
}
