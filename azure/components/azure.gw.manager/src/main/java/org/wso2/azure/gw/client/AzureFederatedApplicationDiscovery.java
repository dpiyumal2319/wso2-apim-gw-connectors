/*
 * Copyright (c) 2025 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.azure.gw.client;

import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.core.http.rest.PagedIterable;
import com.azure.core.http.rest.PagedResponse;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.core.util.Context;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.resourcemanager.apimanagement.ApiManagementManager;
import com.azure.resourcemanager.apimanagement.models.SubscriptionContract;
import lombok.Getter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.azure.gw.client.datastore.AzureProductDataStore;
import org.wso2.azure.gw.client.util.AzureApplicationUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.FederatedApplicationDiscovery;
import org.wso2.carbon.apimgt.api.model.DiscoveredApplication;
import org.wso2.carbon.apimgt.api.model.DiscoveredApplicationResult;
import org.wso2.carbon.apimgt.api.model.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * This class provides the implementation for the discovery of Applications (Subscriptions)
 * from the Azure API Management Gateway.
 *
 * Azure APIM uses "Subscriptions" as the equivalent of WSO2 Applications.
 */
public class AzureFederatedApplicationDiscovery implements FederatedApplicationDiscovery {

    private static final Log log = LogFactory.getLog(AzureFederatedApplicationDiscovery.class);

    private String resourceGroup;
    private String serviceName;
    private ApiManagementManager manager;

    @Getter
    private AzureProductDataStore productDataStore;

    @Override
    public void init(Environment environment, String organization) throws APIManagementException {
        if (log.isDebugEnabled()) {
            log.debug("Initializing Azure Application Discovery for environment: " + environment.getName());
        }
        try {
            String tenantId = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_TENANT_ID);
            String clientId = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_CLIENT_ID);
            String clientSecret = environment.getAdditionalProperties()
                    .get(AzureConstants.AZURE_ENVIRONMENT_CLIENT_SECRET);
            String subscriptionId = environment.getAdditionalProperties()
                    .get(AzureConstants.AZURE_ENVIRONMENT_SUBSCRIPTION_ID);

            HttpClient httpClient = new NettyAsyncHttpClientBuilder().build();

            TokenCredential cred = new ClientSecretCredentialBuilder()
                    .httpClient(httpClient)
                    .tenantId(tenantId)
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .authorityHost(AzureEnvironment.AZURE.getActiveDirectoryEndpoint())
                    .build();

            AzureProfile profile = new AzureProfile(tenantId, subscriptionId, AzureEnvironment.AZURE);
            manager = ApiManagementManager.configure().withHttpClient(httpClient).authenticate(cred, profile);

            resourceGroup = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_RESOURCE_GROUP);
            serviceName = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_SERVICE_NAME);

            if (tenantId == null || clientId == null || clientSecret == null || subscriptionId == null
                    || resourceGroup == null || serviceName == null) {
                throw new APIManagementException("Missing required Azure environment configurations");
            }

            // Initialize product data store for tier mapping
            productDataStore = new AzureProductDataStore(manager, resourceGroup, serviceName);

            if (log.isDebugEnabled()) {
                log.debug("Initialization completed for Azure Application Discovery: " + environment.getName());
            }

        } catch (Exception e) {
            throw new APIManagementException("Error occurred while initializing Azure Application Discovery", e);
        }
    }

    @Override
    public List<DiscoveredApplication> discoverApplications() throws APIManagementException {
        return discoverApplications(0, 100);
    }

    @Override
    public List<DiscoveredApplication> discoverApplications(int offset, int limit) throws APIManagementException {
        return discoverApplications(offset, limit, null);
    }

    @Override
    public List<DiscoveredApplication> discoverApplications(int offset, int limit, String query)
            throws APIManagementException {

        if (log.isDebugEnabled()) {
            log.debug("Discovering Azure subscriptions with offset: " + offset + ", limit: "
                    + limit + ", query: " + query);
        }

        List<DiscoveredApplication> discoveredApplications = new ArrayList<>();

        try {
            // Build filter for Azure API
            String filter = buildFilter(query);

            // List subscriptions with pagination
            PagedIterable<SubscriptionContract> subscriptions = manager.subscriptions().list(
                    resourceGroup,
                    serviceName,
                    filter,
                    limit,  // top
                    offset, // skip
                    Context.NONE
            );

            // Ensure product data store is initialized for tier mapping
            productDataStore.initialize();

            for (SubscriptionContract subscription : subscriptions) {
                try {
                    DiscoveredApplication discoveredApp = AzureApplicationUtil.subscriptionToDiscoveredApplication(
                            subscription, manager, resourceGroup, serviceName, productDataStore);
                    discoveredApplications.add(discoveredApp);
                } catch (Exception e) {
                    log.error("Error converting Azure subscription to DiscoveredApplication: "
                            + subscription.name(), e);
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("Discovered " + discoveredApplications.size() + " Azure subscriptions");
            }

        } catch (Exception e) {
            throw new APIManagementException("Error occurred while discovering Azure subscriptions", e);
        }

        return discoveredApplications;
    }

    @Override
    public DiscoveredApplicationResult discoverApplicationsWithPagination(int offset, int limit, String query)
            throws APIManagementException {

        List<DiscoveredApplication> applications = discoverApplications(offset, limit, query);
        int totalCount = getTotalApplicationCount(query);

        DiscoveredApplicationResult result = new DiscoveredApplicationResult();
        result.setDiscoveredApplications(applications);
        result.setTotalCount(totalCount);
        result.setOffset(offset);
        result.setLimit(limit);
        result.setHasMoreResults(offset + applications.size() < totalCount);

        return result;
    }

    @Override
    public int getTotalApplicationCount() throws APIManagementException {
        return getTotalApplicationCount(null);
    }

    // TODO: Implement efficient method to reuse count logic
    // TODO: Optimize count query if Azure API supports it in future,
    //  if not implement caching using inbuilt in-mem key value store
    @Override
    public int getTotalApplicationCount(String query) throws APIManagementException {
        try {
            String filter = buildFilter(query);
            PagedIterable<SubscriptionContract> subscriptions = manager.subscriptions().list(
                    resourceGroup,
                    serviceName,
                    filter,
                    null,  // top: null to get all items
                    null,  // skip: null to start from beginning
                    Context.NONE
            );

            // Count by iterating through pages
            // Azure API Management SDK does not provide a direct count API,
            // so we need to iterate through the results
            int count = 0;
            for (PagedResponse<SubscriptionContract> page : subscriptions.iterableByPage()) {
                count += page.getValue().size();
            }
            return count;
        } catch (Exception e) {
            throw new APIManagementException("Error occurred while counting Azure subscriptions", e);
        }
    }

    @Override
    public boolean applicationExists(String externalId) throws APIManagementException {
        try {
            SubscriptionContract subscription = manager.subscriptions().get(
                    resourceGroup, serviceName, externalId);
            return subscription != null;
        } catch (Exception e) {
            // Subscription not found
            return false;
        }
    }

    @Override
    public boolean isApplicationUpdated(String existingReferenceArtifact, String newReferenceArtifact) {
        return AzureApplicationUtil.isApplicationUpdated(existingReferenceArtifact, newReferenceArtifact);
    }

    @Override
    public String getGatewayType() {
        return AzureConstants.AZURE_TYPE;
    }

    @Override
    public DiscoveredApplication getApplication(String externalId) throws APIManagementException {
        try {
            SubscriptionContract subscription = manager.subscriptions().get(
                    resourceGroup, serviceName, externalId);

            if (subscription == null) {
                throw new APIManagementException("Azure subscription not found: " + externalId);
            }

            // Ensure product data store is initialized
            productDataStore.initialize();

            return AzureApplicationUtil.subscriptionToDiscoveredApplication(
                    subscription, manager, resourceGroup, serviceName, productDataStore);
        } catch (APIManagementException e) {
            throw e;
        } catch (Exception e) {
            throw new APIManagementException("Error occurred while fetching Azure subscription: " + externalId, e);
        }
    }

    /**
     * Builds the OData filter string for Azure subscription listing.
     *
     * @param query The search query (filters by display name).
     * @return The OData filter string, or null if no filter needed.
     */
    private String buildFilter(String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }
        // OData filter for subscription display name contains query
        return "contains(properties/displayName, '" + query.replace("'", "''") + "')";
    }

}


