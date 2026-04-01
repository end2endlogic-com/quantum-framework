package com.e2eq.framework.actionenablement.runtime;

import java.util.Collection;
import java.util.List;

final class CollectionUtils {

    private CollectionUtils() {
    }

    static <T> List<T> safeList(Collection<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
