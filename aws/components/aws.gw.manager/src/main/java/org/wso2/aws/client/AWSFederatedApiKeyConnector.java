/*
 * Copyright (c) 2026 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.aws.client.util.GatewayUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.FederatedApiKeyConnector;
import org.wso2.carbon.apimgt.api.model.FederatedApiKeyCreationResult;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.FederatedApiKeyContext;
import org.wso2.carbon.apimgt.api.model.ExternalSubscriptionPolicy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.CreateApiKeyRequest;
import software.amazon.awssdk.services.apigateway.model.CreateApiKeyResponse;
import software.amazon.awssdk.services.apigateway.model.CreateUsagePlanKeyRequest;
import software.amazon.awssdk.services.apigateway.model.DeleteApiKeyRequest;
import software.amazon.awssdk.services.apigateway.model.DeleteUsagePlanKeyRequest;
import software.amazon.awssdk.services.apigateway.model.ConflictException;
import software.amazon.awssdk.services.apigateway.model.GetUsagePlansRequest;
import software.amazon.awssdk.services.apigateway.model.GetUsagePlansResponse;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;
import software.amazon.awssdk.services.apigateway.model.UsagePlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS implementation of federated API key management.
 */
public class AWSFederatedApiKeyConnector implements FederatedApiKeyConnector {

    private static final Log log = LogFactory.getLog(AWSFederatedApiKeyConnector.class);
    private static final int MAX_TAG_LENGTH = 256;
    private static final String API_KEY_ID = "apiKeyId";
    private static final String USAGE_PLAN_ID = "usagePlanId";

    private static final String TAG_API_ID = "wso2:api-id";
    private static final String TAG_API_UUID = "wso2:api-uuid";
    private static final String TAG_KEY_UUID = "wso2:key-uuid";
    private static final String TAG_AUTHZ_USER = "wso2:authz-user";
    private static final String TAG_ORGANIZATION = "wso2:organization";
    private static final String TAG_VALIDITY_PERIOD = "wso2:key-validity-period";
    private static final String TAG_PERMITTED_IP = "wso2:key-permitted-ip";
    private static final String TAG_PERMITTED_REFERER = "wso2:key-permitted-referer";
    private static final String USAGE_PLAN_KEY_TYPE_API_KEY = "API_KEY";

    private ApiGatewayClient apiGatewayClient;

    /**
     * Returns the gateway type handled by this connector.
     */
    @Override
    public String getGatewayType() {
        return AWSConstants.AWS_TYPE;
    }

    /**
     * Indicates that AWS federated API-key provisioning is supported.
     */
    @Override
    public boolean isApiKeySupport() {
        return true;
    }

    /**
     * Initializes the AWS API Gateway client from the environment credentials and region.
     */
    @Override
    public void init(Environment environment, String organization) throws APIManagementException {
        try {
            String region = environment.getAdditionalProperties().get(AWSConstants.AWS_ENVIRONMENT_REGION);
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
            throw new APIManagementException("Error occurred while initializing AWS Federated API Key Connector", e);
        }
    }

    /**
     * Creates an AWS API key using the caller-provided local API-key value and returns the AWS API key ID.
     */
    @Override
    public FederatedApiKeyCreationResult createApiKey(FederatedApiKeyContext context) throws APIManagementException {
        if (StringUtils.isBlank(context.getApiKeyValue())) {
            throw new APIManagementException("API key value is required to create AWS API key");
        }
        try {
            String awsApiId = GatewayUtil.getAWSApiIdFromReferenceArtifact(context.getApiReferenceArtifact());
            CreateApiKeyRequest request = CreateApiKeyRequest.builder()
                    .name(context.getApiKeyName())
                    .value(context.getApiKeyValue())
                    .enabled(true)
                    .description("WSO2 API Key UUID: " + context.getApiKeyUuid())
                    .tags(buildTags(context, awsApiId))
                    .build();
            CreateApiKeyResponse response = apiGatewayClient.createApiKey(request);
            
            return FederatedApiKeyCreationResult.builder()
                    .referenceArtifact(buildApiKeyReferenceArtifact(response.id()))
                    .build();
        } catch (Exception e) {
            throw new APIManagementException("Error creating API key in AWS", e);
        }
    }

    /**
     * Replaces an AWS API key by creating a new key, migrating the mapped usage-plan association, and deleting the old
     * key. AWS API Gateway does not support patching an API key's value in place.
     */
    public FederatedApiKeyCreationResult replaceApiKey(FederatedApiKeyContext context) throws APIManagementException {
        if (context == null || StringUtils.isBlank(context.getApiKeyValue())) {
            throw new APIManagementException("API key value is required to replace AWS API key");
        }
        FederatedApiKeyCreationResult result = createApiKey(context);
        if (result == null || StringUtils.isBlank(result.getReferenceArtifact())) {
            throw new APIManagementException("AWS API key replacement did not return a reference artifact");
        }
        FederatedApiKeyContext newKeyContext = copyContextWithApiKeyReferenceArtifact(context,
                result.getReferenceArtifact());
        try {
            if (StringUtils.isNotBlank(context.getRemotePolicyReference())) {
                applyRateLimitPolicy(newKeyContext, context.getRemotePolicyReference());
            }
        } catch (APIManagementException e) {
            revokeApiKey(newKeyContext);
            throw e;
        }
        revokeApiKey(context);
        return result;
    }

    /**
     * Deletes the AWS API key identified by the stored connector-owned reference artifact.
     */
    @Override
    public void revokeApiKey(FederatedApiKeyContext context) throws APIManagementException {
        String apiKeyId = resolveApiKeyId(context);
        if (StringUtils.isBlank(apiKeyId)) {
            return;
        }
        try {
            DeleteApiKeyRequest request = DeleteApiKeyRequest.builder()
                    .apiKey(apiKeyId)
                    .build();
            apiGatewayClient.deleteApiKey(request);
        } catch (Exception e) {
            throw new APIManagementException("Error revoking API key in AWS", e);
        }
    }

    /**
     * Associates the AWS API key with the usage plan encoded in the connector-owned remote plan reference.
     */
    @Override
    public void applyRateLimitPolicy(FederatedApiKeyContext context, String remotePolicyReference)
            throws APIManagementException {
        String apiKeyId = resolveApiKeyId(context);
        if (StringUtils.isBlank(apiKeyId)) {
            throw new APIManagementException("Remote API key ID is required for rate limit policy association");
        }
        String policyId = resolveRemotePolicyId(remotePolicyReference);
        if (StringUtils.isBlank(policyId)) {
            throw new APIManagementException("Remote policy ID is required for association");
        }
        try {
            CreateUsagePlanKeyRequest request = CreateUsagePlanKeyRequest.builder()
                    .usagePlanId(policyId)
                    .keyId(apiKeyId)
                    .keyType(USAGE_PLAN_KEY_TYPE_API_KEY)
                    .build();
            apiGatewayClient.createUsagePlanKey(request);
        } catch (ConflictException e) {
            if (log.isDebugEnabled()) {
                log.debug("AWS API key is already associated with usage plan: " + policyId, e);
            }
        } catch (Exception e) {
            throw new APIManagementException("Error associating API key with usage plan in AWS", e);
        }
    }

    /**
     * Removes the AWS API key from the usage plan encoded in the connector-owned remote plan reference.
     */
    @Override
    public void removeRateLimitPolicy(FederatedApiKeyContext context, String remotePolicyReference)
            throws APIManagementException {
        String apiKeyId = resolveApiKeyId(context);
        if (StringUtils.isBlank(apiKeyId)) {
            return;
        }
        String policyId = resolveRemotePolicyId(remotePolicyReference);
        if (StringUtils.isBlank(policyId)) {
            throw new APIManagementException("Remote policy ID is required for rate limit policy removal");
        }
        try {
            DeleteUsagePlanKeyRequest deleteRequest = DeleteUsagePlanKeyRequest.builder()
                    .usagePlanId(policyId)
                    .keyId(apiKeyId)
                    .build();
            apiGatewayClient.deleteUsagePlanKey(deleteRequest);
        } catch (NotFoundException e) {
            if (log.isDebugEnabled()) {
                log.debug("AWS API key is not associated with usage plan: " + policyId, e);
            }
        } catch (Exception e) {
            throw new APIManagementException("Error removing API key usage plan association in AWS", e);
        }
    }

    private FederatedApiKeyContext copyContextWithApiKeyReferenceArtifact(FederatedApiKeyContext context,
                                                                          String apiKeyReferenceArtifact) {
        return FederatedApiKeyContext.builder()
                .apiUuid(context.getApiUuid())
                .apiName(context.getApiName())
                .apiReferenceArtifact(context.getApiReferenceArtifact())
                .apiKeyUuid(context.getApiKeyUuid())
                .apiKeyName(context.getApiKeyName())
                .apiKeyValue(context.getApiKeyValue())
                .apiKeyReferenceArtifact(apiKeyReferenceArtifact)
                .remotePolicyReference(context.getRemotePolicyReference())
                .authzUser(context.getAuthzUser())
                .applicationUuid(context.getApplicationUuid())
                .organizationId(context.getOrganizationId())
                .environmentId(context.getEnvironmentId())
                .validityPeriod(context.getValidityPeriod())
                .permittedIP(context.getPermittedIP())
                .permittedReferer(context.getPermittedReferer())
                .build();
    }

    private String buildApiKeyReferenceArtifact(String apiKeyId) {
        com.google.gson.JsonObject referenceArtifact = new com.google.gson.JsonObject();
        referenceArtifact.addProperty(API_KEY_ID, apiKeyId);
        return referenceArtifact.toString();
    }

    private String resolveApiKeyId(FederatedApiKeyContext context) throws APIManagementException {
        if (context == null || StringUtils.isBlank(context.getApiKeyReferenceArtifact())) {
            return null;
        }
        try {
            com.google.gson.JsonObject referenceArtifact = com.google.gson.JsonParser
                    .parseString(context.getApiKeyReferenceArtifact()).getAsJsonObject();
            if (!referenceArtifact.has(API_KEY_ID) || referenceArtifact.get(API_KEY_ID).isJsonNull()
                    || StringUtils.isBlank(referenceArtifact.get(API_KEY_ID).getAsString())) {
                throw new APIManagementException("AWS API key reference artifact must contain apiKeyId");
            }
            return referenceArtifact.get(API_KEY_ID).getAsString();
        } catch (APIManagementException e) {
            throw e;
        } catch (Exception e) {
            throw new APIManagementException("Invalid AWS API key reference artifact", e);
        }
    }

    /**
     * Extracts the AWS usage plan ID from the strict connector-owned remote plan reference.
     */
    private String resolveRemotePolicyId(String remotePolicyReference) throws APIManagementException {
        if (StringUtils.isBlank(remotePolicyReference)) {
            return null;
        }
        try {
            com.google.gson.JsonObject policyJson = com.google.gson.JsonParser.parseString(remotePolicyReference)
                    .getAsJsonObject();
            if (!policyJson.has(USAGE_PLAN_ID) || policyJson.get(USAGE_PLAN_ID).isJsonNull()
                    || StringUtils.isBlank(policyJson.get(USAGE_PLAN_ID).getAsString())) {
                throw new APIManagementException("AWS remote policy reference must contain usagePlanId");
            }
            return policyJson.get(USAGE_PLAN_ID).getAsString();
        } catch (APIManagementException e) {
            throw e;
        } catch (Exception e) {
            throw new APIManagementException("Invalid AWS remote policy reference", e);
        }
    }

    /**
     * Lists AWS usage plans as remote subscription policies for Admin plan mapping.
     */
    @Override
    public List<ExternalSubscriptionPolicy> listRateLimitPolicies(Environment environment) throws APIManagementException {
        List<ExternalSubscriptionPolicy> rateLimitPolicies = new ArrayList<>();
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
                    ExternalSubscriptionPolicy policy = new ExternalSubscriptionPolicy(plan.id(), plan.name(),
                            plan.description() != null ? plan.description() : "", limits);
                    policy.setReference(buildRemotePolicyReference(plan.id()));
                    rateLimitPolicies.add(policy);
                }
                position = response.position();
            } while (position != null);
        } catch (Exception e) {
            throw new APIManagementException("Failed to list AWS Usage Plans: " + e.getMessage(), e);
        }
        return rateLimitPolicies;
    }

    /**
     * Builds the opaque remote plan reference persisted by API Manager and later returned to this connector.
     */
    private String buildRemotePolicyReference(String policyId) {
        com.google.gson.JsonObject policyReference = new com.google.gson.JsonObject();
        policyReference.addProperty(USAGE_PLAN_ID, policyId);
        return policyReference.toString();
    }

    /**
     * Indicates that AWS can list remote usage plans for Admin plan mapping.
     */
    @Override
    public boolean supportsRemotePlanListing() {
        return true;
    }

    /**
     * Builds AWS tags that keep enough WSO2 context on the remote API key for traceability.
     */
    private Map<String, String> buildTags(FederatedApiKeyContext context, String awsApiId) {
        Map<String, String> tags = new HashMap<>();
        putTag(tags, TAG_API_ID, awsApiId);
        putTag(tags, TAG_API_UUID, context.getApiUuid());
        putTag(tags, TAG_KEY_UUID, context.getApiKeyUuid());
        putTag(tags, TAG_AUTHZ_USER, context.getAuthzUser());
        putTag(tags, TAG_ORGANIZATION, context.getOrganizationId());
        if (context.getValidityPeriod() != null) {
            putTag(tags, TAG_VALIDITY_PERIOD, String.valueOf(context.getValidityPeriod()));
        }
        putTag(tags, TAG_PERMITTED_IP, context.getPermittedIP());
        putTag(tags, TAG_PERMITTED_REFERER, context.getPermittedReferer());
        return tags;
    }

    /**
     * Adds a bounded AWS tag value when both key and value are present.
     */
    private void putTag(Map<String, String> tags, String key, String value) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_TAG_LENGTH) {
            trimmed = trimmed.substring(0, MAX_TAG_LENGTH);
        }
        tags.put(key, trimmed);
    }
}
