package com.e2eq.framework.model.general;

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
