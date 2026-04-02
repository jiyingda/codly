/**
 * @(#)Parameters.java, 4 月 2, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parameters 定义
 */
@SuppressWarnings("unused")
public class Parameters {
    private String type;
    private Map<String, Property> properties;
    private List<String> required;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Property> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Property> properties) {
        this.properties = properties;
    }

    public List<String> getRequired() {
        return required;
    }

    public void setRequired(List<String> required) {
        this.required = required;
    }

    public static Parameters create() {
        Parameters params = new Parameters();
        params.setType("object");
        params.setProperties(new HashMap<>());
        params.setRequired(new ArrayList<>());
        return params;
    }

    public Parameters addProperty(String name, String type, String description) {
        Property property = new Property();
        property.setType(type);
        property.setDescription(description);
        this.properties.put(name, property);
        return this;
    }

    public Parameters addRequired(String name) {
        this.required.add(name);
        return this;
    }
}

