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
import com.azure.resourcemanager.apimanagement.models.SubscriptionContract;
import com.azure.resourcemanager.apimanagement.models.SubscriptionKeysContract;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.azure.gw.client.AzureConstants;
import org.wso2.azure.gw.client.datastore.AzureProductDataStore;
import org.wso2.azure.gw.client.model.AzureSubscriptionKeyInfo;
import org.wso2.carbon.apimgt.api.model.DiscoveredApplication;
import org.wso2.carbon.apimgt.api.model.DiscoveredApplicationInfo;
import org.wso2.carbon.apimgt.api.model.DiscoveredApplicationKeyInfo;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for Azure application (subscription) operations.
 * Provides methods for converting Azure subscriptions to DiscoveredApplication objects.
 */
public class AzureApplicationUtil {

    private static final Log log = LogFactory.getLog(AzureApplicationUtil.class);
    private static final Gson gson = new Gson();

    /**
     * Converts an Azure SubscriptionContract to a DiscoveredApplication.
     *
     * @param subscription     The Azure subscription contract.
     * @param manager          The Azure API Management Manager.
     * @param resourceGroup    The Azure resource group.
     * @param serviceName      The Azure APIM service name.
     * @param productDataStore The product data store for tier mapping.
     * @param fetchKeys        Whether to fetch and include subscription keys (masked).
     * @return The converted DiscoveredApplication object.
     */
    /**
     * Converts an Azure SubscriptionContract to a full DiscoveredApplication with keys and API subscriptions.
     * This is used for detail operations where complete information including masked keys is needed.
     *
     * @param subscription     The Azure subscription contract
     * @param manager          The Azure API Management Manager
     * @param resourceGroup    The Azure resource group
     * @param serviceName      The Azure APIM service name
     * @param productDataStore Product data store for tier/product mapping
     * @return DiscoveredApplication with full details including keys and subscriptions
     */
    public static DiscoveredApplication subscriptionToDiscoveredApplication(
            SubscriptionContract subscription,
            ApiManagementManager manager,
            String resourceGroup,
            String serviceName,
            AzureProductDataStore productDataStore) {

        // First get lightweight info
        org.wso2.carbon.apimgt.api.model.DiscoveredApplicationInfo info =
                subscriptionToDiscoveredApplicationInfo(subscription, manager, resourceGroup,
                        serviceName, productDataStore);

        // Convert to full application
        DiscoveredApplication discoveredApp = new DiscoveredApplication(info);

        // Fetch and add masked keys
        try {
            SubscriptionKeysContract keys = manager.subscriptions()
                    .listSecrets(resourceGroup, serviceName, subscription.name());

            if (keys != null) {
                List<DiscoveredApplicationKeyInfo> keyInfoList = new ArrayList<>();

                if (keys.primaryKey() != null) {
                    DiscoveredApplicationKeyInfo primaryKey = new DiscoveredApplicationKeyInfo();
                    primaryKey.setKeyType("PRIMARY");
                    primaryKey.setKeyName("Primary Key");
                    primaryKey.setMaskedKeyValue(AzureSubscriptionKeyInfo.maskKeyValue(keys.primaryKey()));
                    primaryKey.setExternalKeyReference(subscription.name() + ":primary");
                    keyInfoList.add(primaryKey);
                }

                if (keys.secondaryKey() != null) {
                    DiscoveredApplicationKeyInfo secondaryKey = new DiscoveredApplicationKeyInfo();
                    secondaryKey.setKeyType("SECONDARY");
                    secondaryKey.setKeyName("Secondary Key");
                    secondaryKey.setMaskedKeyValue(AzureSubscriptionKeyInfo.maskKeyValue(keys.secondaryKey()));
                    secondaryKey.setExternalKeyReference(subscription.name() + ":secondary");
                    keyInfoList.add(secondaryKey);
                }

                discoveredApp.setKeyInfoList(keyInfoList);
            }
        } catch (Exception e) {
            log.error("Error fetching keys for subscription: " + subscription.name(), e);
        }

        // Fetch and add API subscriptions
        try {
            String productId = subscription.scope();
            if (productId != null && !productId.isEmpty()) {
                org.wso2.carbon.apimgt.api.model.DiscoveredAPISubscription apiSub =
                        new org.wso2.carbon.apimgt.api.model.DiscoveredAPISubscription();
                
                // Extract product name from scope
                String[] scopeParts = productId.split("/");
                if (scopeParts.length > 0) {
                    String productName = scopeParts[scopeParts.length - 1];
                    apiSub.setApiId(productId);
                    apiSub.setApiName(productName);
                    apiSub.setSubscriptionTier(discoveredApp.getThrottlingTier());
                    apiSub.setSubscriptionStatus(subscription.state() != null ?
                            subscription.state().toString() : "ACTIVE");
                }
                
                discoveredApp.addApiSubscription(apiSub);
            }
        } catch (Exception e) {
            log.error("Error fetching API subscriptions for subscription: " + subscription.name(), e);
        }

        return discoveredApp;
    }

    /**
     * Extracts the product ID from the Azure subscription scope.
     *
     * @param scope The subscription scope path.
     * @return The product ID, or null if scope is not a product.
     */
    public static String extractProductIdFromScope(String scope) {
        if (scope == null || scope.isEmpty()) {
            return null;
        }
        // Scope format: /products/{productId} or /apis/{apiId}
        if (scope.startsWith("/products/")) {
            return scope.substring("/products/".length());
        }
        return null;
    }

    /**
     * Creates an attributes map from the subscription.
     *
     * @param subscription The Azure subscription contract.
     * @return A map of attributes.
     */
    private static Map<String, String> createAttributesMap(SubscriptionContract subscription) {
        Map<String, String> attributes = new HashMap<>();

        if (subscription.state() != null) {
            attributes.put("state", subscription.state().toString().toLowerCase());
        }
        if (subscription.scope() != null) {
            attributes.put("scope", subscription.scope());
        }
        if (subscription.stateComment() != null) {
            attributes.put("stateComment", subscription.stateComment());
        }
        if (subscription.allowTracing() != null) {
            attributes.put("allowTracing", subscription.allowTracing().toString());
        }

        return attributes;
    }

    /**
     * Builds a description string for the discovered application.
     *
     * @param subscription The Azure subscription contract.
     * @param productName  The product name.
     * @return The description string.
     */
    private static String buildDescription(SubscriptionContract subscription, String productName) {
        StringBuilder description = new StringBuilder();
        description.append("Azure APIM Subscription");

        if (productName != null && !productName.isEmpty()) {
            description.append(" for product: ").append(productName);
        }

        if (subscription.state() != null) {
            description.append(" (").append(subscription.state().toString()).append(")");
        }

        return description.toString();
    }

    /**
     * Builds the key info list with masked subscription keys.
     *
     * @param subscription  The Azure subscription contract.
     * @param manager       The Azure API Management Manager.
     * @param resourceGroup The Azure resource group.
     * @param serviceName   The Azure APIM service name.
     * @return List of DiscoveredApplicationKeyInfo objects with masked keys.
     */
    public static List<DiscoveredApplicationKeyInfo> buildKeyInfoList(
            SubscriptionContract subscription,
            ApiManagementManager manager,
            String resourceGroup,
            String serviceName) {

        List<DiscoveredApplicationKeyInfo> keyInfoList = new ArrayList<>();

        try {
            // Fetch subscription keys from Azure
            SubscriptionKeysContract keys = manager.subscriptions().listSecrets(
                    resourceGroup, serviceName, subscription.name());

            if (keys != null) {
                // Primary key
                if (keys.primaryKey() != null) {
                    DiscoveredApplicationKeyInfo primaryKeyInfo = new DiscoveredApplicationKeyInfo();
                    primaryKeyInfo.setKeyType(AzureConstants.AZURE_KEY_TYPE_PRIMARY);
                    primaryKeyInfo.setKeyName("Primary Key");
                    primaryKeyInfo.setMaskedKeyValue(AzureSubscriptionKeyInfo.maskKeyValue(keys.primaryKey()));
                    primaryKeyInfo.setExternalKeyReference(subscription.name() + "_primary");
                    primaryKeyInfo.setState(subscription.state() != null ?
                            subscription.state().toString().toLowerCase() : "active");
                    keyInfoList.add(primaryKeyInfo);
                }

                // Secondary key
                if (keys.secondaryKey() != null) {
                    DiscoveredApplicationKeyInfo secondaryKeyInfo = new DiscoveredApplicationKeyInfo();
                    secondaryKeyInfo.setKeyType(AzureConstants.AZURE_KEY_TYPE_SECONDARY);
                    secondaryKeyInfo.setKeyName("Secondary Key");
                    secondaryKeyInfo.setMaskedKeyValue(AzureSubscriptionKeyInfo.maskKeyValue(keys.secondaryKey()));
                    secondaryKeyInfo.setExternalKeyReference(subscription.name() + "_secondary");
                    secondaryKeyInfo.setState(subscription.state() != null ?
                            subscription.state().toString().toLowerCase() : "active");
                    keyInfoList.add(secondaryKeyInfo);
                }
            }
        } catch (Exception e) {
            log.warn("Unable to fetch subscription keys for: " + subscription.name(), e);
            // Return empty list - keys will be fetched during import
        }

        return keyInfoList;
    }

    /**
     * Generates the reference artifact JSON for an Azure subscription.
     *
     * @param subscription     The Azure subscription contract.
     * @param productId        The product ID.
     * @param productName      The product name.
     * @param productDataStore The product data store for tier mapping.
     * @return JSON string containing the reference artifact.
     */
    public static String generateApplicationReferenceArtifact(
            SubscriptionContract subscription,
            String productId,
            String productName,
            AzureProductDataStore productDataStore) {

        JsonObject referenceArtifact = new JsonObject();

        // Basic subscription info
        referenceArtifact.addProperty(AzureConstants.AZURE_APP_EXTERNAL_REF_SUBSCRIPTION_ID, subscription.name());
        referenceArtifact.addProperty(AzureConstants.AZURE_APP_EXTERNAL_REF_DISPLAY_NAME,
                subscription.displayName() != null ? subscription.displayName() : subscription.name());

        // State
        if (subscription.state() != null) {
            referenceArtifact.addProperty(AzureConstants.AZURE_APP_EXTERNAL_REF_STATE,
                    subscription.state().toString().toLowerCase());
        }

        // Scope and product info
        if (subscription.scope() != null) {
            referenceArtifact.addProperty(AzureConstants.AZURE_APP_EXTERNAL_REF_SCOPE, subscription.scope());
        }
        if (productId != null) {
            referenceArtifact.addProperty(AzureConstants.AZURE_APP_EXTERNAL_REF_PRODUCT_ID, productId);
        }
        if (productName != null) {
            referenceArtifact.addProperty(AzureConstants.AZURE_APP_EXTERNAL_REF_PRODUCT_NAME, productName);
        }

        // Timestamps
        if (subscription.createdDate() != null) {
            referenceArtifact.addProperty(AzureConstants.AZURE_APP_EXTERNAL_REF_CREATED_DATE,
                    subscription.createdDate().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }

        // Use startDate or expirationDate as modified date indicator if available
        if (subscription.startDate() != null) {
            referenceArtifact.addProperty(AzureConstants.AZURE_APP_EXTERNAL_REF_MODIFIED_DATE,
                    subscription.startDate().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        } else if (subscription.createdDate() != null) {
            referenceArtifact.addProperty(AzureConstants.AZURE_APP_EXTERNAL_REF_MODIFIED_DATE,
                    subscription.createdDate().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }

        // Key references (not actual keys)
        referenceArtifact.addProperty(AzureConstants.AZURE_APP_EXTERNAL_REF_PRIMARY_KEY_REF,
                subscription.name() + "_primary");
        referenceArtifact.addProperty(AzureConstants.AZURE_APP_EXTERNAL_REF_SECONDARY_KEY_REF,
                subscription.name() + "_secondary");

        // Tier mapping info
        if (productId != null && productDataStore != null) {
            JsonObject tierMapping = new JsonObject();
            Map<String, Integer> rateLimitInfo = productDataStore.getRateLimitInfo(productId);

            if (!rateLimitInfo.isEmpty()) {
                tierMapping.addProperty("calls", rateLimitInfo.getOrDefault("calls", 0));
                tierMapping.addProperty("renewalPeriod", rateLimitInfo.getOrDefault("renewal-period", 60));
            }
            tierMapping.addProperty("wso2Tier", productDataStore.getTierForProduct(productId));
            referenceArtifact.add(AzureConstants.AZURE_APP_EXTERNAL_REF_TIER_MAPPING, tierMapping);
        }

        // Owner info
        if (subscription.ownerId() != null) {
            String[] parts = subscription.ownerId().split("/");
            if (parts.length > 0) {
                referenceArtifact.addProperty(AzureConstants.AZURE_APP_EXTERNAL_REF_OWNER_EMAIL,
                        parts[parts.length - 1]);
            }
        }

        return gson.toJson(referenceArtifact);
    }

    /**
     * Compares two reference artifacts to check if the subscription has been updated.
     *
     * @param existingRef The existing reference artifact.
     * @param newRef      The new reference artifact.
     * @return true if the subscription has been updated, false otherwise.
     */
    public static boolean isApplicationUpdated(String existingRef, String newRef) {
        if (existingRef == null || newRef == null) {
            return true;
        }

        try {
            JsonObject existingArtifact = gson.fromJson(existingRef, JsonObject.class);
            JsonObject newArtifact = gson.fromJson(newRef, JsonObject.class);

            // Compare state
            String existingState = getJsonString(existingArtifact, AzureConstants.AZURE_APP_EXTERNAL_REF_STATE);
            String newState = getJsonString(newArtifact, AzureConstants.AZURE_APP_EXTERNAL_REF_STATE);
            if (nullSafeEquals(existingState, newState)) {
                return true;
            }

            // Compare modified date
            String existingModified =
                    getJsonString(existingArtifact, AzureConstants.AZURE_APP_EXTERNAL_REF_MODIFIED_DATE);
            String newModified = getJsonString(newArtifact, AzureConstants.AZURE_APP_EXTERNAL_REF_MODIFIED_DATE);
            if (nullSafeEquals(existingModified, newModified)) {
                return true;
            }

            // Compare display name
            String existingName = getJsonString(existingArtifact, AzureConstants.AZURE_APP_EXTERNAL_REF_DISPLAY_NAME);
            String newName = getJsonString(newArtifact, AzureConstants.AZURE_APP_EXTERNAL_REF_DISPLAY_NAME);
            return nullSafeEquals(existingName, newName);
        } catch (Exception e) {
            log.warn("Error comparing reference artifacts", e);
            return true;
        }
    }

    private static String getJsonString(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private static boolean nullSafeEquals(String a, String b) {
        if (a == null && b == null) {
            return false;
        }
        if (a == null || b == null) {
            return true;
        }
        return !a.equals(b);
    }

    /**
     * Converts an Azure SubscriptionContract to a lightweight DiscoveredApplicationInfo.
     * This is used for listing operations where keys and subscriptions are not needed.
     *
     * @param subscription     The Azure subscription contract
     * @param manager          The Azure API Management Manager
     * @param resourceGroup    The Azure resource group
     * @param serviceName      The Azure APIM service name
     * @param productDataStore The product data store for tier mapping
     * @return The converted DiscoveredApplicationInfo object
     */
    public static DiscoveredApplicationInfo subscriptionToDiscoveredApplicationInfo(
            SubscriptionContract subscription,
            ApiManagementManager manager,
            String resourceGroup,
            String serviceName,
            AzureProductDataStore productDataStore) {

        DiscoveredApplicationInfo info =
                new DiscoveredApplicationInfo();

        // Set basic properties
        info.setExternalId(subscription.name());
        info.setName(subscription.displayName() != null ? subscription.displayName() : subscription.name());

        // Extract owner information
        String ownerId = subscription.ownerId();
        if (ownerId != null && !ownerId.isEmpty()) {
            String[] parts = ownerId.split("/");
            if (parts.length > 0) {
                info.setOwner(parts[parts.length - 1]);
            }
        }

        // Set creation time
        if (subscription.createdDate() != null) {
            info.setCreatedTime(subscription.createdDate().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }

        // Map throttling tier from product
        String productId = subscription.scope();
        if (productId != null && !productId.isEmpty()) {
            String tierName = productDataStore.getTierForProduct(productId);
            info.setThrottlingTier(tierName != null ? tierName : AzureConstants.AZURE_DEFAULT_TIER);
        } else {
            info.setThrottlingTier(AzureConstants.AZURE_DEFAULT_TIER);
        }

        // Set reference artifact for import
        info.setReferenceArtifact(buildReferenceArtifact(subscription, info.getThrottlingTier()));

        // Set attributes
        Map<String, String> attributes = new HashMap<>();
        if (subscription.scope() != null) {
            attributes.put("scope", subscription.scope());
        }
        if (subscription.state() != null) {
            attributes.put("state", subscription.state().toString());
        }
        info.setAttributes(attributes);

        return info;
    }

    /**
     * Converts an Azure SubscriptionContract to a full DiscoveredApplication with keys and subscriptions.
     * This is used for detail view operations.
     *
     * @param subscription     The Azure subscription contract
     * @param manager          The Azure API Management Manager
     * @param resourceGroup    The Azure resource group
     * @param serviceName      The Azure APIM service name
     * @param productDataStore The product data store for tier mapping
     * @return The converted DiscoveredApplication object with keys and subscriptions
     */
    /**
     * Builds the reference artifact JSON for an Azure subscription.
     *
     * @param subscription The Azure subscription
     * @param tierName     The mapped tier name
     * @return JSON string representation
     */
    private static String buildReferenceArtifact(SubscriptionContract subscription, String tierName) {
        JsonObject artifact = new JsonObject();
        artifact.addProperty("externalId", subscription.name());
        artifact.addProperty("displayName", subscription.displayName());
        artifact.addProperty("scope", subscription.scope());
        artifact.addProperty("tier", tierName);
        artifact.addProperty("gatewayType", AzureConstants.AZURE_TYPE);
        return gson.toJson(artifact);
    }
}

