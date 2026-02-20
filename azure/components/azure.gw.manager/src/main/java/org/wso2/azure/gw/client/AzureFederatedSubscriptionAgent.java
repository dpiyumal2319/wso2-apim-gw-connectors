package org.wso2.azure.gw.client;


import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.resourcemanager.apimanagement.ApiManagementManager;
import com.azure.resourcemanager.apimanagement.models.ApiContract;
import com.azure.resourcemanager.apimanagement.models.SubscriptionContract;
import com.azure.resourcemanager.apimanagement.models.SubscriptionCreateParameters;
import com.azure.resourcemanager.apimanagement.models.SubscriptionKeysContract;
import com.azure.resourcemanager.apimanagement.models.SubscriptionState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.FederatedSubscriptionAgent;
import org.wso2.carbon.apimgt.api.model.AgentOperationResult;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.FederatedCredential;
import org.wso2.carbon.apimgt.api.model.FederatedSubscriptionContext;
import org.wso2.carbon.apimgt.api.model.InvocationInstruction;
import org.wso2.carbon.apimgt.api.model.SubscriptionSupportInfo;
import org.wso2.carbon.apimgt.api.model.schema.credential.PrimarySecondaryKeyPairCredential;
import org.wso2.carbon.apimgt.api.model.schema.invocation.ApiKeyInvocation;


import java.time.format.DateTimeFormatter;

/**
 * Azure API Management Federated Subscription Agent.
 * <p>
 * Implements subscription management for Azure APIM following the Universal
 * Gateway architecture:
 * - Application → Azure User (skipped for subscription operations, assumed
 * pre-created)
 * - API → Azure API (pre-deployed)
 * - Subscription → Azure Subscription (API-scoped, not product-scoped for v1)
 * </p>
 * <p>
 * Key Design Decisions:
 * - Uses API-scoped subscriptions (not Product-scoped) for simplicity in v1
 * - Returns Primary key as the credential (Secondary available for rotation)
 * - Azure generates keys, WSO2 retrieves and displays them
 * - Subscription ID follows pattern: wso2_{org}_{appUuid}_{apiUuid}_{envId}
 * </p>
 */
public class AzureFederatedSubscriptionAgent implements FederatedSubscriptionAgent {

    private static final Log log = LogFactory.getLog(AzureFederatedSubscriptionAgent.class);
    private static final String GATEWAY_TYPE = "Azure";
    private static final String CREDENTIAL_TYPE = "primary-secondary-key-pair";
    private static final String HEADER_NAME = "Ocp-Apim-Subscription-Key";
    private static final String QUERY_PARAM_NAME = "subscription-key";

    private String resourceGroup;
    private String serviceName;
    private String hostname;
    private ApiManagementManager manager;

    @Override
    public void init(Environment environment, String organization) throws APIManagementException {
        if (log.isDebugEnabled()) {
            log.debug("Initializing Azure Subscription Agent for Environment: " + environment.getName()
                    + " in Organization: " + organization);
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
            hostname = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_HOSTNAME);

            if (tenantId == null || clientId == null || clientSecret == null || subscriptionId == null
                    || resourceGroup == null || serviceName == null) {
                throw new APIManagementException("Missing required Azure environment configurations");
            }

            // Default hostname if not provided
            if (hostname == null || hostname.isEmpty()) {
                hostname = "azure-api.net";
            }

            if (log.isDebugEnabled()) {
                log.debug("Initialization completed for Azure Subscription Agent: " + environment.getName());
            }
        } catch (Exception e) {
            throw new APIManagementException("Error occurred while initializing Azure Subscription Agent", e);
        }
    }

    @Override
    public AgentOperationResult createSubscription(FederatedSubscriptionContext context, String selectedOption)
            throws APIManagementException {
        if (log.isDebugEnabled()) {
            log.debug("Creating Azure subscription for API: " + context.getApiName()
                    + ", Application: " + context.getApplicationName());
        }

        try {
            // Extract Azure API ID from reference artifact
            String azureApiId = extractAzureApiIdFromReferenceArtifact(context.getApiReferenceArtifact());

            // Generate subscription name using WSO2 pattern
            String subscriptionName = generateSubscriptionName(context);
            String displayName = generateDisplayName(context);

            // Build the API scope - Azure subscriptions are scoped to specific APIs
            String apiScope = buildApiScope(azureApiId);

            if (log.isDebugEnabled()) {
                log.debug("Creating subscription with name: " + subscriptionName + ", scope: " + apiScope);
            }

            SubscriptionCreateParameters parameters = new SubscriptionCreateParameters()
                    .withScope(apiScope)
                    .withDisplayName(displayName)
                    .withState(SubscriptionState.ACTIVE)
                    .withAllowTracing(false);

            // Create the subscription in Azure APIM
            SubscriptionContract subscription = manager.subscriptions()
                    .createOrUpdate(resourceGroup, serviceName, subscriptionName, parameters);


            if (log.isDebugEnabled()) {
                log.debug("Subscription created successfully: " + subscription.name());
            }

            // Retrieve the subscription keys
            SubscriptionKeysContract keys = manager.subscriptions()
                    .listSecrets(resourceGroup, serviceName, subscription.name());

            // Build typed credential body (schema name on envelope, not in body)
            String createdTime = subscription.createdDate() != null 
                ? subscription.createdDate().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) 
                : null;
            
            if (keys == null || keys.primaryKey() == null || keys.secondaryKey() == null) {
                throw new APIManagementException("Failed to retrieve subscription keys from Azure");
            }

            PrimarySecondaryKeyPairCredential credBody = new PrimarySecondaryKeyPairCredential(
                HEADER_NAME,
                QUERY_PARAM_NAME,
                keys.primaryKey(),
                keys.secondaryKey(),
                createdTime
            );

            FederatedCredential credential = new FederatedCredential();
            credential.setBody(credBody);
            credential.setExternalSubscriptionId(subscription.name());
            credential.setValueRetrievable(true);
            credential.setMasked(false);

            // Build invocation instruction and reference artifact — prefer snapshot over live gateway call
            InvocationInstruction instruction = extractInvocationFromSnapshot(context);
            if (instruction == null) {
                instruction = getInvocationInstruction(context);
            }
            String referenceArtifact = buildReferenceArtifact(credential, instruction);

            if (log.isDebugEnabled()) {
                log.debug("Subscription credential created successfully for: " + subscriptionName);
            }

            return AgentOperationResult.builder()
                    .credential(credential)
                    .instruction(instruction)
                    .referenceArtifact(referenceArtifact)
                    .externalSubscriptionId(subscription.name())
                    .build();

        } catch (Exception e) {
            log.error("Error creating Azure subscription for: " + context.getSubscriptionUuid(), e);
            throw new APIManagementException("Failed to create subscription in Azure APIM: " + e.getMessage(), e);
        }
    }

    @Override
    public AgentOperationResult regenerateCredential(FederatedSubscriptionContext context)
            throws APIManagementException {
        String externalSubscriptionId = context.getExternalSubscriptionId();
        
        if (log.isDebugEnabled()) {
            log.debug("Regenerating credential for Azure subscription: " + externalSubscriptionId);
        }

        try {
            // Verify subscription exists
            SubscriptionContract subscription = manager.subscriptions()
                    .get(resourceGroup, serviceName, externalSubscriptionId);

            if (subscription == null) {
                throw new APIManagementException("Subscription not found: " + externalSubscriptionId);
            }

            // Regenerate both primary and secondary keys using Azure SDK's built-in methods
            manager.subscriptions().regeneratePrimaryKey(resourceGroup, serviceName, externalSubscriptionId);
            manager.subscriptions().regenerateSecondaryKey(resourceGroup, serviceName, externalSubscriptionId);

            if (log.isDebugEnabled()) {
                log.debug("Keys regenerated successfully for subscription: " + externalSubscriptionId);
            }

            // Retrieve the new keys
            SubscriptionKeysContract keys = manager.subscriptions()
                    .listSecrets(resourceGroup, serviceName, externalSubscriptionId);

            // Build typed credential body
            String createdTime = subscription.createdDate() != null 
                ? subscription.createdDate().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) 
                : null;

            if (keys == null || keys.primaryKey() == null || keys.secondaryKey() == null) {
                throw new APIManagementException("Failed to retrieve regenerated subscription keys from Azure");
            }

            PrimarySecondaryKeyPairCredential credBody = new PrimarySecondaryKeyPairCredential(
                HEADER_NAME,
                QUERY_PARAM_NAME,
                keys.primaryKey(),
                keys.secondaryKey(),
                createdTime
            );

            FederatedCredential credential = new FederatedCredential();
            credential.setBody(credBody);
            credential.setExternalSubscriptionId(externalSubscriptionId);
            credential.setValueRetrievable(true);
            credential.setMasked(false);

            // Build invocation instruction and reference artifact — prefer snapshot over live gateway call
            InvocationInstruction instruction = extractInvocationFromSnapshot(context);
            if (instruction == null) {
                instruction = getInvocationInstruction(context);
            }
            String referenceArtifact = buildReferenceArtifact(credential, instruction);

            if (log.isDebugEnabled()) {
                log.debug("Credential regenerated successfully for: " + externalSubscriptionId);
            }

            return AgentOperationResult.builder()
                    .credential(credential)
                    .instruction(instruction)
                    .referenceArtifact(referenceArtifact)
                    .externalSubscriptionId(externalSubscriptionId)
                    .build();

        } catch (Exception e) {
            log.error("Error regenerating credential for subscription: " + externalSubscriptionId, e);
            throw new APIManagementException("Failed to regenerate credential in Azure APIM: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteSubscription(FederatedSubscriptionContext context) throws APIManagementException {
        String externalSubscriptionId = context.getExternalSubscriptionId();
        if (log.isDebugEnabled()) {
            log.debug("Deleting Azure subscription: " + externalSubscriptionId);
        }

        try {
            // Check if subscription exists first (for idempotency)
            if (!subscriptionExists(context)) {
                log.warn("Subscription does not exist, skipping deletion: " + externalSubscriptionId);
                return;
            }

            // Delete the subscription
            manager.subscriptions()
                    .delete(resourceGroup, serviceName, externalSubscriptionId, "*");

            if (log.isDebugEnabled()) {
                log.debug("Successfully deleted subscription: " + externalSubscriptionId);
            }

        } catch (Exception e) {
            log.error("Error deleting Azure subscription: " + externalSubscriptionId, e);
            throw new APIManagementException("Failed to delete subscription in Azure APIM: " + e.getMessage(), e);
        }
    }

    /**
     * Generates invocation instruction for the Azure API.
     */
    /**
     * Generates invocation instruction for the Azure API.
     * 
     * Azure API Management has a fixed authentication pattern:
     * - Header name: "Ocp-Apim-Subscription-Key" (Azure standard, not configurable)
     * - Query parameter name: "subscription-key" (Azure standard, not configurable)
     * - Both methods are ALWAYS enabled (Azure's built-in behavior)
     * 
     * This is determined by Azure APIM's architecture, not runtime configuration.
     * See: https://learn.microsoft.com/en-us/azure/api-management/api-management-subscriptions
     */
    private InvocationInstruction getInvocationInstruction(FederatedSubscriptionContext context)
            throws APIManagementException {
        if (log.isDebugEnabled()) {
            log.debug("Generating invocation instruction for API: " + context.getApiName());
        }

        try {
            // Extract Azure API ID from reference artifact, then get the API name
            String azureApiId = extractAzureApiIdFromReferenceArtifact(context.getApiReferenceArtifact());
            String apiName = extractApiNameFromId(azureApiId);

            // Try to get the actual API contract to retrieve the correct path
            String apiPath = null;
            try {
                ApiContract apiContract = manager.apis().get(resourceGroup, serviceName, apiName);
                if (apiContract != null && apiContract.path() != null) {
                    apiPath = apiContract.path();
                    if (log.isDebugEnabled()) {
                        log.debug("Retrieved API path from Azure: " + apiPath);
                    }
                }
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("Could not retrieve API details, using API name as path: " + e.getMessage());
                }
            }

            // Build the base URL - Azure pattern: https://{service-name}.{hostname}
            String baseUrl = String.format("https://%s.%s", serviceName, hostname);

            // Use the retrieved API path, or fall back to the API name
            String basePath = apiPath != null ? "/" + apiPath : "/" + apiName;

            // Generate curl examples for both header and query parameter methods
            String curlExampleHeader = String.format(
                "curl -X GET \"%s%s" + "\"{path} -H \"%s: {YOUR_SUBSCRIPTION_KEY}\"",
                baseUrl, basePath, HEADER_NAME);

            String curlExampleQuery = String.format(
                "curl -X GET \"%s%s" + "\"{path}?%s={YOUR_SUBSCRIPTION_KEY}\"",
                baseUrl, basePath, QUERY_PARAM_NAME);

            // Add notes about alternative query parameter option
            String notes = String.format(
                    "You can pass the subscription key either in the '%s' header or as a '%s' query parameter.",
                    HEADER_NAME, QUERY_PARAM_NAME);

            ApiKeyInvocation invBody = new ApiKeyInvocation();
            invBody.setHeaderEnabled(true);
            invBody.setQueryParamEnabled(true);
            invBody.setHeaderName(HEADER_NAME);
            invBody.setQueryParamName(QUERY_PARAM_NAME);
            invBody.setBaseUrl(baseUrl);
            invBody.setBasePath(basePath);
            invBody.setCurlExampleHeader(curlExampleHeader);
            invBody.setCurlExampleQuery(curlExampleQuery);
            invBody.setNotes(notes);

            InvocationInstruction instruction = new InvocationInstruction();
            instruction.setBody(invBody);

            if (log.isDebugEnabled()) {
                log.debug("Invocation instruction generated for API: " + apiName + " with path: " + basePath);
            }

            return instruction;

        } catch (Exception e) {
            log.error("Error generating invocation instruction for API: " + context.getApiName(), e);
            throw new APIManagementException("Failed to generate invocation instruction: " + e.getMessage(), e);
        }
    }

    @Override
    public AgentOperationResult retrieveSubscription(FederatedSubscriptionContext context,
            boolean includeFullCredentials) throws APIManagementException {

        FederatedCredential credential;
        if (includeFullCredentials) {
            // First verify gateway supports retrieval
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

        InvocationInstruction instruction = extractInvocationFromSnapshot(context);
        if (instruction == null) {
            instruction = getInvocationInstruction(context);
        }

        return AgentOperationResult.builder()
                .credential(credential)
                .instruction(instruction)
                .build();
    }

    /**
     * Retrieves the full credential value from Azure APIM.
     */
    private FederatedCredential retrieveFullCredential(FederatedSubscriptionContext context)
            throws APIManagementException {
        String externalSubscriptionId = context.getExternalSubscriptionId();
        if (log.isDebugEnabled()) {
            log.debug("Retrieving credential for Azure subscription: " + externalSubscriptionId);
        }

        try {
            // Verify subscription exists
            SubscriptionContract subscription = manager.subscriptions()
                    .get(resourceGroup, serviceName, externalSubscriptionId);

            if (subscription == null) {
                throw new APIManagementException("Subscription not found: " + externalSubscriptionId);
            }

            // Retrieve the subscription keys
            SubscriptionKeysContract keys = manager.subscriptions()
                    .listSecrets(resourceGroup, serviceName, externalSubscriptionId);

            // Build typed credential body (schema name on envelope, not in body)
            String createdTime = subscription.createdDate() != null 
                ? subscription.createdDate().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) 
                : null;

            if (keys == null || keys.primaryKey() == null || keys.secondaryKey() == null) {
                throw new APIManagementException("Failed to retrieve subscription keys from Azure");
            }

            PrimarySecondaryKeyPairCredential credBody = new PrimarySecondaryKeyPairCredential(
                HEADER_NAME,
                QUERY_PARAM_NAME,
                keys.primaryKey(),
                keys.secondaryKey(),
                createdTime
            );

            FederatedCredential credential = new FederatedCredential();
            credential.setBody(credBody);
            credential.setExternalSubscriptionId(externalSubscriptionId);
            credential.setValueRetrievable(true);
            credential.setMasked(false);

            if (log.isDebugEnabled()) {
                log.debug("Credential retrieved successfully for: " + externalSubscriptionId);
            }

            return credential;

        } catch (Exception e) {
            log.error("Error retrieving credential for subscription: " + externalSubscriptionId, e);
            throw new APIManagementException("Failed to retrieve credential from Azure APIM: " + e.getMessage(), e);
        }
    }

    /**
     * Builds the reference artifact JSON for storage. Agent-internal.
     */
    private String buildReferenceArtifact(FederatedCredential credential,
                                           InvocationInstruction instruction) {
        JsonObject json = new JsonObject();

        if (credential != null && credential.getBody() != null) {
            try {
                PrimarySecondaryKeyPairCredential maskedCredBody =
                        (PrimarySecondaryKeyPairCredential) credential.getBody().masked();

                JsonObject maskedCred = new JsonObject();
                maskedCred.addProperty("schemaName", maskedCredBody.getSchemaName());
                maskedCred.addProperty("body", maskedCredBody.toJson());
                maskedCred.addProperty("isValueRetrievable", credential.isValueRetrievable());
                json.add("credential", maskedCred);
            } catch (Exception e) {
                log.warn("Failed to mask credential body", e);
            }
        }

        if (instruction != null && instruction.getBody() != null) {
            JsonObject invJson = new JsonObject();
            invJson.addProperty("schemaName", instruction.getSchemaName());
            invJson.addProperty("body", instruction.getBodyAsJson());
            json.add("invocationInstruction", invJson);
        }

        return json.toString();
    }

    /**
     * Extracts masked credential from the stored reference artifact.
     */
    private FederatedCredential extractCredentialFromReferenceArtifact(FederatedSubscriptionContext context) {
        String subscriptionReferenceArtifact = context.getSubscriptionReferenceArtifact();
        FederatedCredential credential = new FederatedCredential();
        if (subscriptionReferenceArtifact == null || subscriptionReferenceArtifact.isEmpty()) {
            return credential;
        }
        try {
            JsonObject json = JsonParser.parseString(subscriptionReferenceArtifact).getAsJsonObject();
            JsonObject credJson = json.has("credential")
                    ? json.getAsJsonObject("credential") : null;
            if (credJson != null) {
                if (credJson.has("body")) {
                    String bodyJson = credJson.get("body").getAsString();
                    PrimarySecondaryKeyPairCredential credBody =
                            PrimarySecondaryKeyPairCredential.fromJson(bodyJson);
                    credential.setBody(credBody);
                }
                if (credJson.has("isValueRetrievable")) {
                    credential.setValueRetrievable(credJson.get("isValueRetrievable").getAsBoolean());
                }
                credential.setMasked(true);
            }
        } catch (JsonSyntaxException e) {
            log.warn("Failed to parse subscription reference artifact", e);
        }
        return credential;
    }

    @Override
    public boolean subscriptionExists(FederatedSubscriptionContext context) throws APIManagementException {
        String externalSubscriptionId = context.getExternalSubscriptionId();
        try {
            SubscriptionContract subscription = manager.subscriptions()
                    .get(resourceGroup, serviceName, externalSubscriptionId);
            return subscription != null;
        } catch (Exception e) {
            // If the subscription is not found, Azure SDK throws an exception
            if (log.isDebugEnabled()) {
                log.debug("Subscription does not exist: " + externalSubscriptionId);
            }
            return false;
        }
    }

    @Override
    public String getGatewayType() {
        return GATEWAY_TYPE;
    }

    public SubscriptionSupportInfo getSubscriptionSupportInfo(FederatedSubscriptionContext context) 
            throws APIManagementException {
        if (log.isDebugEnabled()) {
            log.debug("Checking subscription support for API: " + context.getApiName());
        }

        try {
            // Extract Azure API ID from reference artifact
            String azureApiId = extractAzureApiIdFromReferenceArtifact(context.getApiReferenceArtifact());
            String apiName = extractApiNameFromId(azureApiId);

            // Get API from Azure
            ApiContract apiContract = manager.apis().get(resourceGroup, serviceName, apiName);

            if (apiContract == null) {
                throw new APIManagementException("API not found in Azure: " + apiName);
            }

            // Check if subscription required
            Boolean subscriptionRequired = apiContract.subscriptionRequired();

            if (subscriptionRequired != null && subscriptionRequired) {
                if (log.isDebugEnabled()) {
                    log.debug("API requires subscription: " + apiName + " - SECURED");
                }
                return new SubscriptionSupportInfo.Builder()
                        .status(SubscriptionSupportInfo.SubscriptionStatus.SECURED)
                        .supportedAuthTypes(new String[]{"primary-secondary-key-pair"})
                        .subscriptionOptions(null)
                        .build();
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("API does not require subscription: " + apiName + " - OPEN");
                }
                return new SubscriptionSupportInfo.Builder()
                        .status(SubscriptionSupportInfo.SubscriptionStatus.OPEN)
                        .supportedAuthTypes(new String[]{})
                        .subscriptionOptions(null)
                        .build();
            }

        } catch (Exception e) {
            log.error("Error checking subscription support for API: " + context.getApiName(), e);
            throw new APIManagementException("Failed to check subscription support: " + e.getMessage(), e);
        }
    }

    /**
     * Generates the subscription name following WSO2 pattern.
     * Pattern: wso2_{org}_{appUuid}_{apiUuid}_{envId}
     *
     * @param context The subscription request
     * @return The generated subscription name
     */
    private String generateSubscriptionName(FederatedSubscriptionContext context) {
        return "wso2_" + sanitize(context.getSubscriptionUuid());
    }

    /**
     * Generates a human-readable display name for the subscription.
     *
     * @param context The subscription context
     * @return The display name
     */
    private String generateDisplayName(FederatedSubscriptionContext context) {
        // Use API and Application names if available
        if (context.getApiName() != null && context.getApplicationName() != null) {
            return String.format("WSO2: %s -> %s", context.getApplicationName(), context.getApiName());
        }
        return String.format("WSO2 Subscription - %s", context.getSubscriptionUuid());
    }

    /**
     * Extracts the Azure API ID from the raw reference artifact JSON.
     * Azure stores the full resource path in the "id" field.
     *
     * @param referenceArtifact Raw JSON from AM_API_EXTERNAL_API_MAPPING
     * @return The Azure API ID (full resource path)
     * @throws APIManagementException If the reference artifact is missing or invalid
     */
    private String extractAzureApiIdFromReferenceArtifact(String referenceArtifact) throws APIManagementException {
        if (referenceArtifact == null || referenceArtifact.isEmpty()) {
            throw new APIManagementException("Reference artifact is null or empty for Azure API");
        }
        try {
            JsonObject refJson = JsonParser.parseString(referenceArtifact).getAsJsonObject();
            if (refJson.has(AzureConstants.AZURE_EXTERNAL_REFERENCE_ID)
                    && !refJson.get(AzureConstants.AZURE_EXTERNAL_REFERENCE_ID).isJsonNull()) {
                String azureId = refJson.get(AzureConstants.AZURE_EXTERNAL_REFERENCE_ID).getAsString();
                if (!azureId.isEmpty()) {
                    return azureId;
                }
            }
            // Fallback to "name" if "id" is not present
            if (refJson.has("name") && !refJson.get("name").isJsonNull()) {
                String name = refJson.get("name").getAsString();
                if (!name.isEmpty()) {
                    return name;
                }
            }
            throw new APIManagementException("Azure API ID not found in reference artifact");
        } catch (JsonSyntaxException e) {
            throw new APIManagementException("Failed to parse Azure reference artifact: " + e.getMessage(), e);
        }
    }

    /**
     * Builds the Azure API scope path for subscription.
     * <p>
     * Azure subscriptions require a scope path. The externalApiId from
     * FederatedSubscriptionRequest should be the full Azure resource ID stored in
     * the
     * AM_API_EXTERNAL_API_MAPPING table (from apiContract.id()).
     * </p>
     * Format:
     * /subscriptions/{subId}/resourceGroups/{rg}/providers/Microsoft.ApiManagement/service/{service}/apis/{apiId}
     * Or relative: /apis/{apiId}
     *
     * @param externalApiId The external API identifier (full Azure resource ID or
     *                      API name)
     * @return The API scope path
     * @throws APIManagementException If the API scope cannot be determined
     */
    private String buildApiScope(String externalApiId) throws APIManagementException {
        if (externalApiId == null || externalApiId.isEmpty()) {
            throw new APIManagementException("External API ID cannot be null or empty");
        }

        // If externalApiId is already a full Azure resource path, return it as-is
        if (externalApiId.startsWith("/subscriptions/")) {
            return externalApiId;
        }

        // If it starts with /apis/, it's a relative scope - return as-is
        if (externalApiId.startsWith("/apis/")) {
            return externalApiId;
        }

        // Otherwise, assume it's just the API name and build the relative scope
        // Azure SDK will resolve this relative to the service
        return String.format("/apis/%s", externalApiId);
    }

    /**
     * Extracts the API name/ID from the external API ID.
     * <p>
     * Handles both full Azure resource paths and simple API names:
     * - Full path: /subscriptions/.../apis/{apiId} → {apiId}
     * - Relative path: /apis/{apiId} → {apiId}
     * - Simple name: {apiId} → {apiId}
     * </p>
     *
     * @param externalApiId The external API ID (full path or simple name)
     * @return The API name/ID
     */
    private String extractApiNameFromId(String externalApiId) {
        if (externalApiId == null || externalApiId.isEmpty()) {
            return "api";
        }

        // If it's a path containing /apis/, extract what comes after
        if (externalApiId.contains("/apis/")) {
            String[] parts = externalApiId.split("/apis/");
            if (parts.length > 1) {
                // Return the API ID (may contain additional path segments)
                String apiPart = parts[1];
                // Remove any trailing path segments after the API ID
                int nextSlash = apiPart.indexOf('/');
                return nextSlash > 0 ? apiPart.substring(0, nextSlash) : apiPart;
            }
        }

        // If it's a full path without /apis/, extract the last segment
        if (externalApiId.contains("/")) {
            String[] parts = externalApiId.split("/");
            return parts[parts.length - 1];
        }

        // Otherwise, it's already just the API name
        return externalApiId;
    }

    /**
     * Sanitizes a string for use in Azure resource names.
     * Azure allows alphanumeric, hyphens, and underscores.
     *
     * @param value The value to sanitize
     * @return The sanitized value
     */
    private String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "null";
        }
        // Replace invalid characters with underscores, keep hyphens
        return value.replaceAll("[^a-zA-Z0-9-]", "_");
    }

    /**
     * Truncates a string to the specified length.
     *
     * @param value  The value to truncate
     * @param length The maximum length
     * @return The truncated value
     */
    private String truncate(String value, int length) {
        if (value == null || value.length() <= length) {
            return value;
        }
        return value.substring(0, length);
    }

    protected String maskCredential(String credentialValue) {
        if (credentialValue == null || credentialValue.isEmpty()) {
            return credentialValue;
        }
        int length = credentialValue.length();
        int visibleChars = 4;
        if (length <= visibleChars) {
            return "•".repeat(length);
        }
        int maskLength = Math.min(8, length - visibleChars);
        return "•".repeat(maskLength) + credentialValue.substring(length - visibleChars);
    }

    @Override
    public SubscriptionSupportInfo getFederationConfigProvider(FederatedSubscriptionContext context)
            throws APIManagementException {
        SubscriptionSupportInfo info = getSubscriptionSupportInfo(context);
        if (info != null && info.getStatus() == SubscriptionSupportInfo.SubscriptionStatus.SECURED) {
            info.setInvocationTemplate(getInvocationInstruction(context));
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
}
