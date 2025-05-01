package com.e2eq.framework.model.persistent.base;


public enum DynamicAttributeType {
        String,
        Text,
        Integer,
        Long,
        Float,
        Double,
        Date,
        Object,
        DateTime,
        Boolean,
        Select,
        MultiSelect,
        Regex,
        Exclude,
        ObjectRef;

        public String value() {
            return name();
        }

        public static DynamicAttributeType fromValue(String v) {
            return valueOf(v);
        }
}
