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
import org.wso2.carbon.apimgt.api.model.FederatedSubscriptionRequest;
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
import software.amazon.awssdk.services.apigateway.model.UsagePlan;

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
    public FederatedCredential createSubscription(FederatedSubscriptionRequest request) throws APIManagementException {
        try {
            String subUuid = request.getSubscriptionUuid();
            String apiKeyName = "wso2_" + subUuid;

            // 1. Create API Key
            CreateApiKeyRequest createApiKeyRequest = CreateApiKeyRequest.builder()
                    .name(apiKeyName)
                    .enabled(true)
                    .description("WSO2 Subscription: " + subUuid)
                    .build();
            CreateApiKeyResponse apiKeyResponse = apiGatewayClient.createApiKey(createApiKeyRequest);
            String apiKeyId = apiKeyResponse.id();
            String apiKeyValue = apiKeyResponse.value();

            // 2. Find Usage Plan (Name: wso2_{apiUUID})
            String awsApiId = GatewayUtil.getAWSApiIdFromReferenceArtifact(request.getReferenceArtifact());
            String apiUuid = request.getApiUuid(); // Assuming we use API UUID for usage plan name
            String usagePlanName = "wso2_" + apiUuid;

            UsagePlan usagePlan = findUsagePlanByName(usagePlanName);
            if (usagePlan == null) {
                // Determine if we should fail or try to find by stage...
                // Strict design: fail if Usage Plan not found. It should be created during deployment.
                // However, to be robust, we might fallback or throw meaningful error.
                throw new APIManagementException("Usage Plan not found for API: " + usagePlanName +
                        ". Ensure API is deployed correctly.");
            }

            // 3. Associate API Key with Usage Plan
            CreateUsagePlanKeyRequest planKeyRequest = CreateUsagePlanKeyRequest.builder()
                    .usagePlanId(usagePlan.id())
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
            credential.setValueRetrievable(false); // AWS doesn't allow retrieving value later
            credential.setMasked(false);

            return credential;

        } catch (Exception e) {
            // Cleanup on failure? AWS operations are not transactional.
            // Ideally we should try to delete the created key if association fails.
            throw new APIManagementException("Error creating subscription on AWS: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteSubscription(String externalSubscriptionId) throws APIManagementException {
        try {
            if (StringUtils.isNotEmpty(externalSubscriptionId)) {
                DeleteApiKeyRequest deleteRequest = DeleteApiKeyRequest.builder()
                        .apiKey(externalSubscriptionId)
                        .build();
                apiGatewayClient.deleteApiKey(deleteRequest);
            }
        } catch (Exception e) {
            // Log and ignore 404?
            throw new APIManagementException("Error deleting subscription on AWS: " + e.getMessage(), e);
        }
    }

    @Override
    public InvocationInstruction getInvocationInstruction(String referenceArtifact) {
        JsonObject invBody = new JsonObject();
        invBody.addProperty("invocationSchema", "header-based");
        invBody.addProperty("headerName", HEADER_NAME);
        
        // Extract AWS API ID from reference artifact and build real execution URL
        String baseUrl = "https://{api-url}";
        String basePath = "/{stage}";
        String curlExampleHeader = "curl -H '" + HEADER_NAME + ": {apiKey}' https://{api-url}/{stage}";
        
        try {
            String awsApiId = GatewayUtil.getAWSApiIdFromReferenceArtifact(referenceArtifact);
            baseUrl = "https://" + awsApiId + ".execute-api." + region + ".amazonaws.com";
            basePath = "/" + (stage != null ? stage : "{stage}");
            curlExampleHeader = "curl -H '" + HEADER_NAME + ": {apiKey}' " + baseUrl + basePath;
        } catch (Exception e) {
            log.warn("Failed to extract AWS API ID from reference artifact, using placeholder URL", e);
        }
        
        invBody.addProperty("baseUrl", baseUrl);
        invBody.addProperty("basePath", basePath);
        invBody.addProperty("curlExampleHeader", curlExampleHeader);

        InvocationInstruction instruction = new InvocationInstruction();
        instruction.setBody(invBody.toString());
        return instruction;
    }

    @Override
    public String[] getSupportedAuthTypes(String apiReferenceArtifact) {
        // In our design, all deployed APIs on AWS via this connector require API Key.
        // We could check the artifact or query AWS to see if 'apiKeyRequired' is true on methods.
        // For efficiency, we assume strict mode from our design: Supported.
        return new String[]{CREDENTIAL_TYPE};
    }
    
    @Override
    public FederatedCredential retrieveCredential(String externalSubscriptionId) throws APIManagementException {
        // AWS secrets are not retrievable after creation (only returns masked).
        // Return null or masked.
        return null;
    }

    @Override
    public String buildSubscriptionReferenceArtifact(FederatedCredential credential,
                                                      InvocationInstruction instruction) {
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
        } catch (Exception e) {
            log.warn("Failed to parse subscription reference artifact", e);
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
}
