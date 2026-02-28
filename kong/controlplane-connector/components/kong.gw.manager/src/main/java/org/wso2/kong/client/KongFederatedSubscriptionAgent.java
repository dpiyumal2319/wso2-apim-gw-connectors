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

package org.wso2.kong.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import feign.Feign;
import feign.FeignException;
import feign.RequestInterceptor;
import feign.gson.GsonDecoder;
import feign.gson.GsonEncoder;
import feign.slf4j.Slf4jLogger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.FederatedSubscriptionAgent;
import org.wso2.carbon.apimgt.api.model.AgentOperationResult;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.FederatedCredential;
import org.wso2.carbon.apimgt.api.model.FederatedSubscriptionContext;
import org.wso2.carbon.apimgt.api.model.FederatedSubscriptionOptions;
import org.wso2.carbon.apimgt.api.model.InvocationInstruction;
import org.wso2.carbon.apimgt.api.model.SubscriptionSupportInfo;
import org.wso2.carbon.apimgt.api.model.VHost;
import org.wso2.carbon.apimgt.api.model.schema.credential.OpaqueApiKeyCredential;
import org.wso2.carbon.apimgt.api.model.schema.invocation.ApiKeyInvocation;
import org.wso2.carbon.apimgt.api.model.schema.options.OptionGroup;
import org.wso2.carbon.apimgt.api.model.schema.options.OptionGroups;
import org.wso2.carbon.apimgt.api.model.schema.options.OptionItem;
import org.wso2.carbon.apimgt.impl.kmclient.ApacheFeignHttpClient;
import org.wso2.kong.client.model.KongAPIImplementation;
import org.wso2.kong.client.model.KongAcl;
import org.wso2.kong.client.model.KongConsumer;
import org.wso2.kong.client.model.KongConsumerGroup;
import org.wso2.kong.client.model.KongConsumerGroupMembership;
import org.wso2.kong.client.model.KongKeyAuth;
import org.wso2.kong.client.model.KongListResponse;
import org.wso2.kong.client.model.KongPlugin;
import org.wso2.kong.client.model.PagedResponse;
import org.wso2.kong.client.util.KongAPIUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Kong Konnect Federated Subscription Agent.
 * <p>
 * Implements subscription management for Kong following the Universal Gateway architecture:
 * - Application → (skipped - no Kong entity needed)
 * - API → Kong Service (pre-deployed)
 * - Subscription → Kong Consumer + key-auth credential + ACL group membership
 * </p>
 * <p>
 * Key Design Decisions:
 * - Uses key-auth plugin for API key authentication
 * - ACL groups exposed as subscription options when multiple groups configured
 * - Single ACL group auto-assigned; no ACL means unscoped credential
 * - Regeneration keeps consumer alive, only rotates key-auth credential
 * - Supports credential retrieval (isValueRetrievable: true)
 * - Consumer username pattern: wso2_{subscriptionUuid}
 * </p>
 */
public class KongFederatedSubscriptionAgent implements FederatedSubscriptionAgent {

    private static final Log log = LogFactory.getLog(KongFederatedSubscriptionAgent.class);
    private static final String GATEWAY_TYPE = "Kong";

    private KongKonnectApi apiGatewayClient;
    private String controlPlaneId;
    private String proxyUrl;

    @Override
    public void init(Environment environment, String organization) throws APIManagementException {
        if (log.isDebugEnabled()) {
            log.debug("Initializing Kong Subscription Agent for Environment: " + environment.getName()
                    + " in Organization: " + organization);
        }

        try {
            String adminUrl = environment.getAdditionalProperties().get(KongConstants.KONG_ADMIN_URL);
            this.controlPlaneId = environment.getAdditionalProperties().get(KongConstants.KONG_CONTROL_PLANE_ID);
            String authToken = environment.getAdditionalProperties().get(KongConstants.KONG_AUTH_TOKEN);
            this.proxyUrl = environment.getAdditionalProperties().get(KongConstants.KONG_PROXY_URL);

            if (adminUrl == null || controlPlaneId == null || authToken == null) {
                throw new APIManagementException("Missing required Kong environment configurations");
            }

            // Fallback to VHost if proxyUrl not provided
            if (proxyUrl == null || proxyUrl.isEmpty()) {
                List<VHost> vhosts = environment.getVhosts();
                if (vhosts != null && !vhosts.isEmpty()) {
                    proxyUrl = "https://" + vhosts.get(0).getHost();
                } else {
                    proxyUrl = "https://" + KongConstants.DEFAULT_VHOST;
                }
            }

            // Build Feign client (same pattern as KongFederatedAPIDiscovery)
            CloseableHttpClient httpClient = HttpClients.custom().build();
            RequestInterceptor auth = template ->
                    template.header(KongConstants.AUTHORIZATION_HEADER, KongConstants.BEARER_PREFIX + authToken);

            apiGatewayClient = Feign.builder()
                    .client(new ApacheFeignHttpClient(httpClient))
                    .encoder(new GsonEncoder())
                    .decoder(new GsonDecoder())
                    .errorDecoder(new KongErrorDecoder())
                    .logger(new Slf4jLogger(KongKonnectApi.class))
                    .requestInterceptor(auth)
                    .target(KongKonnectApi.class, adminUrl);

            if (log.isDebugEnabled()) {
                log.debug("Initialization completed for Kong Subscription Agent: " + environment.getName());
            }
        } catch (Exception e) {
            throw new APIManagementException("Error occurred while initializing Kong Subscription Agent", e);
        }
    }

    @Override
    public AgentOperationResult createSubscription(FederatedSubscriptionContext context, String selectedOption)
            throws APIManagementException {
        if (log.isDebugEnabled()) {
            log.debug("Creating Kong subscription for API: " + context.getApiName()
                    + ", Application: " + context.getApplicationName());
        }

        try {
            // Resolve Kong service ID from API reference artifact
            String serviceId = resolveServiceId(context.getApiReferenceArtifact());

            // Fetch key-auth plugin configuration to get header name and enabled methods
            KeyAuthPluginConfig pluginConfig = fetchKeyAuthPluginConfig(serviceId);

            // Generate consumer username using WSO2 pattern
            String consumerUsername = KongConstants.CONSUMER_NAME_PREFIX + context.getSubscriptionUuid();

            if (log.isDebugEnabled()) {
                log.debug("Creating consumer with username: " + consumerUsername + " for service: " + serviceId);
            }

            // Create consumer
            KongConsumer consumerRequest = new KongConsumer(consumerUsername, context.getSubscriptionUuid());
            KongConsumer consumer = apiGatewayClient.createConsumer(controlPlaneId, consumerRequest);

            if (consumer == null || consumer.getId() == null) {
                throw new APIManagementException("Failed to create Kong consumer");
            }

            // Create key-auth credential (Kong auto-generates the key)
            KongKeyAuth keyAuthRequest = new KongKeyAuth();
            KongKeyAuth keyAuth = apiGatewayClient.createKeyAuth(controlPlaneId, consumer.getId(), keyAuthRequest);

            if (keyAuth == null || keyAuth.getKey() == null) {
                throw new APIManagementException("Failed to create Kong key-auth credential");
            }

            // Parse multi-group selections from selectedOption
            // Format: {"acl-groups": {"id": "...", "name": "..."}, "consumer-groups": {"id": "...", "name": "..."}}
            JsonObject selections = null;
            if (selectedOption != null) {
                selections = JsonParser.parseString(selectedOption).getAsJsonObject();
            }

            // Handle ACL group assignment
            String aclGroup = null;
            List<String> aclGroups = detectAclGroups(serviceId);

            if (aclGroups != null && !aclGroups.isEmpty()) {
                if (selections != null && selections.has(KongConstants.OPTION_GROUP_ACL)) {
                    aclGroup = selections.getAsJsonObject(KongConstants.OPTION_GROUP_ACL)
                            .get("id").getAsString();
                } else if (aclGroups.size() == 1) {
                    aclGroup = aclGroups.get(0);
                } else {
                    throw new APIManagementException(
                            "ACL group selection required. This API has multiple access groups.");
                }

                KongAcl aclRequest = new KongAcl(aclGroup);
                apiGatewayClient.createAcl(controlPlaneId, consumer.getId(), aclRequest);
                if (log.isDebugEnabled()) {
                    log.debug("Added consumer to ACL group: " + aclGroup);
                }
            }

            // Handle Consumer Group assignment (rate limiting tier)
            String consumerGroupId = null;
            if (selections != null && selections.has(KongConstants.OPTION_GROUP_CONSUMER_GROUPS)) {
                consumerGroupId = selections.getAsJsonObject(KongConstants.OPTION_GROUP_CONSUMER_GROUPS)
                        .get("id").getAsString();
                try {
                    apiGatewayClient.addConsumerToGroup(controlPlaneId, consumerGroupId,
                            new KongConsumerGroupMembership(consumer.getId()));
                    if (log.isDebugEnabled()) {
                        log.debug("Added consumer to consumer group: " + consumerGroupId);
                    }
                } catch (Exception e) {
                    log.error("Failed to add consumer to consumer group: " + consumerGroupId, e);
                }
            }

            // Build credential - use the first key name from plugin config
            String headerName = pluginConfig.keyNames.get(0);
            OpaqueApiKeyCredential credBody = new OpaqueApiKeyCredential(headerName, keyAuth.getKey());

            FederatedCredential credential = new FederatedCredential();
            credential.setBody(credBody);
            credential.setExternalSubscriptionId(consumer.getUsername());
            credential.setValueRetrievable(true);
            credential.setMasked(false);

            // Build invocation instruction — prefer stored snapshot over live gateway call
            InvocationInstruction instruction = extractInvocationFromSnapshot(context);
            if (instruction == null) {
                instruction = getInvocationInstruction(context, pluginConfig);
            }
            String referenceArtifact = buildReferenceArtifact(
                    credential, instruction, consumer.getId(), keyAuth.getId(), serviceId, aclGroup,
                    consumerGroupId, selectedOption, pluginConfig);

            if (log.isDebugEnabled()) {
                log.debug("Subscription credential created successfully for: " + consumerUsername);
            }

            return AgentOperationResult.builder()
                    .credential(credential)
                    .instruction(instruction)
                    .referenceArtifact(referenceArtifact)
                    .externalSubscriptionId(consumer.getUsername())
                    .build();

        } catch (Exception e) {
            log.error("Error creating Kong subscription for: " + context.getSubscriptionUuid(), e);
            throw new APIManagementException("Failed to create subscription in Kong: " + e.getMessage(), e);
        }
    }

    @Override
    public AgentOperationResult regenerateCredential(FederatedSubscriptionContext context)
            throws APIManagementException {
        if (log.isDebugEnabled()) {
            log.debug("Regenerating credential for Kong consumer: " + context.getExternalSubscriptionId());
        }

        try {
            // Extract metadata from reference artifact
            String consumerId = extractConsumerIdFromArtifact(context);
            String oldKeyAuthId = extractKeyAuthIdFromArtifact(context);
            String serviceId = extractServiceIdFromArtifact(context);
            String aclGroup = extractAclGroupFromArtifact(context);
            String consumerGroupId = extractConsumerGroupIdFromArtifact(context);
            String selectedOption = extractSelectedOptionFromArtifact(context);
            KeyAuthPluginConfig pluginConfig = extractPluginConfigFromArtifact(context);

            // Delete old key-auth credential
            try {
                apiGatewayClient.deleteKeyAuth(controlPlaneId, consumerId, oldKeyAuthId);
                if (log.isDebugEnabled()) {
                    log.debug("Deleted old key-auth credential: " + oldKeyAuthId);
                }
            } catch (FeignException.NotFound e) {
                log.warn("Old key-auth credential not found, continuing with regeneration");
            }

            // Create new key-auth credential
            KongKeyAuth keyAuthRequest = new KongKeyAuth();
            KongKeyAuth keyAuth = apiGatewayClient.createKeyAuth(controlPlaneId, consumerId, keyAuthRequest);

            if (keyAuth == null || keyAuth.getKey() == null) {
                throw new APIManagementException("Failed to create new Kong key-auth credential");
            }

            // Build credential - use the first key name from stored plugin config
            String headerName = pluginConfig.keyNames.get(0);
            OpaqueApiKeyCredential credBody = new OpaqueApiKeyCredential(headerName, keyAuth.getKey());

            FederatedCredential credential = new FederatedCredential();
            credential.setBody(credBody);
            credential.setExternalSubscriptionId(context.getExternalSubscriptionId());
            credential.setValueRetrievable(true);
            credential.setMasked(false);

            // Build invocation instruction and reference artifact (preserving selectedOption and pluginConfig)
            InvocationInstruction instruction = getInvocationInstruction(context, pluginConfig);
            String referenceArtifact = buildReferenceArtifact(
                    credential, instruction, consumerId, keyAuth.getId(), serviceId, aclGroup,
                    consumerGroupId, selectedOption, pluginConfig);

            if (log.isDebugEnabled()) {
                log.debug("Credential regenerated successfully for: " + context.getExternalSubscriptionId());
            }

            return AgentOperationResult.builder()
                    .credential(credential)
                    .instruction(instruction)
                    .referenceArtifact(referenceArtifact)
                    .externalSubscriptionId(context.getExternalSubscriptionId())
                    .build();

        } catch (Exception e) {
            log.error("Error regenerating credential for consumer: " + context.getExternalSubscriptionId(), e);
            throw new APIManagementException("Failed to regenerate credential in Kong: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteSubscription(FederatedSubscriptionContext context) throws APIManagementException {
        String consumerId = extractConsumerIdFromArtifact(context);
        if (log.isDebugEnabled()) {
            log.debug("Deleting Kong consumer: " + consumerId);
        }

        try {
            // Check if consumer exists first (for idempotency)
            if (!subscriptionExists(context)) {
                log.warn("Consumer does not exist, skipping deletion: " + consumerId);
                return;
            }

            // Delete the consumer (cascades to key-auth and ACL)
            apiGatewayClient.deleteConsumer(controlPlaneId, consumerId);

            if (log.isDebugEnabled()) {
                log.debug("Successfully deleted consumer: " + consumerId);
            }

        } catch (FeignException.NotFound e) {
            // Idempotent - already deleted
            log.warn("Consumer not found during deletion: " + consumerId);
        } catch (Exception e) {
            log.error("Error deleting Kong consumer: " + consumerId, e);
            throw new APIManagementException("Failed to delete subscription in Kong: " + e.getMessage(), e);
        }
    }

    @Override
    public AgentOperationResult retrieveSubscription(FederatedSubscriptionContext context,
            boolean includeFullCredentials) throws APIManagementException {

        FederatedCredential credential;
        if (includeFullCredentials) {
            // Verify gateway supports retrieval
            FederatedCredential maskedCred = extractCredentialFromReferenceArtifact(context);
            if (maskedCred == null || !maskedCred.isValueRetrievable()) {
                throw new APIManagementException("This gateway does not support credential retrieval");
            }
            credential = retrieveFullCredential(context);
        } else {
            credential = extractCredentialFromReferenceArtifact(context);
            credential.setExternalSubscriptionId(context.getExternalSubscriptionId());
            credential.setMasked(true);
        }

        // Extract stored plugin config for invocation instruction — prefer snapshot over live call
        InvocationInstruction instruction = extractInvocationFromSnapshot(context);
        if (instruction == null) {
            KeyAuthPluginConfig pluginConfig = extractPluginConfigFromArtifact(context);
            instruction = getInvocationInstruction(context, pluginConfig);
        }

        return AgentOperationResult.builder()
                .credential(credential)
                .instruction(instruction)
                .build();
    }

    @Override
    public boolean subscriptionExists(FederatedSubscriptionContext context) throws APIManagementException {
        String consumerId = extractConsumerIdFromArtifact(context);
        try {
            KongConsumer consumer = apiGatewayClient.getConsumer(controlPlaneId, consumerId);
            return consumer != null;
        } catch (FeignException.NotFound e) {
            return false;
        } catch (Exception e) {
            log.error("Error checking Kong consumer existence: " + consumerId, e);
            throw new APIManagementException("Failed to check subscription existence: " + e.getMessage(), e);
        }
    }

    public SubscriptionSupportInfo getSubscriptionSupportInfo(FederatedSubscriptionContext context)
            throws APIManagementException {
        try {
            String serviceId = resolveServiceId(context.getApiReferenceArtifact());
            
            // Single API call to list all plugins
            PagedResponse<KongPlugin> pluginsResp = apiGatewayClient.listPluginsByServiceId(
                    controlPlaneId, serviceId, KongConstants.DEFAULT_PLUGIN_LIST_LIMIT);

            boolean hasKeyAuth = false;
            boolean hasAcl = false;
            List<String> aclGroups = null;

            if (pluginsResp != null && pluginsResp.getData() != null) {
                for (KongPlugin plugin : pluginsResp.getData()) {
                    if (Boolean.TRUE.equals(plugin.getEnabled())) {
                        // Check for key-auth plugin
                        if (KongConstants.KONG_KEY_AUTH_PLUGIN_TYPE.equals(plugin.getName())) {
                            hasKeyAuth = true;
                        }
                        
                        // Check for ACL plugin and extract groups
                        if (KongConstants.KONG_ACL_PLUGIN_TYPE.equals(plugin.getName())) {
                            hasAcl = true;
                            JsonObject config = plugin.getConfig();
                            if (config != null && config.has("allow") && config.get("allow").isJsonArray()) {
                                JsonArray allowArray = config.get("allow").getAsJsonArray();
                                aclGroups = new ArrayList<>();
                                for (int i = 0; i < allowArray.size(); i++) {
                                    aclGroups.add(allowArray.get(i).getAsString());
                                }
                                if (log.isDebugEnabled()) {
                                    log.debug("Detected ACL groups: " + aclGroups);
                                }
                            }
                        }
                    }
                }
            }

            // Decision logic
            if (!hasKeyAuth && !hasAcl) {
                // No key-auth, no ACL → OPEN
                if (log.isDebugEnabled()) {
                    log.debug("API " + context.getApiName() + " has no key-auth or ACL - OPEN");
                }
                return new SubscriptionSupportInfo.Builder()
                        .status(SubscriptionSupportInfo.SubscriptionStatus.OPEN)
                        .supportedAuthTypes(new String[]{})
                        .subscriptionOptions(null)
                        .build();
            }

            if (hasAcl && !hasKeyAuth) {
                // ACL without key-auth → OPEN (Kong doesn't validate credentials, effectively open)
                if (log.isDebugEnabled()) {
                    log.debug("API " + context.getApiName() + " has ACL but no key-auth - treating as OPEN");
                }
                return new SubscriptionSupportInfo.Builder()
                        .status(SubscriptionSupportInfo.SubscriptionStatus.OPEN)
                        .supportedAuthTypes(new String[]{})
                        .subscriptionOptions(null)
                        .build();
            }

            // key-auth enabled → SECURED
            if (log.isDebugEnabled()) {
                log.debug("API " + context.getApiName() + " has key-auth enabled - SECURED");
            }

            // Build option groups: ACL groups + consumer groups
            FederatedSubscriptionOptions options = buildSubscriptionOptions(serviceId, aclGroups);

            return new SubscriptionSupportInfo.Builder()
                    .status(SubscriptionSupportInfo.SubscriptionStatus.SECURED)
                    .supportedAuthTypes(new String[]{"opaque-api-key"})
                    .subscriptionOptions(options)
                    .build();

        } catch (Exception e) {
            log.error("Error checking subscription support for API: " + context.getApiName(), e);
            throw new APIManagementException("Failed to check subscription support: " + e.getMessage(), e);
        }
    }

    @Override
    public String getGatewayType() {
        return GATEWAY_TYPE;
    }

    @Override
    public SubscriptionSupportInfo getFederationConfigProvider(FederatedSubscriptionContext context)
            throws APIManagementException {
        SubscriptionSupportInfo info = getSubscriptionSupportInfo(context);
        if (info != null && info.getStatus() == SubscriptionSupportInfo.SubscriptionStatus.SECURED) {
            try {
                String serviceId = resolveServiceId(context.getApiReferenceArtifact());
                KeyAuthPluginConfig pluginConfig = fetchKeyAuthPluginConfig(serviceId);
                info.setInvocationTemplate(getInvocationInstruction(context, pluginConfig));
            } catch (Exception e) {
                log.warn("Failed to add invocation template to federation config for API: " + context.getApiName(), e);
            }
        }
        return info;
    }

    private InvocationInstruction extractInvocationFromSnapshot(FederatedSubscriptionContext context) {
        SubscriptionSupportInfo snapshot = context.getFederationConfigSnapshot();
        if (snapshot == null) {
            return null;
        }
        return snapshot.getInvocationTemplate();
    }

    // Private helper methods

    private String resolveServiceId(String apiRefArtifact) throws APIManagementException {
        try {
            JsonObject refArtifact = JsonParser.parseString(apiRefArtifact).getAsJsonObject();
            String apiUuid = refArtifact.get("uuid").getAsString();

            // List API implementations to find API -> Service mapping
            KongListResponse<KongAPIImplementation> impls = apiGatewayClient.listAPIImplementations(
                    KongConstants.DEFAULT_API_LIST_LIMIT);

            if (impls != null && impls.getData() != null) {
                for (KongAPIImplementation impl : impls.getData()) {
                    if (apiUuid.equals(impl.getApiId()) && impl.getService() != null) {
                        return impl.getService().getId();
                    }
                }
            }

            // Fallback: uuid might be the service ID itself (standalone services)
            if (log.isDebugEnabled()) {
                log.debug("No API implementation found, using uuid as service ID: " + apiUuid);
            }
            return apiUuid;

        } catch (Exception e) {
            log.error("Error resolving Kong service ID from reference artifact", e);
            throw new APIManagementException("Failed to resolve Kong service ID: " + e.getMessage(), e);
        }
    }

    private List<String> detectAclGroups(String serviceId) {
        try {
            PagedResponse<KongPlugin> pluginsResp = apiGatewayClient.listPluginsByServiceId(
                    controlPlaneId, serviceId, KongConstants.DEFAULT_PLUGIN_LIST_LIMIT);

            if (pluginsResp != null && pluginsResp.getData() != null) {
                for (KongPlugin plugin : pluginsResp.getData()) {
                    if (KongConstants.KONG_ACL_PLUGIN_TYPE.equals(plugin.getName())
                            && Boolean.TRUE.equals(plugin.getEnabled())) {
                        JsonObject config = plugin.getConfig();
                        if (config != null && config.has("allow") && config.get("allow").isJsonArray()) {
                            JsonArray allowArray = config.get("allow").getAsJsonArray();
                            List<String> groups = new ArrayList<>();
                            for (int i = 0; i < allowArray.size(); i++) {
                                groups.add(allowArray.get(i).getAsString());
                            }
                            if (log.isDebugEnabled()) {
                                log.debug("Detected ACL groups: " + groups);
                            }
                            return groups;
                        }
                    }
                }
            }

            return null; // No ACL plugin found
        } catch (Exception e) {
            log.warn("Error detecting ACL groups for service: " + serviceId, e);
            return null;
        }
    }

    /**
     * Builds subscription options as an {@link OptionGroups} body containing:
     * <ul>
     *   <li>ACL groups — access scoping (required by default when present)</li>
     *   <li>Consumer groups — rate limiting tiers (required by default when present)</li>
     * </ul>
     * Returns {@code null} if no option groups are available.
     */
    private FederatedSubscriptionOptions buildSubscriptionOptions(String serviceId, List<String> aclGroups) {
        List<OptionGroup> groups = new ArrayList<>();

        // ACL groups group
        if (aclGroups != null && !aclGroups.isEmpty()) {
            List<OptionItem> aclItems = new ArrayList<>();
            for (String group : aclGroups) {
                aclItems.add(new OptionItem(group, group, null));
            }
            // Required by default — publisher can relax this via curation
            groups.add(new OptionGroup(KongConstants.OPTION_GROUP_ACL, "Access Group", true, aclItems));
            if (log.isDebugEnabled()) {
                log.debug("Exposing " + aclGroups.size() + " ACL groups as subscription option group");
            }
        }

        // Consumer groups (enriched with rate-limit details)
        try {
            PagedResponse<KongConsumerGroup> cgResp = apiGatewayClient.listConsumerGroups(
                    controlPlaneId, KongConstants.DEFAULT_CONSUMER_GROUP_LIST_LIMIT);
            if (cgResp != null && cgResp.getData() != null && !cgResp.getData().isEmpty()) {
                List<OptionItem> cgItems = new ArrayList<>();
                for (KongConsumerGroup cg : cgResp.getData()) {
                    cgItems.add(buildConsumerGroupOptionItem(cg));
                }
                // Required by default — publisher can make optional via curation
                groups.add(new OptionGroup(KongConstants.OPTION_GROUP_CONSUMER_GROUPS,
                        "Consumer Group", true, cgItems));
                if (log.isDebugEnabled()) {
                    log.debug("Exposing " + cgItems.size() + " consumer groups as subscription option group");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch Kong consumer groups for subscription options, skipping", e);
        }

        if (groups.isEmpty()) {
            return null;
        }

        OptionGroups body = new OptionGroups(groups);
        FederatedSubscriptionOptions options = new FederatedSubscriptionOptions();
        options.setBody(body);
        return options;
    }

    /**
     * Builds an OptionItem for a consumer group, enriched with rate-limit details if available.
     * Fetches the rate-limiting plugin applied to the consumer group and populates
     * the description and properties fields of the OptionItem.
     */
    private OptionItem buildConsumerGroupOptionItem(KongConsumerGroup cg) {
        KongPlugin rateLimitPlugin = fetchConsumerGroupRateLimitPlugin(cg.getId());
        String description = rateLimitPlugin != null
                ? KongAPIUtil.formatRateLimitDescription(rateLimitPlugin) : null;
        return new OptionItem(cg.getId(), cg.getName(), description);
    }

    /**
     * Fetches the rate-limiting plugin applied directly to a consumer group.
     * Returns the first enabled rate-limiting plugin found, or null if none exists.
     */
    private KongPlugin fetchConsumerGroupRateLimitPlugin(String groupId) {
        try {
            PagedResponse<KongPlugin> pluginsResp = apiGatewayClient.listPluginsByConsumerGroupId(
                    controlPlaneId, groupId, KongConstants.DEFAULT_PLUGIN_LIST_LIMIT);
            if (pluginsResp != null && pluginsResp.getData() != null) {
                for (KongPlugin plugin : pluginsResp.getData()) {
                    if (Boolean.TRUE.equals(plugin.getEnabled()) && isRateLimitPlugin(plugin)) {
                        return plugin;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch rate-limit plugins for consumer group: " + groupId, e);
        }
        return null;
    }

    private static boolean isRateLimitPlugin(KongPlugin plugin) {
        String name = plugin.getName();
        return KongConstants.KONG_RATELIMIT_ADVANCED_PLUGIN_TYPE.equals(name)
                || KongConstants.KONG_RATELIMIT_PLUGIN_TYPE.equals(name);
    }

    /**
     * Builds dynamic invocation instruction based on key-auth plugin configuration.
     * Uses the actual enabled methods (header/query/body) and key names from Kong.
     */
    private InvocationInstruction getInvocationInstruction(FederatedSubscriptionContext context,
                                                           KeyAuthPluginConfig pluginConfig) {
        String baseUrl = proxyUrl;
        String basePath = context.getApiContext() != null ? context.getApiContext() : "";

        ApiKeyInvocation invBody = new ApiKeyInvocation();
        invBody.setBaseUrl(baseUrl);
        invBody.setBasePath(basePath);

        // Use first key name for all methods (Kong supports multiple names but uses same name across methods)
        String primaryKeyName = pluginConfig.keyNames.get(0);

        // Header method
        if (pluginConfig.keyInHeader) {
            invBody.setHeaderEnabled(true);
            invBody.setHeaderName(primaryKeyName);
            String curlExampleHeader = String.format(
                    "curl -X GET \"%s%s{path}\" -H \"%s: {YOUR_API_KEY}\"",
                    baseUrl, basePath, primaryKeyName);
            invBody.setCurlExampleHeader(curlExampleHeader);
        }

        // Query parameter method
        if (pluginConfig.keyInQuery) {
            invBody.setQueryParamEnabled(true);
            invBody.setQueryParamName(primaryKeyName);
            String curlExampleQuery = String.format(
                    "curl -X GET \"%s%s{path}?%s={YOUR_API_KEY}\"",
                    baseUrl, basePath, primaryKeyName);
            invBody.setCurlExampleQuery(curlExampleQuery);
        }

        // Body method
        if (pluginConfig.keyInBody) {
            invBody.setBodyEnabled(true);
            invBody.setBodyParamName(primaryKeyName);
        }

        // Add notes about available methods
        StringBuilder notes = new StringBuilder("API key can be sent via: ");
        List<String> methods = new ArrayList<>();
        if (pluginConfig.keyInHeader) {
            methods.add("HTTP header '" + primaryKeyName + "'");
        }
        if (pluginConfig.keyInQuery) {
            methods.add("query parameter '" + primaryKeyName + "'");
        }
        if (pluginConfig.keyInBody) {
            methods.add("request body field '" + primaryKeyName + "'");
        }
        invBody.setNotes(notes.append(String.join(", ", methods)).toString());

        InvocationInstruction instruction = new InvocationInstruction();
        instruction.setBody(invBody);

        return instruction;
    }

    private String buildReferenceArtifact(FederatedCredential credential, InvocationInstruction instruction,
            String consumerId, String keyAuthId, String serviceId, String aclGroup, String consumerGroupId,
            String selectedOption, KeyAuthPluginConfig pluginConfig) throws APIManagementException {
        try {
            JsonObject artifact = new JsonObject();

            // Store masked credential
            if (credential != null && credential.getBody() != null) {
                try {
                    OpaqueApiKeyCredential maskedCredBody =
                            (OpaqueApiKeyCredential) credential.getBody().masked();

                    JsonObject credJson = new JsonObject();
                    credJson.addProperty("schemaName", maskedCredBody.getSchemaName());
                    credJson.addProperty("body", maskedCredBody.toJson());
                    credJson.addProperty("isValueRetrievable", credential.isValueRetrievable());
                    artifact.add("credential", credJson);
                } catch (Exception e) {
                    log.warn("Failed to mask credential body", e);
                }
            }

            // Store invocation instruction
            if (instruction != null && instruction.getBody() != null) {
                JsonObject invJson = new JsonObject();
                invJson.addProperty("schemaName", instruction.getSchemaName());
                invJson.addProperty("body", instruction.getBodyAsJson());
                artifact.add("invocationInstruction", invJson);
            }

            // Store Kong-specific metadata
            artifact.addProperty("consumerId", consumerId);
            artifact.addProperty("keyAuthId", keyAuthId);
            artifact.addProperty("serviceId", serviceId);
            if (aclGroup != null) {
                artifact.addProperty("aclGroup", aclGroup);
            }
            if (consumerGroupId != null) {
                artifact.addProperty("consumerGroupId", consumerGroupId);
            }
            if (selectedOption != null) {
                artifact.addProperty("selectedOption", selectedOption);
            }

            // Store plugin configuration for future retrieval/regeneration
            if (pluginConfig != null) {
                JsonObject pluginConfigJson = new JsonObject();
                JsonArray keyNamesArray = new JsonArray();
                for (String keyName : pluginConfig.keyNames) {
                    keyNamesArray.add(keyName);
                }
                pluginConfigJson.add("keyNames", keyNamesArray);
                pluginConfigJson.addProperty("keyInHeader", pluginConfig.keyInHeader);
                pluginConfigJson.addProperty("keyInQuery", pluginConfig.keyInQuery);
                pluginConfigJson.addProperty("keyInBody", pluginConfig.keyInBody);
                artifact.add("pluginConfig", pluginConfigJson);
            }

            return artifact.toString();

        } catch (Exception e) {
            throw new APIManagementException("Failed to build reference artifact: " + e.getMessage(), e);
        }
    }

    private FederatedCredential extractCredentialFromReferenceArtifact(FederatedSubscriptionContext context)
            throws APIManagementException {
        FederatedCredential credential = new FederatedCredential();
        String subscriptionReferenceArtifact = context.getCredentialReferenceArtifact();

        if (subscriptionReferenceArtifact == null || subscriptionReferenceArtifact.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("No subscription reference artifact found for API: " + context.getApiName());
            }
            return credential;
        }

        try {
            JsonObject artifact = JsonParser.parseString(subscriptionReferenceArtifact).getAsJsonObject();
            JsonObject credJson = artifact.has("credential") ? artifact.getAsJsonObject("credential") : null;

            if (credJson != null) {
                if (credJson.has("body")) {
                    String bodyJson = credJson.get("body").getAsString();
                    OpaqueApiKeyCredential credBody = OpaqueApiKeyCredential.fromJson(bodyJson);
                    credential.setBody(credBody);
                }
                if (credJson.has("isValueRetrievable")) {
                    credential.setValueRetrievable(credJson.get("isValueRetrievable").getAsBoolean());
                }
                credential.setMasked(true);

                if (log.isDebugEnabled()) {
                    log.debug("Extracted credential from reference artifact for API: " + context.getApiName());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse subscription reference artifact for API: " + context.getApiName(), e);
        }

        return credential;
    }

    private FederatedCredential retrieveFullCredential(FederatedSubscriptionContext context)
            throws APIManagementException {
        String consumerId = extractConsumerIdFromArtifact(context);
        KeyAuthPluginConfig pluginConfig = extractPluginConfigFromArtifact(context);

        if (log.isDebugEnabled()) {
            log.debug("Retrieving full credential for Kong consumer: " + consumerId);
        }

        try {
            // List key-auth credentials for the consumer
            PagedResponse<KongKeyAuth> keyAuthResp = apiGatewayClient.listKeyAuth(
                    controlPlaneId, consumerId, 1);

            if (keyAuthResp == null || keyAuthResp.getData() == null || keyAuthResp.getData().isEmpty()) {
                throw new APIManagementException("No key-auth credentials found for consumer: " + consumerId);
            }

            KongKeyAuth keyAuth = keyAuthResp.getData().get(0);

            // Build credential with full key value - use the first key name from stored plugin config
            String headerName = pluginConfig.keyNames.get(0);
            OpaqueApiKeyCredential credBody = new OpaqueApiKeyCredential(headerName, keyAuth.getKey());

            FederatedCredential credential = new FederatedCredential();
            credential.setBody(credBody);
            credential.setExternalSubscriptionId(context.getExternalSubscriptionId());
            credential.setValueRetrievable(true);
            credential.setMasked(false);

            if (log.isDebugEnabled()) {
                log.debug("Retrieved full credential for consumer: " + consumerId);
            }

            return credential;

        } catch (Exception e) {
            log.error("Error retrieving credential for consumer: " + consumerId, e);
            throw new APIManagementException("Failed to retrieve credential from Kong: " + e.getMessage(), e);
        }
    }

    private String extractConsumerIdFromArtifact(FederatedSubscriptionContext context) throws APIManagementException {
        try {
            JsonObject artifact = JsonParser.parseString(context.getCredentialReferenceArtifact()).getAsJsonObject();
            return artifact.get("consumerId").getAsString();
        } catch (Exception e) {
            throw new APIManagementException("Failed to extract consumer ID from reference artifact", e);
        }
    }

    private String extractKeyAuthIdFromArtifact(FederatedSubscriptionContext context) throws APIManagementException {
        try {
            JsonObject artifact = JsonParser.parseString(context.getCredentialReferenceArtifact()).getAsJsonObject();
            return artifact.get("keyAuthId").getAsString();
        } catch (Exception e) {
            throw new APIManagementException("Failed to extract key-auth ID from reference artifact", e);
        }
    }

    private String extractServiceIdFromArtifact(FederatedSubscriptionContext context) throws APIManagementException {
        try {
            JsonObject artifact = JsonParser.parseString(context.getCredentialReferenceArtifact()).getAsJsonObject();
            return artifact.get("serviceId").getAsString();
        } catch (Exception e) {
            throw new APIManagementException("Failed to extract service ID from reference artifact", e);
        }
    }

    private String extractAclGroupFromArtifact(FederatedSubscriptionContext context) throws APIManagementException {
        try {
            JsonObject artifact = JsonParser.parseString(context.getCredentialReferenceArtifact()).getAsJsonObject();
            return artifact.has("aclGroup") ? artifact.get("aclGroup").getAsString() : null;
        } catch (Exception e) {
            throw new APIManagementException("Failed to extract ACL group from reference artifact", e);
        }
    }

    private String extractConsumerGroupIdFromArtifact(FederatedSubscriptionContext context) {
        try {
            JsonObject artifact = JsonParser.parseString(context.getCredentialReferenceArtifact()).getAsJsonObject();
            return artifact.has("consumerGroupId") ? artifact.get("consumerGroupId").getAsString() : null;
        } catch (Exception e) {
            log.warn("Failed to extract consumer group ID from reference artifact", e);
            return null;
        }
    }

    private String extractSelectedOptionFromArtifact(FederatedSubscriptionContext context) {
        try {
            JsonObject artifact = JsonParser.parseString(context.getCredentialReferenceArtifact()).getAsJsonObject();
            return artifact.has("selectedOption") ? artifact.get("selectedOption").getAsString() : null;
        } catch (Exception e) {
            log.warn("Failed to extract selectedOption from reference artifact", e);
            return null;
        }
    }

    /**
     * Fetches the key-auth plugin configuration from Kong for the given service.
     * Returns the actual enabled methods and key names configured on the gateway.
     */
    private KeyAuthPluginConfig fetchKeyAuthPluginConfig(String serviceId) throws APIManagementException {
        try {
            PagedResponse<KongPlugin> pluginsResp = apiGatewayClient.listPluginsByServiceId(
                    controlPlaneId, serviceId, KongConstants.DEFAULT_PLUGIN_LIST_LIMIT);

            if (pluginsResp != null && pluginsResp.getData() != null) {
                for (KongPlugin plugin : pluginsResp.getData()) {
                    if (KongConstants.KONG_KEY_AUTH_PLUGIN_TYPE.equals(plugin.getName())
                            && Boolean.TRUE.equals(plugin.getEnabled())) {
                        JsonObject config = plugin.getConfig();
                        
                        // Extract key_names (defaults to ["apikey"])
                        List<String> keyNames = new ArrayList<>();
                        if (config != null && config.has("key_names") && config.get("key_names").isJsonArray()) {
                            JsonArray keyNamesArray = config.get("key_names").getAsJsonArray();
                            for (int i = 0; i < keyNamesArray.size(); i++) {
                                keyNames.add(keyNamesArray.get(i).getAsString());
                            }
                        }
                        if (keyNames.isEmpty()) {
                            keyNames.add(KongConstants.DEFAULT_KEY_AUTH_HEADER); // Kong's default
                        }

                        // Extract enabled methods (defaults: header=true, query=false, body=false)
                        boolean keyInHeader = true; // Kong default
                        boolean keyInQuery = false;
                        boolean keyInBody = false;

                        if (config != null) {
                            if (config.has("key_in_header") && config.get("key_in_header").isJsonPrimitive()) {
                                keyInHeader = config.get("key_in_header").getAsBoolean();
                            }
                            if (config.has("key_in_query") && config.get("key_in_query").isJsonPrimitive()) {
                                keyInQuery = config.get("key_in_query").getAsBoolean();
                            }
                            if (config.has("key_in_body") && config.get("key_in_body").isJsonPrimitive()) {
                                keyInBody = config.get("key_in_body").getAsBoolean();
                            }
                        }

                        if (log.isDebugEnabled()) {
                            log.debug("Fetched key-auth plugin config - keyNames: " + keyNames +
                                    ", header: " + keyInHeader + ", query: " + keyInQuery + ", body: " + keyInBody);
                        }

                        return new KeyAuthPluginConfig(keyNames, keyInHeader, keyInQuery, keyInBody);
                    }
                }
            }

            // No key-auth plugin found - shouldn't happen if called correctly, but provide defaults
            log.warn("No key-auth plugin found for service: " + serviceId + ", using defaults");
            List<String> defaultKeyNames = new ArrayList<>();
            defaultKeyNames.add(KongConstants.DEFAULT_KEY_AUTH_HEADER);
            return new KeyAuthPluginConfig(defaultKeyNames, true, false, false);

        } catch (Exception e) {
            log.error("Error fetching key-auth plugin config for service: " + serviceId, e);
            throw new APIManagementException("Failed to fetch key-auth plugin configuration: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts the stored plugin configuration from the reference artifact.
     */
    private KeyAuthPluginConfig extractPluginConfigFromArtifact(FederatedSubscriptionContext context)
            throws APIManagementException {
        try {
            JsonObject artifact = JsonParser.parseString(context.getCredentialReferenceArtifact()).getAsJsonObject();
            
            if (!artifact.has("pluginConfig")) {
                // Fallback for old artifacts without stored config - use defaults
                log.warn("No plugin config in reference artifact, using defaults");
                List<String> defaultKeyNames = new ArrayList<>();
                defaultKeyNames.add(KongConstants.DEFAULT_KEY_AUTH_HEADER);
                return new KeyAuthPluginConfig(defaultKeyNames, true, false, false);
            }

            JsonObject pluginConfigJson = artifact.getAsJsonObject("pluginConfig");
            
            // Extract key names
            List<String> keyNames = new ArrayList<>();
            if (pluginConfigJson.has("keyNames") && pluginConfigJson.get("keyNames").isJsonArray()) {
                JsonArray keyNamesArray = pluginConfigJson.getAsJsonArray("keyNames");
                for (int i = 0; i < keyNamesArray.size(); i++) {
                    keyNames.add(keyNamesArray.get(i).getAsString());
                }
            }
            if (keyNames.isEmpty()) {
                keyNames.add(KongConstants.DEFAULT_KEY_AUTH_HEADER);
            }

            // Extract enabled methods
            boolean keyInHeader = pluginConfigJson.has("keyInHeader") 
                    ? pluginConfigJson.get("keyInHeader").getAsBoolean() : true;
            boolean keyInQuery = pluginConfigJson.has("keyInQuery")
                    ? pluginConfigJson.get("keyInQuery").getAsBoolean() : false;
            boolean keyInBody = pluginConfigJson.has("keyInBody")
                    ? pluginConfigJson.get("keyInBody").getAsBoolean() : false;

            return new KeyAuthPluginConfig(keyNames, keyInHeader, keyInQuery, keyInBody);

        } catch (Exception e) {
            throw new APIManagementException("Failed to extract plugin config from reference artifact", e);
        }
    }

    /**
     * Internal class to hold key-auth plugin configuration.
     */
    private static class KeyAuthPluginConfig {
        final List<String> keyNames;
        final boolean keyInHeader;
        final boolean keyInQuery;
        final boolean keyInBody;

        KeyAuthPluginConfig(List<String> keyNames, boolean keyInHeader, boolean keyInQuery, boolean keyInBody) {
            this.keyNames = keyNames;
            this.keyInHeader = keyInHeader;
            this.keyInQuery = keyInQuery;
            this.keyInBody = keyInBody;
        }
    }
}
