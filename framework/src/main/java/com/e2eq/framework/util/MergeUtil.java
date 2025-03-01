package com.e2eq.framework.util;

import org.apache.commons.beanutils.BeanUtils;

import java.lang.reflect.InvocationTargetException;

public class MergeUtil {

    public static <T> T merge(T target, T source) {
        if (target == null || source == null) {
            throw new IllegalArgumentException("Both target and source must be non-null");
        }

        try {
            for (var propertyDescriptor : org.apache.commons.beanutils.PropertyUtils.getPropertyDescriptors(source.getClass())) {
                String propertyName = propertyDescriptor.getName();
                if ("class".equals(propertyName)) {
                    continue; // Skip the class property
                }

                Object sourceValue = org.apache.commons.beanutils.PropertyUtils.getProperty(source, propertyName);
                if (sourceValue != null) {
                    BeanUtils.setProperty(target, propertyName, sourceValue);
                }
            }
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException("Failed to merge objects", e);
        }

        return target;
    }
}