package com.e2eq.framework.model.persistent.base;

public enum AddressRole {
    shipto,
    billto,
    contact;

    public String value() {
        return name();
    }

    public static AddressRole fromValue(String v) {
        return valueOf(v);
    }
}
