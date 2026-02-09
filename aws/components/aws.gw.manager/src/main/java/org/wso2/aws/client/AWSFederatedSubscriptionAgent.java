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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.aws.client.util.GatewayUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.FederatedSubscriptionAgent;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.FederatedCredential;
import org.wso2.carbon.apimgt.api.model.FederatedSubscriptionContext;
import org.wso2.carbon.apimgt.api.model.FederatedSubscriptionOptions;
import org.wso2.carbon.apimgt.api.model.InvocationInstruction;
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
import java.util.List;

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
    public FederatedCredential createSubscription(FederatedSubscriptionContext context) throws APIManagementException {
        try {
            String subUuid = context.getSubscriptionUuid();
            String apiKeyName = "wso2_" + subUuid;

            if (log.isDebugEnabled()) {
                log.debug("Creating AWS subscription for API: " + context.getApiName() + 
                        ", Application: " + context.getApplicationName() + 
                        ", Subscription: " + subUuid);
            }

            // 1. Create API Key
            CreateApiKeyRequest createApiKeyRequest = CreateApiKeyRequest.builder()
                    .name(apiKeyName)
                    .enabled(true)
                    .description("WSO2 Subscription: " + subUuid)
                    .build();
            CreateApiKeyResponse apiKeyResponse = apiGatewayClient.createApiKey(createApiKeyRequest);
            String apiKeyId = apiKeyResponse.id();
            String apiKeyValue = apiKeyResponse.value();

            // 2. Determine Usage Plan ID
            String usagePlanId;
            if (context.getSelectedOption() != null) {
                // Fresh create - developer selected an option
                JsonObject selected = JsonParser.parseString(context.getSelectedOption()).getAsJsonObject();
                usagePlanId = selected.get("id").getAsString();
                if (log.isDebugEnabled()) {
                    log.debug("Using selected usage plan: " + usagePlanId);
                }
            } else if (context.getSubscriptionReferenceArtifact() != null) {
                // Regeneration - reuse previous selection
                usagePlanId = extractUsagePlanIdFromArtifact(context.getSubscriptionReferenceArtifact());
                if (log.isDebugEnabled()) {
                    log.debug("Reusing previous usage plan: " + usagePlanId);
                }
            } else {
                // Legacy fallback - use wso2_{apiUUID} naming
                String apiUuid = context.getApiUuid();
                String usagePlanName = "wso2_" + apiUuid;
                UsagePlan usagePlan = findUsagePlanByName(usagePlanName);
                if (usagePlan == null) {
                    // Try to find any usage plan associated with this API
                    String awsApiId = GatewayUtil.getAWSApiIdFromReferenceArtifact(context.getApiReferenceArtifact());
                    List<UsagePlan> availablePlans = findUsagePlansForApi(awsApiId, stage);
                    if (availablePlans.isEmpty()) {
                        throw new APIManagementException("No subscription option selected and no usage plans found for this API. " +
                                "Please ensure the API stage is associated with a usage plan on AWS API Gateway.");
                    }
                    throw new APIManagementException("No subscription option selected and no previous selection found. " +
                            "Usage Plan not found: " + usagePlanName + ". Available usage plans: " + 
                            availablePlans.size() + ". Please select a subscription tier when creating the subscription.");
                }
                usagePlanId = usagePlan.id();
                if (log.isDebugEnabled()) {
                    log.debug("Using legacy usage plan: " + usagePlanId);
                }
            }

            // 3. Associate API Key with Usage Plan
            CreateUsagePlanKeyRequest planKeyRequest = CreateUsagePlanKeyRequest.builder()
                    .usagePlanId(usagePlanId)
                    .keyId(apiKeyId)
                    .keyType("API_KEY")
                    .build();
            apiGatewayClient.createUsagePlanKey(planKeyRequest);

            // 4. Return Credential
            JsonObject credBody = new JsonObject();
            credBody.addProperty("credentialType", CREDENTIAL_TYPE);
            credBody.addProperty("headerName", HEADER_NAME);
            credBody.addProperty("value", apiKeyValue);

            FederatedCredential credential = new FederatedCredential();
            credential.setBody(credBody.toString());
            credential.setExternalSubscriptionId(apiKeyId); // Use Key ID as external ref
            credential.setValueRetrievable(true); // AWS allows retrieving value later
            credential.setMasked(false);

            if (log.isDebugEnabled()) {
                log.debug("Successfully created AWS subscription for API: " + context.getApiName());
            }

            return credential;

        } catch (Exception e) {
            // Cleanup on failure? AWS operations are not transactional.
            // Ideally we should try to delete the created key if association fails.
            log.error("Error creating subscription on AWS for API: " + context.getApiName(), e);
            throw new APIManagementException("Error creating subscription on AWS: " + e.getMessage(), e);
        }
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

    @Override
    public InvocationInstruction getInvocationInstruction(FederatedSubscriptionContext context) {
        JsonObject invBody = new JsonObject();
        invBody.addProperty("invocationSchema", "header-based");
        invBody.addProperty("headerName", HEADER_NAME);
        
        // Extract AWS API ID from reference artifact and build real execution URL
        String baseUrl = "https://{api-url}";
        String basePath = "/{stage}";
        String curlExampleHeader = "curl -H '" + HEADER_NAME + ": {apiKey}' https://{api-url}/{stage}";
        
        try {
            String awsApiId = GatewayUtil.getAWSApiIdFromReferenceArtifact(context.getApiReferenceArtifact());
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
        
        invBody.addProperty("baseUrl", baseUrl);
        invBody.addProperty("basePath", basePath);
        invBody.addProperty("curlExampleHeader", curlExampleHeader);

        InvocationInstruction instruction = new InvocationInstruction();
        instruction.setBody(invBody.toString());
        return instruction;
    }

    @Override
    public String[] getSupportedAuthTypes(FederatedSubscriptionContext context) {
        // Check if the AWS API actually requires API keys AND has usage plans
        if (log.isDebugEnabled()) {
            log.debug("Checking if API requires API keys: " + context.getApiName());
        }
        
        try {
            // Extract AWS API ID from reference artifact
            String awsApiId = GatewayUtil.getAWSApiIdFromReferenceArtifact(context.getApiReferenceArtifact());
            
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
                // No method requires API key
                if (log.isDebugEnabled()) {
                    log.debug("API " + context.getApiName() + " does not require API keys");
                }
                return new String[]{};
            }
            
            // API key is required, now check if there are any usage plans associated with this API stage
            List<UsagePlan> usagePlans = findUsagePlansForApi(awsApiId, stage);
            if (usagePlans.isEmpty()) {
                // No usage plans available - subscriptions cannot be created
                if (log.isDebugEnabled()) {
                    log.debug("API " + context.getApiName() + " requires API keys but has no usage plans associated with stage: " + stage);
                }
                return new String[]{};
            }
            
            // API key required and usage plans are available
            if (log.isDebugEnabled()) {
                log.debug("API " + context.getApiName() + " requires API keys and has " + usagePlans.size() + " usage plan(s) available");
            }
            return new String[]{CREDENTIAL_TYPE};
            
        } catch (Exception e) {
            // On error, fall back to not requiring subscription for safety
            // (fail-open to avoid blocking legitimate APIs)
            log.warn("Error checking API key requirement for API: " + context.getApiName() 
                    + ". Assuming no subscription required.", e);
            return new String[]{};
        }
    }
    
    @Override
    public FederatedCredential retrieveCredential(FederatedSubscriptionContext context) throws APIManagementException {
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

            // Build opaque JSON body containing credential details
            JsonObject credBody = new JsonObject();
            credBody.addProperty("credentialType", CREDENTIAL_TYPE);
            credBody.addProperty("headerName", HEADER_NAME);
            credBody.addProperty("value", apiKeyResponse.value());

            // Build and return the credential with opaque body
            FederatedCredential credential = new FederatedCredential();
            credential.setBody(credBody.toString());
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

    @Override
    public String buildSubscriptionReferenceArtifact(FederatedCredential credential,
                                                      InvocationInstruction instruction,
                                                      FederatedSubscriptionContext context) {
        JsonObject json = new JsonObject();

        if (credential != null && credential.getBody() != null) {
            // Parse credential body to mask the keys
            try {
                JsonObject credBody = JsonParser.parseString(credential.getBody()).getAsJsonObject();

                // Mask value if present
                if (credBody.has("value")) {
                    String originalValue = credBody.get("value").getAsString();
                    credBody.addProperty("value", maskCredential(originalValue));
                }

                // Build masked credential body
                JsonObject maskedCred = new JsonObject();
                maskedCred.addProperty("body", new Gson().toJson(credBody));
                maskedCred.addProperty("isValueRetrievable", credential.isValueRetrievable());
                json.add("credential", maskedCred);
            } catch (Exception e) {
                log.warn("Failed to parse credential body for masking", e);
            }
        }

        if (instruction != null && instruction.getBody() != null) {
            JsonObject invJson = new JsonObject();
            invJson.addProperty("body", instruction.getBody());
            json.add("invocationInstruction", invJson);
        }

        // Store selected option for regeneration
        if (context != null && context.getSelectedOption() != null) {
            json.addProperty("selectedOption", context.getSelectedOption());
        }

        return json.toString();
    }

    @Override
    public FederatedCredential extractCredentialFromReferenceArtifact(FederatedSubscriptionContext context) {
        FederatedCredential credential = new FederatedCredential();
        String subscriptionReferenceArtifact = context.getSubscriptionReferenceArtifact();
        
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
                    credential.setBody(credJson.get("body").getAsString());
                }
                if (credJson.has("isValueRetrievable")) {
                    credential.setValueRetrievable(credJson.get("isValueRetrievable").getAsBoolean());
                }
                // Mark as masked since this comes from reference artifact
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

    private String maskCredential(String credentialValue) {
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
    
    private UsagePlan findUsagePlanByName(String name) {
        // AWS doesn't support getUsagePlanByName directly. Need to list/filter.
        // Pagination caution: If there are many plans, this might be slow using getUsagePlans().
        // For now, simple implementation.
        // Verified: Usage Plans list supports limit/position, but not filtering by name serverside?
        // We might need to iterate.
        try {
            String position = null;
            do {
                GetUsagePlansRequest request = GetUsagePlansRequest.builder()
                        .limit(500) // Max limit is usually 500
                        .position(position)
                        .build();
                GetUsagePlansResponse response = apiGatewayClient.getUsagePlans(request);
                for (UsagePlan plan : response.items()) {
                    if (name.equals(plan.name())) {
                        return plan;
                    }
                }
                position = response.position();
            } while (position != null);
        } catch (Exception e) {
            log.error("Error finding usage plan: " + name, e);
        }
        return null;
    }

    @Override
    public FederatedSubscriptionOptions getSubscriptionOptions(FederatedSubscriptionContext context) 
            throws APIManagementException {
        try {
            String awsApiId = GatewayUtil.getAWSApiIdFromReferenceArtifact(context.getApiReferenceArtifact());
            
            // Find all usage plans associated with this API
            List<UsagePlan> matchingPlans = findUsagePlansForApi(awsApiId, this.stage);
            
            if (matchingPlans.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("No usage plans found for AWS API: " + awsApiId + ", stage: " + this.stage);
                }
                return null;  // No options available
            }

            // Build opaque options body
            JsonArray options = new JsonArray();
            for (UsagePlan plan : matchingPlans) {
                JsonObject opt = new JsonObject();
                opt.addProperty("id", plan.id());
                opt.addProperty("name", plan.name());
                opt.addProperty("description", plan.description() != null ? plan.description() : "");
                
                // Include throttle info for display
                if (plan.throttle() != null) {
                    opt.addProperty("rateLimit", plan.throttle().rateLimit());
                    opt.addProperty("burstLimit", plan.throttle().burstLimit());
                }
                
                // Include quota info for display
                if (plan.quota() != null) {
                    opt.addProperty("quotaLimit", plan.quota().limit());
                    opt.addProperty("quotaPeriod", plan.quota().period().toString());
                }
                
                options.add(opt);
            }

            JsonObject body = new JsonObject();
            body.add("options", options);
            body.addProperty("optionsType", "usage-plan");

            FederatedSubscriptionOptions result = new FederatedSubscriptionOptions();
            result.setBody(body.toString());
            result.setOptionsSchema("tier-selector");
            
            if (log.isDebugEnabled()) {
                log.debug("Found " + matchingPlans.size() + " usage plan options for API: " + awsApiId);
            }
            
            return result;
            
        } catch (Exception e) {
            log.error("Error getting subscription options for API: " + context.getApiName(), e);
            throw new APIManagementException("Failed to get subscription options from AWS: " + e.getMessage(), e);
        }
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

    private String extractUsagePlanIdFromArtifact(String referenceArtifact) {
        try {
            JsonObject json = JsonParser.parseString(referenceArtifact).getAsJsonObject();
            if (json.has("selectedOption")) {
                JsonObject selected = JsonParser.parseString(json.get("selectedOption").getAsString())
                        .getAsJsonObject();
                return selected.get("id").getAsString();
            }
        } catch (Exception e) {
            log.warn("Failed to extract usage plan ID from reference artifact", e);
        }
        return null;
    }
}

