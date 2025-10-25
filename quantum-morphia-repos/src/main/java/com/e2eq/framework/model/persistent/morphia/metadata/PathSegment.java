package com.e2eq.framework.model.persistent.morphia.metadata;

public class PathSegment {
    public final String name;
    public final boolean array;

    public PathSegment(String name, boolean array) {
        this.name = name;
        this.array = array;
    }
}
