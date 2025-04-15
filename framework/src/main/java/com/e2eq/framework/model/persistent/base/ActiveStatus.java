package com.e2eq.framework.model.persistent.base;

import com.e2eq.framework.model.general.AddressRole;

public enum ActiveStatus {
    ACTIVE,
    INACTIVE,
    DELETED;

    public String value() {
        return name();
    }

    public static ActiveStatus fromValue(String v) {
        return valueOf(v);
    }

}
