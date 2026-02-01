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
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.FederatedSubscriptionAgent;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.FederatedCredential;
import org.wso2.carbon.apimgt.api.model.FederatedSubscriptionRequest;
import org.wso2.carbon.apimgt.api.model.InvocationInstruction;


import java.time.OffsetDateTime;
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
    private static final String INVOCATION_SCHEMA = "header-with-query-fallback";
    private static final String HEADER_NAME = "Ocp-Apim-Subscription-Key";
    private static final String QUERY_PARAM_NAME = "subscription-key";

    private String resourceGroup;
    private String serviceName;
    private String hostname;
    private ApiManagementManager manager;
    private Gson gson = new Gson();

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
    public FederatedCredential createSubscription(FederatedSubscriptionRequest request) throws APIManagementException {
        if (log.isDebugEnabled()) {
            log.debug("Creating Azure subscription for API UUID: " + request.getApiUuid()
                    + ", Application: " + request.getApplicationUuid());
        }

        try {
            // Extract Azure API ID from reference artifact
            String azureApiId = extractAzureApiIdFromReferenceArtifact(request.getReferenceArtifact());

            // Generate subscription name using WSO2 pattern
            String subscriptionName = generateSubscriptionName(request);
            String displayName = generateDisplayName(request);

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

            // Build opaque JSON body containing credential details
            JsonObject credBody = new JsonObject();
            credBody.addProperty("credentialType", CREDENTIAL_TYPE);
            credBody.addProperty("invocationSchema", INVOCATION_SCHEMA);
            credBody.addProperty("headerName", HEADER_NAME);
            credBody.addProperty("queryParamName", QUERY_PARAM_NAME);

            // Return both primary and secondary keys
            if (keys != null && keys.primaryKey() != null && keys.secondaryKey() != null) {
                credBody.addProperty("primaryKey", keys.primaryKey());
                credBody.addProperty("secondaryKey", keys.secondaryKey());
            } else {
                throw new APIManagementException("Failed to retrieve subscription keys from Azure");
            }

            // Set timestamps
            if (subscription.createdDate() != null) {
                credBody.addProperty("createdTime",
                    subscription.createdDate().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            }

            // Build and return the credential with opaque body
            FederatedCredential credential = new FederatedCredential();
            credential.setBody(gson.toJson(credBody));
            credential.setExternalSubscriptionId(subscription.name());
            credential.setValueRetrievable(true);
            credential.setMasked(false);

            if (log.isDebugEnabled()) {
                log.debug("Subscription credential created successfully for: " + subscriptionName);
            }

            return credential;

        } catch (Exception e) {
            log.error("Error creating Azure subscription for request: " + request.getSubscriptionUuid(), e);
            throw new APIManagementException("Failed to create subscription in Azure APIM: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteSubscription(String externalSubscriptionId) throws APIManagementException {
        if (log.isDebugEnabled()) {
            log.debug("Deleting Azure subscription: " + externalSubscriptionId);
        }

        try {
            // Check if subscription exists first (for idempotency)
            if (!subscriptionExists(externalSubscriptionId)) {
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

    @Override
    public FederatedCredential regenerateCredential(String externalSubscriptionId) throws APIManagementException {
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

            // Regenerate the primary key
            manager.subscriptions()
                    .regeneratePrimaryKey(resourceGroup, serviceName, externalSubscriptionId);

            if (log.isDebugEnabled()) {
                log.debug("Primary key regenerated for: " + externalSubscriptionId);
            }

            // Retrieve the new keys
            SubscriptionKeysContract keys = manager.subscriptions()
                    .listSecrets(resourceGroup, serviceName, externalSubscriptionId);

            // Build opaque JSON body containing credential details
            JsonObject credBody = new JsonObject();
            credBody.addProperty("credentialType", CREDENTIAL_TYPE);
            credBody.addProperty("invocationSchema", INVOCATION_SCHEMA);
            credBody.addProperty("headerName", HEADER_NAME);
            credBody.addProperty("queryParamName", QUERY_PARAM_NAME);

            if (keys != null && keys.primaryKey() != null && keys.secondaryKey() != null) {
                credBody.addProperty("primaryKey", keys.primaryKey());
                credBody.addProperty("secondaryKey", keys.secondaryKey());
            } else {
                throw new APIManagementException("Failed to retrieve regenerated keys from Azure");
            }

            // Set current timestamp
            credBody.addProperty("createdTime",
                OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

            // Build and return the credential with opaque body
            FederatedCredential credential = new FederatedCredential();
            credential.setBody(gson.toJson(credBody));
            credential.setExternalSubscriptionId(externalSubscriptionId);
            credential.setValueRetrievable(true);
            credential.setMasked(false);

            if (log.isDebugEnabled()) {
                log.debug("Credential regenerated successfully for: " + externalSubscriptionId);
            }

            return credential;

        } catch (Exception e) {
            log.error("Error regenerating credential for subscription: " + externalSubscriptionId, e);
            throw new APIManagementException("Failed to regenerate credential in Azure APIM: " + e.getMessage(), e);
        }
    }

    @Override
    public InvocationInstruction getInvocationInstruction(String referenceArtifact) throws APIManagementException {
        if (log.isDebugEnabled()) {
            log.debug("Generating invocation instruction from reference artifact");
        }

        try {
            // Extract Azure API ID from reference artifact, then get the API name
            String azureApiId = extractAzureApiIdFromReferenceArtifact(referenceArtifact);
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

            // Generate curl example with placeholder
            String curlExample = String.format(
                "curl -X GET \"%s%s" + "\"{path} -H \"%s: {YOUR_SUBSCRIPTION_KEY}\"",
                baseUrl, basePath, HEADER_NAME);

            // Add notes about alternative query parameter option
            String notes = String.format(
                    "You can pass the subscription key either in the '%s' header or as a '%s' query parameter.",
                    HEADER_NAME, QUERY_PARAM_NAME);

            // Build opaque JSON body containing invocation details
            JsonObject invBody = new JsonObject();
            invBody.addProperty("invocationSchema", INVOCATION_SCHEMA);
            invBody.addProperty("headerName", HEADER_NAME);
            invBody.addProperty("queryParamName", QUERY_PARAM_NAME);
            invBody.addProperty("baseUrl", baseUrl);
            invBody.addProperty("basePath", basePath);
            invBody.addProperty("curlExample", curlExample);
            invBody.addProperty("notes", notes);

            // Build and return the invocation instruction with opaque body
            InvocationInstruction instruction = new InvocationInstruction();
            instruction.setBody(gson.toJson(invBody));

            if (log.isDebugEnabled()) {
                log.debug("Invocation instruction generated for API: " + apiName + " with path: " + basePath);
            }

            return instruction;

        } catch (Exception e) {
            log.error("Error generating invocation instruction from reference artifact", e);
            throw new APIManagementException("Failed to generate invocation instruction: " + e.getMessage(), e);
        }
    }

    @Override
    public FederatedCredential retrieveCredential(String externalSubscriptionId) throws APIManagementException {
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

            // Build opaque JSON body containing credential details
            JsonObject credBody = new JsonObject();
            credBody.addProperty("credentialType", CREDENTIAL_TYPE);
            credBody.addProperty("invocationSchema", INVOCATION_SCHEMA);
            credBody.addProperty("headerName", HEADER_NAME);
            credBody.addProperty("queryParamName", QUERY_PARAM_NAME);

            if (keys != null && keys.primaryKey() != null && keys.secondaryKey() != null) {
                credBody.addProperty("primaryKey", keys.primaryKey());
                credBody.addProperty("secondaryKey", keys.secondaryKey());
            } else {
                throw new APIManagementException("Failed to retrieve subscription keys from Azure");
            }

            // Set timestamps
            if (subscription.createdDate() != null) {
                credBody.addProperty("createdTime",
                    subscription.createdDate().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            }

            // Build and return the credential with opaque body
            FederatedCredential credential = new FederatedCredential();
            credential.setBody(gson.toJson(credBody));
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

    @Override
    public String buildSubscriptionReferenceArtifact(FederatedCredential credential,
                                                      InvocationInstruction instruction) {
        JsonObject json = new JsonObject();

        if (credential != null && credential.getBody() != null) {
            // Parse credential body to mask the keys
            try {
                JsonObject credBody = JsonParser.parseString(credential.getBody()).getAsJsonObject();

                // Mask primary key if present
                if (credBody.has("primaryKey")) {
                    String originalPrimaryKey = credBody.get("primaryKey").getAsString();
                    credBody.addProperty("primaryKey", maskCredential(originalPrimaryKey));
                }

                // Mask secondary key if present
                if (credBody.has("secondaryKey")) {
                    String originalSecondaryKey = credBody.get("secondaryKey").getAsString();
                    credBody.addProperty("secondaryKey", maskCredential(originalSecondaryKey));
                }

                // Build masked credential body
                JsonObject maskedCred = new JsonObject();
                maskedCred.addProperty("body", gson.toJson(credBody));
                maskedCred.addProperty("isValueRetrievable", credential.isValueRetrievable());
                json.add("credential", maskedCred);
            } catch (JsonSyntaxException e) {
                log.warn("Failed to parse credential body for masking", e);
            }
        }

        if (instruction != null && instruction.getBody() != null) {
            JsonObject invJson = new JsonObject();
            invJson.addProperty("body", instruction.getBody());
            json.add("invocationInstruction", invJson);
        }

        return json.toString();
    }

    @Override
    public FederatedCredential extractCredentialFromReferenceArtifact(String subscriptionReferenceArtifact) {
        FederatedCredential credential = new FederatedCredential();
        if (subscriptionReferenceArtifact == null || subscriptionReferenceArtifact.isEmpty()) {
            return credential;
        }
        try {
            JsonObject json = JsonParser.parseString(subscriptionReferenceArtifact).getAsJsonObject();
            JsonObject credJson = json.has("credential") ? json.getAsJsonObject("credential") : null;
            if (credJson != null) {
                if (credJson.has("body")) {
                    credential.setBody(credJson.get("body").getAsString());
                }
                if (credJson.has("isValueRetrievable")) {
                    credential.setValueRetrievable(credJson.get("isValueRetrievable").getAsBoolean());
                }
                // Mark as masked since this comes from reference artifact
                credential.setMasked(true);
            }
        } catch (JsonSyntaxException e) {
            log.warn("Failed to parse subscription reference artifact", e);
        }
        return credential;
    }

    @Override
    public boolean subscriptionExists(String externalSubscriptionId) throws APIManagementException {
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

    @Override
    public String[] getSupportedAuthTypes(String apiReferenceArtifact) throws APIManagementException {
        if (log.isDebugEnabled()) {
            log.debug("Checking subscription support for API");
        }

        try {
            // Extract Azure API ID from reference artifact
            String azureApiId = extractAzureApiIdFromReferenceArtifact(apiReferenceArtifact);
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
                    log.debug("API requires subscription: " + apiName);
                }
                return new String[]{"primary-secondary-key-pair"};
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("API does not require subscription: " + apiName);
                }
                return new String[]{};  // No subscription security
            }
        } catch (Exception e) {
            log.error("Error checking subscription support for API", e);
            throw new APIManagementException("Failed to check subscription support: " + e.getMessage(), e);
        }
    }

    /**
     * Generates the subscription name following WSO2 pattern.
     * Pattern: wso2_{org}_{appUuid}_{apiUuid}_{envId}
     *
     * @param request The subscription request
     * @return The generated subscription name
     */
    private String generateSubscriptionName(FederatedSubscriptionRequest request) {
        return "wso2_" + sanitize(request.getSubscriptionUuid());
    }

    /**
     * Generates a human-readable display name for the subscription.
     *
     * @param request The subscription request
     * @return The display name
     */
    private String generateDisplayName(FederatedSubscriptionRequest request) {
        return String.format("WSO2 Subscription - %s", request.getSubscriptionUuid());
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
}
