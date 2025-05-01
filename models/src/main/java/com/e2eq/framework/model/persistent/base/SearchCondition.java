package com.e2eq.framework.model.persistent.base;

public enum SearchCondition {
    AND,
    OR,
    NOT;

    public static SearchCondition fromValue(String conditionType) {
        return SearchCondition.valueOf(conditionType);
    }

    public String value() {
        return name();
    }
}
