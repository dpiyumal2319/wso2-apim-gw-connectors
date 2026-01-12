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

package org.wso2.azure.gw.client.model;

/**
 * Model class representing Azure subscription key metadata.
 * This class stores credential metadata for display purposes without exposing actual secrets.
 */
public class AzureSubscriptionKeyInfo {

    private String keyType;
    private String externalKeyReference;
    private String maskedValue;
    private String state;

    public AzureSubscriptionKeyInfo() {
    }

    public AzureSubscriptionKeyInfo(String keyType, String externalKeyReference, String maskedValue, String state) {
        this.keyType = keyType;
        this.externalKeyReference = externalKeyReference;
        this.maskedValue = maskedValue;
        this.state = state;
    }

    public String getKeyType() {
        return keyType;
    }

    public void setKeyType(String keyType) {
        this.keyType = keyType;
    }

    public String getExternalKeyReference() {
        return externalKeyReference;
    }

    public void setExternalKeyReference(String externalKeyReference) {
        this.externalKeyReference = externalKeyReference;
    }

    public String getMaskedValue() {
        return maskedValue;
    }

    public void setMaskedValue(String maskedValue) {
        this.maskedValue = maskedValue;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    /**
     * Masks a subscription key, showing only the last 4 characters.
     *
     * @param key The subscription key to mask.
     * @return The masked key value with only last 4 characters visible.
     */
    public static String maskKeyValue(String key) {
        if (key == null || key.isEmpty()) {
            return "••••••••";
        }
        if (key.length() <= 4) {
            return "••••" + key;
        }
        return "••••••••" + key.substring(key.length() - 4);
    }
}

