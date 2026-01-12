/*
 * Copyright (c) 2025 WSO2 LLC.[](http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.azure.gw.client.util;


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;


import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;


/**
 * Utility class for parsing Azure API Management policy XML.
 * Extracts rate limit information from product policies.
 */
public class AzurePolicyParser {

    private static final Log log = LogFactory.getLog(AzurePolicyParser.class);

    private static final String RATE_LIMIT_TAG = "rate-limit";
    private static final String RATE_LIMIT_BY_KEY_TAG = "rate-limit-by-key";
    private static final String QUOTA_TAG = "quota";
    private static final String CALLS_ATTR = "calls";
    private static final String RENEWAL_PERIOD_ATTR = "renewal-period";

    /**
     * Parses rate limit information from Azure product policy XML.
     *
     * @param policyXml The policy XML string to parse.
     * @return A map containing 'calls' and 'renewalPeriod' values, or empty map if not found.
     */
    public static Map<String, Integer> parseRateLimitFromPolicy(String policyXml) {
        Map<String, Integer> rateLimitInfo = new HashMap<>();

        if (policyXml == null || policyXml.isEmpty()) {
            return rateLimitInfo;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Disable external entities to prevent XXE attacks
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(policyXml)));

            // Try to find rate-limit element first
            NodeList rateLimitNodes = document.getElementsByTagName(RATE_LIMIT_TAG);
            if (rateLimitNodes.getLength() > 0) {
                Element rateLimitElement = (Element) rateLimitNodes.item(0);
                extractRateLimitValues(rateLimitElement, rateLimitInfo);
                if (!rateLimitInfo.isEmpty()) {
                    return rateLimitInfo;
                }
            }

            // Try rate-limit-by-key if rate-limit not found
            NodeList rateLimitByKeyNodes = document.getElementsByTagName(RATE_LIMIT_BY_KEY_TAG);
            if (rateLimitByKeyNodes.getLength() > 0) {
                Element rateLimitByKeyElement = (Element) rateLimitByKeyNodes.item(0);
                extractRateLimitValues(rateLimitByKeyElement, rateLimitInfo);
                if (!rateLimitInfo.isEmpty()) {
                    return rateLimitInfo;
                }
            }

            // Try quota as fallback
            NodeList quotaNodes = document.getElementsByTagName(QUOTA_TAG);
            if (quotaNodes.getLength() > 0) {
                Element quotaElement = (Element) quotaNodes.item(0);
                extractRateLimitValues(quotaElement, rateLimitInfo);
            }

        } catch (ParserConfigurationException | SAXException | IOException e) {
            log.error("Error occurred while parsing Azure policy XML", e);
            // Using empty map indicates no rate limit found
        }

        return rateLimitInfo;
    }

    /**
     * Extracts calls and renewal period values from a rate limit XML element.
     *
     * @param element       The XML element containing rate limit attributes.
     * @param rateLimitInfo The map to populate with extracted values.
     */
    private static void extractRateLimitValues(Element element, Map<String, Integer> rateLimitInfo) {
        String callsStr = element.getAttribute(CALLS_ATTR);
        String renewalPeriodStr = element.getAttribute(RENEWAL_PERIOD_ATTR);

        if (callsStr != null && !callsStr.isEmpty()) {
            try {
                rateLimitInfo.put(CALLS_ATTR, Integer.parseInt(callsStr));
            } catch (NumberFormatException e) {
                // Ignore invalid number
            }
        }

        if (renewalPeriodStr != null && !renewalPeriodStr.isEmpty()) {
            try {
                rateLimitInfo.put(RENEWAL_PERIOD_ATTR, Integer.parseInt(renewalPeriodStr));
            } catch (NumberFormatException e) {
                // Ignore invalid number
            }
        }
    }

    /**
     * Maps Azure rate limit values to a WSO2 tier name.
     *
     * @param calls                The number of calls allowed.
     * @param renewalPeriodSeconds The renewal period in seconds.
     * @return The corresponding WSO2 tier name.
     */
    public static String mapToWSO2Tier(int calls, int renewalPeriodSeconds) {
        // Calculate requests per minute for consistent comparison
        double requestsPerMinute = (calls * 60.0) / renewalPeriodSeconds;

        // Map to standard WSO2 tiers based on requests per minute
        if (requestsPerMinute <= 1) {
            return "Bronze";
        } else if (requestsPerMinute <= 5) {
            return "Silver";
        } else if (requestsPerMinute <= 20) {
            return "Gold";
        } else if (requestsPerMinute <= 50) {
            return "Platinum";
        } else {
            return "Unlimited";
        }
    }

    /**
     * Generates a tier description from rate limit values.
     *
     * @param calls                The number of calls allowed.
     * @param renewalPeriodSeconds The renewal period in seconds.
     * @return A human-readable tier description.
     */
    public static String generateTierDescription(int calls, int renewalPeriodSeconds) {
        if (renewalPeriodSeconds == 60) {
            return calls + " requests per minute";
        } else if (renewalPeriodSeconds == 3600) {
            return calls + " requests per hour";
        } else if (renewalPeriodSeconds == 86400) {
            return calls + " requests per day";
        } else {
            return calls + " requests per " + renewalPeriodSeconds + " seconds";
        }
    }
}
