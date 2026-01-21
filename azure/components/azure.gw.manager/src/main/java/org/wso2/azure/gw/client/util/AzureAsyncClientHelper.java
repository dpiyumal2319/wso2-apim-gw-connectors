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
import com.azure.resourcemanager.apimanagement.models.PolicyContract;
import com.azure.resourcemanager.apimanagement.models.PolicyIdName;
import com.azure.resourcemanager.apimanagement.models.ProductContract;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Helper class for async/parallel Azure API operations using Project Reactor.
 * Provides methods to parallelize product policy fetching to avoid N+1 query problems.
 */
public class AzureAsyncClientHelper {

    private static final Log log = LogFactory.getLog(AzureAsyncClientHelper.class);

    // Default concurrency limit to prevent Azure API rate limiting
    private static final int DEFAULT_CONCURRENCY = 10;
    // Default timeout for batch operations
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private AzureAsyncClientHelper() {
        // Utility class
    }

    /**
     * Fetches product policies for multiple products in parallel.
     *
     * @param manager       The Azure API Management Manager.
     * @param resourceGroup The Azure resource group.
     * @param serviceName   The Azure APIM service name.
     * @param products      List of products to fetch policies for.
     * @return Map of product name to PolicyContract.
     */
    public static Map<String, PolicyContract> fetchProductPoliciesInParallel(
            ApiManagementManager manager,
            String resourceGroup,
            String serviceName,
            List<ProductContract> products) {

        return fetchProductPoliciesInParallel(manager, resourceGroup, serviceName,
                products, DEFAULT_CONCURRENCY, DEFAULT_TIMEOUT);
    }

    /**
     * Fetches product policies for multiple products in parallel with configurable settings.
     *
     * @param manager       The Azure API Management Manager.
     * @param resourceGroup The Azure resource group.
     * @param serviceName   The Azure APIM service name.
     * @param products      List of products to fetch policies for.
     * @param concurrency   Maximum number of parallel requests.
     * @param timeout       Timeout duration for the entire operation.
     * @return Map of product name to PolicyContract.
     */
    public static Map<String, PolicyContract> fetchProductPoliciesInParallel(
            ApiManagementManager manager,
            String resourceGroup,
            String serviceName,
            List<ProductContract> products,
            int concurrency,
            Duration timeout) {

        if (products == null || products.isEmpty()) {
            return Map.of();
        }

        if (log.isDebugEnabled()) {
            log.debug("Fetching policies for " + products.size() + " products in parallel");
        }

        return Flux.fromIterable(products)
                .flatMap(product -> fetchProductPolicyAsync(
                        manager, resourceGroup, serviceName, product), concurrency)
                .collectList()
                .map(entries -> entries.stream()
                        .filter(entry -> entry.getValue() != null)
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (v1, v2) -> v1)))
                .block(timeout);
    }

    /**
     * Asynchronously fetches policy for a single product.
     */
    private static Mono<AbstractMap.SimpleEntry<String, PolicyContract>> fetchProductPolicyAsync(
            ApiManagementManager manager,
            String resourceGroup,
            String serviceName,
            ProductContract product) {

        return Mono.fromCallable(() -> {
                    PolicyContract policy = manager.productPolicies().get(
                            resourceGroup, serviceName, product.name(), PolicyIdName.POLICY);
                    return new AbstractMap.SimpleEntry<>(product.name(), policy);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    // Product might not have a policy defined - this is normal
                    if (log.isDebugEnabled()) {
                        log.debug("No policy found for product: " + product.name());
                    }
                    return Mono.just(new AbstractMap.SimpleEntry<>(product.name(), (PolicyContract) null));
                });
    }
}
