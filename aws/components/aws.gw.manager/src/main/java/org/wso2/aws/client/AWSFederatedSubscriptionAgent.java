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

package org.wso2.aws.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.model.schema.credential.OpaqueApiKeyCredential;
import org.wso2.carbon.apimgt.api.model.schema.invocation.ApiKeyInvocation;
import org.wso2.carbon.apimgt.api.model.schema.options.SubscriptionPlans;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.FederatedSubscriptionAgent;
import org.wso2.carbon.apimgt.api.model.AgentOperationResult;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.FederatedCredential;
import org.wso2.carbon.apimgt.api.model.FederatedSubscriptionContext;
import org.wso2.carbon.apimgt.api.model.FederatedSubscriptionOptions;
import org.wso2.carbon.apimgt.api.model.GatewayPortalConfiguration;
import org.wso2.carbon.apimgt.api.model.InvocationInstruction;
import org.wso2.carbon.apimgt.api.model.RemotePlan;
import org.wso2.carbon.apimgt.api.model.SubscriptionSupportInfo;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
// Removed unused ApiKey import
import software.amazon.awssdk.services.apigateway.model.CreateApiKeyRequest;
import software.amazon.awssdk.services.apigateway.model.CreateApiKeyResponse;
import software.amazon.awssdk.services.apigateway.model.CreateUsagePlanKeyRequest;
import software.amazon.awssdk.services.apigateway.model.DeleteApiKeyRequest;
import software.amazon.awssdk.services.apigateway.model.GetApiKeyRequest;
import software.amazon.awssdk.services.apigateway.model.GetApiKeyResponse;
import software.amazon.awssdk.services.apigateway.model.GetUsagePlansRequest;
import software.amazon.awssdk.services.apigateway.model.GetUsagePlansResponse;
import software.amazon.awssdk.services.apigateway.model.ApiStage;
import software.amazon.awssdk.services.apigateway.model.UsagePlan;
import software.amazon.awssdk.services.apigateway.model.GetResourcesRequest;
import software.amazon.awssdk.services.apigateway.model.GetResourcesResponse;
import software.amazon.awssdk.services.apigateway.model.Resource;
import software.amazon.awssdk.services.apigateway.model.Method;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS implementation of the FederatedSubscriptionAgent.
 * Responsibilities:
 * 1. Create API Keys on AWS (mapped to WSO2 subscriptions).
 * 2. Associate API Keys with Usage Plans (mapped 1:1 to WSO2 APIs).
 */
public class AWSFederatedSubscriptionAgent implements FederatedSubscriptionAgent {

    private static final Log log = LogFactory.getLog(AWSFederatedSubscriptionAgent.class);
    private static final String CREDENTIAL_TYPE = "opaque-api-key";
    private static final String HEADER_NAME = "x-api-key";

    private ApiGatewayClient apiGatewayClient;
    private String region;
    private String stage;

    @Override
    public String getGatewayType() {
        return AWSConstants.AWS_TYPE;
    }

    @Override
    public boolean isSubscriptionSupport() {
        try {
            GatewayPortalConfiguration featureCatalog = new AWSGatewayConfiguration().getGatewayFeatureCatalog();
            Object supportedFeatures = featureCatalog.getSupportedFeatures();
            if (supportedFeatures instanceof JsonObject) {
                JsonObject featuresJson = (JsonObject) supportedFeatures;
                if (featuresJson.has("federatedSubscription")
                        && featuresJson.get("federatedSubscription").isJsonObject()) {
                    JsonObject federatedSubscriptionConfig = featuresJson.getAsJsonObject("federatedSubscription");
                    return federatedSubscriptionConfig.has("subcriptionSupport")
                            && federatedSubscriptionConfig.get("subcriptionSupport").getAsBoolean();
                }
            }
        } catch (APIManagementException e) {
            log.warn("Error while resolving AWS subscription support from GatewayFeatureCatalog", e);
        }
        return false;
    }

    @Override
    public void init(Environment environment, String organization) throws APIManagementException {
        try {
            this.region = environment.getAdditionalProperties().get(AWSConstants.AWS_ENVIRONMENT_REGION);
            this.stage = environment.getAdditionalProperties().get(AWSConstants.AWS_API_STAGE);
            String accessKey = environment.getAdditionalProperties().get(AWSConstants.AWS_ENVIRONMENT_ACCESS_KEY);
            String secretKey = environment.getAdditionalProperties().get(AWSConstants.AWS_ENVIRONMENT_SECRET_KEY);

            SdkHttpClient httpClient = ApacheHttpClient.builder().build();
            this.apiGatewayClient = ApiGatewayClient.builder()
                    .region(Region.of(region))
                    .httpClient(httpClient)
                    .credentialsProvider(StaticCredentialsProvider
                            .create(AwsBasicCredentials.create(accessKey, secretKey)))
                    .build();
        } catch (Exception e) {
            throw new APIManagementException("Error occurred while initializing AWS Federated Subscription Agent", e);
        }
    }

    @Override
    public AgentOperationResult createSubscription(FederatedSubscriptionContext context, String selectedOption)
            throws APIManagementException {
        try {
            String subUuid = context.getSubscriptionUuid();
            String apiKeyName = "wso2_" + subUuid;

            if (log.isDebugEnabled()) {
                log.debug("Creating AWS subscription for API: " + context.getApiName() + 
                        ", Application: " + context.getApplicationName() + 
                        ", Subscription: " + subUuid);
            }

            // 1. Determine Usage Plan ID — selectedOption is required
            if (StringUtils.isEmpty(selectedOption)) {
                throw new APIManagementException(
                        "Subscription option (usage plan) must be selected for AWS APIs");
            }

            JsonObject selected = JsonParser.parseString(selectedOption).getAsJsonObject();
            String usagePlanId = selected.get("id").getAsString();
            if (log.isDebugEnabled()) {
                log.debug("Using selected usage plan: " + usagePlanId);
            }

            // 2. Create API Key
            CreateApiKeyRequest createApiKeyRequest = CreateApiKeyRequest.builder()
                    .name(apiKeyName)
                    .enabled(true)
                    .description("WSO2 Subscription: " + subUuid)
                    .build();
            CreateApiKeyResponse apiKeyResponse = apiGatewayClient.createApiKey(createApiKeyRequest);
            String apiKeyId = apiKeyResponse.id();
            String apiKeyValue = apiKeyResponse.value();


            // 3. Associate API Key with Usage Plan
            CreateUsagePlanKeyRequest planKeyRequest = CreateUsagePlanKeyRequest.builder()
                    .usagePlanId(usagePlanId)
                    .keyId(apiKeyId)
                    .keyType("API_KEY")
                    .build();
            apiGatewayClient.createUsagePlanKey(planKeyRequest);

            // 4. Build credential
            OpaqueApiKeyCredential credBody = new OpaqueApiKeyCredential(HEADER_NAME, apiKeyValue);
            FederatedCredential credential = new FederatedCredential();
            credential.setBody(credBody);
            credential.setExternalSubscriptionId(apiKeyId);
            credential.setValueRetrievable(true);
            credential.setMasked(false);

            // 5. Build invocation instruction — prefer stored snapshot over live gateway call
            InvocationInstruction instruction = extractInvocationFromSnapshot(context);
            if (instruction == null) {
                instruction = getInvocationInstruction(context);
            }

            // 6. Build reference artifact (agent-owned, stores selectedOption internally)
            String referenceArtifact = buildReferenceArtifact(credential, instruction, selectedOption);

            if (log.isDebugEnabled()) {
                log.debug("Successfully created AWS subscription for API: " + context.getApiName());
            }

            return AgentOperationResult.builder()
                    .credential(credential)
                    .instruction(instruction)
                    .referenceArtifact(referenceArtifact)
                    .externalSubscriptionId(apiKeyId)
                    .build();

        } catch (Exception e) {
            log.error("Error creating subscription on AWS for API: " + context.getApiName(), e);
            throw new APIManagementException("Error creating subscription on AWS: " + e.getMessage(), e);
        }
    }

    public void validateSelectedOption(FederatedSubscriptionContext context, String selectedOption)
            throws APIManagementException {
        SubscriptionSupportInfo snapshot = context.getFederationConfigSnapshot();
        if (snapshot == null || snapshot.getSubscriptionOptions() == null
                || !(snapshot.getSubscriptionOptions().getBody() instanceof SubscriptionPlans)) {
            return;
        }

        SubscriptionPlans plansBody = (SubscriptionPlans) snapshot.getSubscriptionOptions().getBody();
        List<org.wso2.carbon.apimgt.api.model.schema.options.SubscriptionPlan> plans = plansBody.getPlans();
        if (plans == null || plans.isEmpty()) {
            return;
        }

        if (StringUtils.isBlank(selectedOption)) {
            throw new APIManagementException("Subscription option (usage plan) must be selected for AWS APIs");
        }

        String selectedPlanId;
        try {
            JsonObject selected = JsonParser.parseString(selectedOption).getAsJsonObject();
            if (!selected.has("id") || selected.get("id").isJsonNull()) {
                throw new APIManagementException("Selected subscription option must include plan id");
            }
            selectedPlanId = selected.get("id").getAsString();
        } catch (APIManagementException e) {
            throw e;
        } catch (Exception e) {
            throw new APIManagementException("Invalid selected subscription option payload", e);
        }

        for (org.wso2.carbon.apimgt.api.model.schema.options.SubscriptionPlan plan : plans) {
            if (plan != null && plan.isEnabled() && selectedPlanId.equals(plan.getId())) {
                return;
            }
        }

        throw new APIManagementException("Invalid or disabled subscription option selected");
    }

    @Override
    public void deleteSubscription(FederatedSubscriptionContext context) throws APIManagementException {
        try {
            String externalSubscriptionId = context.getExternalSubscriptionId();
            
            if (log.isDebugEnabled()) {
                log.debug("Deleting AWS subscription for API: " + context.getApiName() + 
                        ", Application: " + context.getApplicationName() + 
                        ", External ID: " + externalSubscriptionId);
            }
            
            if (StringUtils.isNotEmpty(externalSubscriptionId)) {
                DeleteApiKeyRequest deleteRequest = DeleteApiKeyRequest.builder()
                        .apiKey(externalSubscriptionId)
                        .build();
                apiGatewayClient.deleteApiKey(deleteRequest);
                
                if (log.isDebugEnabled()) {
                    log.debug("Successfully deleted AWS subscription for API: " + context.getApiName());
                }
            }
        } catch (Exception e) {
            log.error("Error deleting subscription on AWS for API: " + context.getApiName(), e);
            throw new APIManagementException("Error deleting subscription on AWS: " + e.getMessage(), e);
        }
    }

    /**
     * Generates invocation instruction for the AWS API.
     * 
     * AWS API Gateway has a fixed authentication pattern:
     * - Header name: "x-api-key" (AWS standard, not configurable)
     * - Header-only delivery (no query parameter or body support)
     * 
     * This is determined by AWS's API Gateway architecture, not runtime configuration.
     */
    private InvocationInstruction getInvocationInstruction(FederatedSubscriptionContext context) {
        // Extract AWS API ID from reference artifact and build real execution URL
        String baseUrl = "https://{api-url}";
        String basePath = "/{stage}";
        String curlExampleHeader = "curl -H '" + HEADER_NAME + ": {apiKey}' https://{api-url}/{stage}";
        
        try {
            String awsApiId = extractAWSApiIdFromReferenceArtifact(context.getApiReferenceArtifact());
            baseUrl = "https://" + awsApiId + ".execute-api." + region + ".amazonaws.com";
            basePath = "/" + (stage != null ? stage : "{stage}");
            curlExampleHeader = "curl -H '" + HEADER_NAME + ": {apiKey}' " + baseUrl + basePath;
            
            if (log.isDebugEnabled()) {
                log.debug("Generated invocation instruction for API: " + context.getApiName() + 
                        ", Base URL: " + baseUrl);
            }
        } catch (Exception e) {
            log.warn("Failed to extract AWS API ID from reference artifact for API: " + 
                    context.getApiName() + ", using placeholder URL", e);
        }
        
        ApiKeyInvocation invBody = new ApiKeyInvocation();
        invBody.setHeaderEnabled(true);
        invBody.setHeaderName(HEADER_NAME);
        invBody.setBaseUrl(baseUrl);
        invBody.setBasePath(basePath);
        invBody.setCurlExampleHeader(curlExampleHeader);

        InvocationInstruction instruction = new InvocationInstruction();
        instruction.setBody(invBody);
        return instruction;
    }

    public SubscriptionSupportInfo getSubscriptionSupportInfo(FederatedSubscriptionContext context)
            throws APIManagementException {
        if (log.isDebugEnabled()) {
            log.debug("Checking subscription support for API: " + context.getApiName());
        }
        
        try {
            // Extract AWS API ID from reference artifact
            String awsApiId = extractAWSApiIdFromReferenceArtifact(context.getApiReferenceArtifact());
            
            // Get all resources (routes) for this API with embedded methods
            GetResourcesRequest getResourcesRequest = GetResourcesRequest.builder()
                    .restApiId(awsApiId)
                    .embed(List.of("methods"))
                    .build();
            
            GetResourcesResponse resourcesResponse = apiGatewayClient.getResources(getResourcesRequest);
            
            // Check if any method has apiKeyRequired = true
            boolean apiKeyRequired = false;
            for (Resource resource : resourcesResponse.items()) {
                if (resource.resourceMethods() != null) {
                    for (Method method : resource.resourceMethods().values()) {
                        if (method.apiKeyRequired() != null && method.apiKeyRequired()) {
                            apiKeyRequired = true;
                            break;
                        }
                    }
                    if (apiKeyRequired) {
                        break;
                    }
                }
            }
            
            if (!apiKeyRequired) {
                // No method requires API key → OPEN
                if (log.isDebugEnabled()) {
                    log.debug("API " + context.getApiName() + " does not require API keys - OPEN");
                }
                return new SubscriptionSupportInfo.Builder()
                        .status(SubscriptionSupportInfo.SubscriptionStatus.OPEN)
                        .supportedAuthTypes(new String[]{})
                        .subscriptionOptions(null)
                        .build();
            }
            
            // API key required - check if usage plans are available
            List<UsagePlan> matchingPlans = findUsagePlansForApi(awsApiId, this.stage);
            
            if (matchingPlans.isEmpty()) {
                // API key required but no usage plans → SECURED
                // Subscription creation will fail at action time with clear error about missing usage plans
                if (log.isDebugEnabled()) {
                    log.debug("API " + context.getApiName() + " requires API keys but no usage plans found - SECURED (will fail at subscription creation)");
                }
                return new SubscriptionSupportInfo.Builder()
                        .status(SubscriptionSupportInfo.SubscriptionStatus.SECURED)
                        .supportedAuthTypes(new String[]{CREDENTIAL_TYPE})
                        .subscriptionOptions(null)
                        .build();
            }
            
            // API key required + usage plans found → SECURED
            if (log.isDebugEnabled()) {
                log.debug("API " + context.getApiName() + " requires API keys with " 
                        + matchingPlans.size() + " usage plans - SECURED");
            }
            
            // Build subscription options from usage plans
            FederatedSubscriptionOptions options = buildSubscriptionOptions(matchingPlans);
            
            return new SubscriptionSupportInfo.Builder()
                    .status(SubscriptionSupportInfo.SubscriptionStatus.SECURED)
                    .supportedAuthTypes(new String[]{CREDENTIAL_TYPE})
                    .subscriptionOptions(options)
                    .build();
            
        } catch (Exception e) {
            log.warn("Error checking subscription support for API: " + context.getApiName() 
                    + ". Assuming OPEN.", e);
            return new SubscriptionSupportInfo.Builder()
                    .status(SubscriptionSupportInfo.SubscriptionStatus.OPEN)
                    .supportedAuthTypes(new String[]{})
                    .subscriptionOptions(null)
                    .build();
        }
    }

    /**
     * Builds subscription options from usage plans.
     */
    private FederatedSubscriptionOptions buildSubscriptionOptions(List<UsagePlan> matchingPlans) {
        List<org.wso2.carbon.apimgt.api.model.schema.options.SubscriptionPlan> subscriptionPlans = new ArrayList<>();
        for (UsagePlan plan : matchingPlans) {
            org.wso2.carbon.apimgt.api.model.schema.options.SubscriptionPlan subscriptionPlan = 
                    new org.wso2.carbon.apimgt.api.model.schema.options.SubscriptionPlan(
                        plan.id(),
                        plan.name(),
                        plan.description() != null ? plan.description() : ""
                    );
            
            // Include throttle info in limits map
            if (plan.throttle() != null) {
                if (plan.throttle().rateLimit() != null) {
                    subscriptionPlan.addLimit("rateLimit", String.valueOf(plan.throttle().rateLimit()));
                }
                if (plan.throttle().burstLimit() != null) {
                    subscriptionPlan.addLimit("burstLimit", String.valueOf(plan.throttle().burstLimit()));
                }
            }
            
            // Include quota info in limits map
            if (plan.quota() != null) {
                if (plan.quota().limit() != null) {
                    subscriptionPlan.addLimit("quotaLimit", String.valueOf(plan.quota().limit()));
                }
                if (plan.quota().period() != null) {
                    subscriptionPlan.addLimit("quotaPeriod", plan.quota().period().toString());
                }
            }
            
            subscriptionPlans.add(subscriptionPlan);
        }

        SubscriptionPlans optionsBody = new SubscriptionPlans("Usage Plan", subscriptionPlans);
        FederatedSubscriptionOptions result = new FederatedSubscriptionOptions();
        result.setBody(optionsBody);
        return result;
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
     * Retrieves the full credential value from AWS API Gateway.
     */
    private FederatedCredential retrieveFullCredential(FederatedSubscriptionContext context)
            throws APIManagementException {
        String externalSubscriptionId = context.getExternalSubscriptionId();
        
        if (log.isDebugEnabled()) {
            log.debug("Retrieving credential for API: " + context.getApiName() + 
                    ", Application: " + context.getApplicationName() + 
                    ", AWS API key: " + externalSubscriptionId);
        }

        try {
            GetApiKeyRequest getApiKeyRequest = GetApiKeyRequest.builder()
                    .apiKey(externalSubscriptionId)
                    .includeValue(true)
                    .build();
            GetApiKeyResponse apiKeyResponse = apiGatewayClient.getApiKey(getApiKeyRequest);

            if (apiKeyResponse == null || apiKeyResponse.value() == null) {
                throw new APIManagementException("Failed to retrieve API key value from AWS: " + externalSubscriptionId);
            }

            OpaqueApiKeyCredential credBody = new OpaqueApiKeyCredential(HEADER_NAME, apiKeyResponse.value());

            FederatedCredential credential = new FederatedCredential();
            credential.setBody(credBody);
            credential.setExternalSubscriptionId(externalSubscriptionId);
            credential.setValueRetrievable(true);
            credential.setMasked(false);

            if (log.isDebugEnabled()) {
                log.debug("Credential retrieved successfully for API: " + context.getApiName());
            }

            return credential;
        } catch (Exception e) {
            log.error("Error retrieving credential for API: " + context.getApiName() + 
                    ", AWS API key: " + externalSubscriptionId, e);
            throw new APIManagementException("Failed to retrieve credential from AWS: " + e.getMessage(), e);
        }
    }

    /**
     * Builds the reference artifact JSON for storage. Agent-internal — stores
     * selectedOption for future create/delete/retrieve flows.
     */
    private String buildReferenceArtifact(FederatedCredential credential,
                                           InvocationInstruction instruction,
                                           String selectedOption) {
        JsonObject json = new JsonObject();

        if (credential != null && credential.getBody() != null) {
            try {
                OpaqueApiKeyCredential maskedCredBody =
                        (OpaqueApiKeyCredential) credential.getBody().masked();

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

        if (selectedOption != null) {
            json.addProperty("selectedOption", selectedOption);
        }

        return json.toString();
    }

    /**
     * Extracts masked credential from the stored reference artifact.
     */
    private FederatedCredential extractCredentialFromReferenceArtifact(FederatedSubscriptionContext context) {
        FederatedCredential credential = new FederatedCredential();
        String subscriptionReferenceArtifact = context.getCredentialReferenceArtifact();

        if (subscriptionReferenceArtifact == null || subscriptionReferenceArtifact.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("No subscription reference artifact found for API: " + context.getApiName());
            }
            return credential;
        }
        try {
            JsonObject json = JsonParser.parseString(subscriptionReferenceArtifact).getAsJsonObject();
            JsonObject credJson = json.has("credential") ? json.getAsJsonObject("credential") : null;
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
    
    private List<UsagePlan> findUsagePlansForApi(String apiId, String stageName) {
        List<UsagePlan> matching = new ArrayList<>();
        try {
            String position = null;
            do {
                GetUsagePlansRequest request = GetUsagePlansRequest.builder()
                        .limit(500)
                        .position(position)
                        .build();
                GetUsagePlansResponse response = apiGatewayClient.getUsagePlans(request);
                
                for (UsagePlan plan : response.items()) {
                    // Check if this plan is associated with our API and stage
                    if (plan.apiStages() != null) {
                        for (ApiStage apiStage : plan.apiStages()) {
                            if (apiId.equals(apiStage.apiId()) && 
                                (stageName == null || stageName.equals(apiStage.stage()))) {
                                matching.add(plan);
                                break;  // Don't add same plan twice
                            }
                        }
                    }
                }
                
                position = response.position();
            } while (position != null);
            
        } catch (Exception e) {
            log.error("Error finding usage plans for API: " + apiId, e);
        }
        return matching;
    }
    
    /**
     * Extracts AWS API ID from the reference artifact using the shared utility method.
     */
    private String extractAWSApiIdFromReferenceArtifact(String referenceArtifact) throws APIManagementException {
        return org.wso2.aws.client.util.GatewayUtil.getAWSApiIdFromReferenceArtifact(referenceArtifact);
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

    /**
     * Lists all available AWS Usage Plans in the account, without filtering by API or stage.
     * Used by the Admin Portal to populate the plan mapping section during gateway onboarding.
     *
     * @param environment The gateway environment configuration (contains AWS credentials)
     * @return List of RemotePlan instances representing available AWS Usage Plans
     * @throws APIManagementException if the AWS API call fails
     */
    @Override
    public List<RemotePlan> listRemotePlans(Environment environment) throws APIManagementException {
        List<RemotePlan> remotePlans = new ArrayList<>();
        try {
            String position = null;
            do {
                GetUsagePlansRequest request = GetUsagePlansRequest.builder()
                        .limit(500)
                        .position(position)
                        .build();
                GetUsagePlansResponse response = apiGatewayClient.getUsagePlans(request);
                for (UsagePlan plan : response.items()) {
                    Map<String, String> limits = new HashMap<>();
                    if (plan.throttle() != null) {
                        if (plan.throttle().rateLimit() != null) {
                            limits.put("rateLimit", String.valueOf(plan.throttle().rateLimit()));
                        }
                        if (plan.throttle().burstLimit() != null) {
                            limits.put("burstLimit", String.valueOf(plan.throttle().burstLimit()));
                        }
                    }
                    if (plan.quota() != null) {
                        if (plan.quota().limit() != null) {
                            limits.put("quotaLimit", String.valueOf(plan.quota().limit()));
                        }
                        if (plan.quota().period() != null) {
                            limits.put("quotaPeriod", plan.quota().period().toString());
                        }
                    }
                    remotePlans.add(new RemotePlan(
                            plan.id(),
                            plan.name(),
                            plan.description() != null ? plan.description() : "",
                            limits));
                }
                position = response.position();
            } while (position != null);
        } catch (Exception e) {
            throw new APIManagementException("Failed to list AWS Usage Plans: " + e.getMessage(), e);
        }
        return remotePlans;
    }
}
