package com.e2eq.framework.annotations.support;

import com.e2eq.framework.annotations.FunctionalMapping;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Resolves {@link FunctionalMapping} for model classes with per-class caching.
 * Uses ClassValue to tie cache lifecycle to the defining ClassLoader.
 */
public final class FunctionalMappingResolver {

    private FunctionalMappingResolver() {}

    private static final ClassValue<FunctionalMappingInfo> CACHE = new ClassValue<>() {
        @Override
        protected FunctionalMappingInfo computeValue(Class<?> type) {
            FunctionalMapping fm = type.getAnnotation(FunctionalMapping.class);
            if (fm != null) {
                return FunctionalMappingInfo.annotated(fm.area(), fm.domain());
            }
            // Negative cache: remember that this class has no annotation
            return FunctionalMappingInfo.noAnnotation();
        }
    };

    /**
     * Resolve functional mapping for a model class. If the class has {@link FunctionalMapping},
     * the annotated values are returned. Otherwise, the provided fallbacks are evaluated once and returned.
     */
    public static @NotNull FunctionalMappingInfo resolve(@NotNull Class<?> modelClass,
                                                         @NotNull Supplier<String> areaFallback,
                                                         @NotNull Supplier<String> domainFallback) {
        Objects.requireNonNull(modelClass, "modelClass");
        FunctionalMappingInfo cached = CACHE.get(modelClass);
        if (cached.fromAnnotation) {
            return cached;
        }
        // No annotation: materialize fallbacks just once per invocation.
        return new FunctionalMappingInfo(areaFallback.get(), domainFallback.get(), false);
    }
}
