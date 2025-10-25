package com.e2eq.framework.model.persistent.morphia.metadata;

/**
 * Join specification for an expand(path) hop.
 */
public class JoinSpec {
    public final String fromCollection;
    public final String remoteIdField;
    public final String localIdExpr;
    public final String tenantField;
    public final boolean localIsArray;

    public JoinSpec(String fromCollection, String remoteIdField, String localIdExpr, String tenantField, boolean localIsArray) {
        this.fromCollection = fromCollection;
        this.remoteIdField = remoteIdField;
        this.localIdExpr = localIdExpr;
        this.tenantField = tenantField;
        this.localIsArray = localIsArray;
    }
}
