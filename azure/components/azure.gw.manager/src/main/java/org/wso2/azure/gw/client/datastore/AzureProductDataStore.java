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

package org.wso2.azure.gw.client.datastore;

import com.azure.core.http.rest.PagedIterable;
import com.azure.core.util.Context;
import com.azure.resourcemanager.apimanagement.ApiManagementManager;
import com.azure.resourcemanager.apimanagement.models.PolicyContract;
import com.azure.resourcemanager.apimanagement.models.ProductContract;
import lombok.Getter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.azure.gw.client.AzureConstants;
import org.wso2.azure.gw.client.util.AzureAsyncClientHelper;
import org.wso2.azure.gw.client.util.AzurePolicyParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cache for Azure product-to-tier mappings to avoid N+1 queries.
 * Uses lazy initialization to fetch and cache product policies.
 */
public class AzureProductDataStore {

    private static final Log log = LogFactory.getLog(AzureProductDataStore.class);

    private final ApiManagementManager manager;
    private final String resourceGroup;
    private final String serviceName;

    // Cache for product ID to tier mapping
    private final Map<String, String> productTierCache = new HashMap<>();
    // Cache for product ID to product name mapping
    private final Map<String, String> productNameCache = new HashMap<>();
    // Cache for product ID to rate limit info
    private final Map<String, Map<String, Integer>> productRateLimitCache = new HashMap<>();

    /**
     * -- GETTER --
     * Checks if the cache has been initialized.
     *
     * @return true if the cache is initialized, false otherwise.
     */
    @Getter
    private boolean initialized = false;

    public AzureProductDataStore(ApiManagementManager manager, String resourceGroup, String serviceName) {
        this.manager = manager;
        this.resourceGroup = resourceGroup;
        this.serviceName = serviceName;
    }

    /**
     * Initializes the product cache by fetching all products and their policies in parallel.
     * Uses async parallel fetching to avoid N+1 query problem.
     */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }

        try {
            if (log.isDebugEnabled()) {
                log.debug("Initializing Azure Product Data Store for service: " + serviceName);
            }

            // Fetch all products first
            PagedIterable<ProductContract> productsIterable = manager.products().listByService(
                    resourceGroup, serviceName,
                    null, // filter
                    null, // top
                    null, // skip
                    null, // expandGroups
                    null, // tags
                    Context.NONE);

            // Collect all products into a list for parallel processing
            List<ProductContract> productList = new ArrayList<>();
            productsIterable.streamByPage().forEach(resp -> {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("Fetched product page. Headers: %s. Status: %d",
                            resp.getHeaders(), resp.getStatusCode()));
                }
                resp.getElements().forEach(product -> {
                    productList.add(product);
                    // Cache product name immediately
                    productNameCache.put(product.name(), product.displayName());
                });
            });

            if (log.isDebugEnabled()) {
                log.debug("Fetched " + productList.size() + " products, now fetching policies in parallel");
            }

            // Fetch all product policies in parallel
            Map<String, PolicyContract> policyMap = AzureAsyncClientHelper.fetchProductPoliciesInParallel(
                    manager, resourceGroup, serviceName, productList);

            // Process policies and build tier cache
            for (ProductContract product : productList) {
                String productId = product.name();
                PolicyContract policy = policyMap.get(productId);

                if (policy != null && policy.value() != null) {
                    Map<String, Integer> rateLimitInfo = AzurePolicyParser
                            .parseRateLimitFromPolicy(policy.value());

                    if (!rateLimitInfo.isEmpty()) {
                        productRateLimitCache.put(productId, rateLimitInfo);

                        int calls = rateLimitInfo.getOrDefault("calls", 0);
                        int renewalPeriod = rateLimitInfo.getOrDefault("renewal-period", 60);
                        String tier = AzurePolicyParser.mapToWSO2Tier(calls, renewalPeriod);
                        productTierCache.put(productId, tier);
                    } else {
                        productTierCache.put(productId, AzureConstants.AZURE_DEFAULT_TIER);
                    }
                } else {
                    productTierCache.put(productId, AzureConstants.AZURE_DEFAULT_TIER);
                }
            }

            initialized = true;
            if (log.isDebugEnabled()) {
                log.debug("Azure Product Data Store initialized with " + productTierCache.size() + " products");
            }

        } catch (Exception e) {
            log.error("Error initializing Azure Product Data Store", e);
        }
    }

    /**
     * Gets the WSO2 tier for a given Azure product ID.
     *
     * @param productId The Azure product ID.
     * @return The mapped WSO2 tier name, or "Unlimited" if not found.
     */
    public String getTierForProduct(String productId) {
        if (!initialized) {
            initialize();
        }
        return productTierCache.getOrDefault(productId, AzureConstants.AZURE_DEFAULT_TIER);
    }

    /**
     * Gets the display name for a given Azure product ID.
     *
     * @param productId The Azure product ID.
     * @return The product display name, or the product ID if not found.
     */
    public String getProductName(String productId) {
        if (!initialized) {
            initialize();
        }
        return productNameCache.getOrDefault(productId, productId);
    }

    /**
     * Gets the rate limit information for a given Azure product ID.
     *
     * @param productId The Azure product ID.
     * @return A map containing 'calls' and 'renewal-period', or empty map if not
     *         found.
     */
    public Map<String, Integer> getRateLimitInfo(String productId) {
        if (!initialized) {
            initialize();
        }
        return productRateLimitCache.getOrDefault(productId, new HashMap<>());
    }

    /**
     * Clears the cache, forcing reinitialization on next access.
     */
    public synchronized void clearCache() {
        productTierCache.clear();
        productNameCache.clear();
        productRateLimitCache.clear();
        initialized = false;
    }

}
