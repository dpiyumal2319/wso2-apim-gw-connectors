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

package org.wso2.azure.gw.client.util;

import com.azure.resourcemanager.apimanagement.ApiManagementManager;
import com.azure.resourcemanager.apimanagement.models.SubscriptionKeysContract;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.azure.gw.client.AzureConstants;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.DiscoveredApplication;

import java.util.HashMap;
import java.util.Map;

/**
 * Helper class for transforming Azure subscriptions during import.
 * Provides methods for creating WSO2 Applications from discovered Azure subscriptions.
 */
public class AzureApplicationImportHelper {

    private static final Log log = LogFactory.getLog(AzureApplicationImportHelper.class);
    private static final Gson gson = new Gson();

    /**
     * Retrieves the actual subscription credentials during import.
     * This method should only be called during the import process.
     *
     * @param referenceArtifact The reference artifact containing subscription info.
     * @param manager           The Azure API Management Manager.
     * @param resourceGroup     The Azure resource group.
     * @param serviceName       The Azure APIM service name.
     * @return A map containing the actual subscription keys.
     * @throws APIManagementException If there is an error retrieving the credentials.
     */
    public static Map<String, String> retrieveApplicationCredentials(
            String referenceArtifact,
            ApiManagementManager manager,
            String resourceGroup,
            String serviceName) throws APIManagementException {

        Map<String, String> credentials = new HashMap<>();

        try {
            JsonObject artifact = gson.fromJson(referenceArtifact, JsonObject.class);
            String subscriptionId = artifact.get(AzureConstants.AZURE_APP_EXTERNAL_REF_SUBSCRIPTION_ID).getAsString();

            SubscriptionKeysContract keys = manager.subscriptions().listSecrets(
                    resourceGroup, serviceName, subscriptionId);

            if (keys != null) {
                if (keys.primaryKey() != null) {
                    credentials.put(AzureConstants.AZURE_KEY_TYPE_PRIMARY, keys.primaryKey());
                }
                if (keys.secondaryKey() != null) {
                    credentials.put(AzureConstants.AZURE_KEY_TYPE_SECONDARY, keys.secondaryKey());
                }
            }

            return credentials;
        } catch (Exception e) {
            throw new APIManagementException("Error retrieving Azure subscription credentials", e);
        }
    }

    /**
     * Maps Azure subscription state to WSO2 application status.
     *
     * @param azureState The Azure subscription state.
     * @return The corresponding WSO2 application status.
     */
    public static String mapAzureStateToWSO2Status(String azureState) {
        if (azureState == null) {
            return "APPROVED";
        }

        switch (azureState.toLowerCase()) {
            case AzureConstants.AZURE_SUBSCRIPTION_STATE_ACTIVE:
                return "APPROVED";
            case AzureConstants.AZURE_SUBSCRIPTION_STATE_SUSPENDED:
                return "BLOCKED";
            case AzureConstants.AZURE_SUBSCRIPTION_STATE_CANCELLED:
            case AzureConstants.AZURE_SUBSCRIPTION_STATE_REJECTED:
                return "REJECTED";
            case AzureConstants.AZURE_SUBSCRIPTION_STATE_SUBMITTED:
                return "CREATED";
            default:
                return "APPROVED";
        }
    }

    /**
     * Validates that the discovered application can be imported.
     *
     * @param discoveredApplication The discovered application to validate.
     * @return true if the application can be imported, false otherwise.
     */
    public static boolean canImportApplication(DiscoveredApplication discoveredApplication) {
        if (discoveredApplication == null) {
            return false;
        }

        if (discoveredApplication.getExternalId() == null || discoveredApplication.getExternalId().isEmpty()) {
            log.warn("Cannot import application without external ID");
            return false;
        }

        if (discoveredApplication.getName() == null || discoveredApplication.getName().isEmpty()) {
            log.warn("Cannot import application without name: " + discoveredApplication.getExternalId());
            return false;
        }

        // Check if already imported
        if (discoveredApplication.isAlreadyImported()) {
            log.info("Application already imported: " + discoveredApplication.getName());
            return false;
        }

        return true;
    }

    /**
     * Extracts the subscription ID from the reference artifact.
     *
     * @param referenceArtifact The reference artifact JSON string.
     * @return The subscription ID.
     * @throws APIManagementException If the subscription ID cannot be extracted.
     */
    public static String extractSubscriptionId(String referenceArtifact) throws APIManagementException {
        try {
            JsonObject artifact = gson.fromJson(referenceArtifact, JsonObject.class);

            if (!artifact.has(AzureConstants.AZURE_APP_EXTERNAL_REF_SUBSCRIPTION_ID)) {
                throw new APIManagementException("Reference artifact does not contain subscription ID");
            }

            return artifact.get(AzureConstants.AZURE_APP_EXTERNAL_REF_SUBSCRIPTION_ID).getAsString();
        } catch (Exception e) {
            throw new APIManagementException("Error extracting subscription ID from reference artifact", e);
        }
    }

    /**
     * Extracts the tier from the reference artifact.
     *
     * @param referenceArtifact The reference artifact JSON string.
     * @return The WSO2 tier name.
     */
    public static String extractTier(String referenceArtifact) {
        try {
            JsonObject artifact = gson.fromJson(referenceArtifact, JsonObject.class);

            if (artifact.has(AzureConstants.AZURE_APP_EXTERNAL_REF_TIER_MAPPING)) {
                JsonObject tierMapping = artifact.getAsJsonObject(AzureConstants.AZURE_APP_EXTERNAL_REF_TIER_MAPPING);
                if (tierMapping.has("wso2Tier")) {
                    return tierMapping.get("wso2Tier").getAsString();
                }
            }

            return AzureConstants.AZURE_DEFAULT_TIER;
        } catch (Exception e) {
            log.warn("Error extracting tier from reference artifact, using default", e);
            return AzureConstants.AZURE_DEFAULT_TIER;
        }
    }

    /**
     * Builds an Application attributes map from the reference artifact.
     *
     * @param referenceArtifact The reference artifact JSON string.
     * @return A map of application attributes.
     */
    public static Map<String, String> buildApplicationAttributes(String referenceArtifact) {
        Map<String, String> attributes = new HashMap<>();

        try {
            JsonObject artifact = gson.fromJson(referenceArtifact, JsonObject.class);

            // Add source gateway type
            attributes.put("sourceGateway", AzureConstants.AZURE_TYPE);

            // Add Azure-specific attributes
            if (artifact.has(AzureConstants.AZURE_APP_EXTERNAL_REF_SUBSCRIPTION_ID)) {
                attributes.put("azureSubscriptionId",
                        artifact.get(AzureConstants.AZURE_APP_EXTERNAL_REF_SUBSCRIPTION_ID).getAsString());
            }

            if (artifact.has(AzureConstants.AZURE_APP_EXTERNAL_REF_PRODUCT_ID)) {
                attributes.put("azureProductId",
                        artifact.get(AzureConstants.AZURE_APP_EXTERNAL_REF_PRODUCT_ID).getAsString());
            }

            if (artifact.has(AzureConstants.AZURE_APP_EXTERNAL_REF_PRODUCT_NAME)) {
                attributes.put("azureProductName",
                        artifact.get(AzureConstants.AZURE_APP_EXTERNAL_REF_PRODUCT_NAME).getAsString());
            }

            if (artifact.has(AzureConstants.AZURE_APP_EXTERNAL_REF_STATE)) {
                attributes.put("azureState",
                        artifact.get(AzureConstants.AZURE_APP_EXTERNAL_REF_STATE).getAsString());
            }

            if (artifact.has(AzureConstants.AZURE_APP_EXTERNAL_REF_SCOPE)) {
                attributes.put("azureScope",
                        artifact.get(AzureConstants.AZURE_APP_EXTERNAL_REF_SCOPE).getAsString());
            }

        } catch (Exception e) {
            log.warn("Error building application attributes from reference artifact", e);
        }

        return attributes;
    }
}

