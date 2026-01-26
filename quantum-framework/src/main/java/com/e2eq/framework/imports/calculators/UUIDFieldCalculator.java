package com.e2eq.framework.imports.calculators;

import com.e2eq.framework.imports.spi.FieldCalculator;
import com.e2eq.framework.imports.spi.ImportContext;
import com.e2eq.framework.model.persistent.base.BaseModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

import java.util.Map;
import java.util.UUID;

/**
 * Built-in field calculator that generates UUID values.
 * Sets externalId and other UUID fields if not already present.
 */
@ApplicationScoped
@Named("uuidCalculator")
public class UUIDFieldCalculator implements FieldCalculator {

    @Override
    public String getName() {
        return "uuidCalculator";
    }

    @Override
    public void calculate(BaseModel bean, Map<String, Object> rowData, ImportContext context) {
        // Use reflection to set common UUID fields if they exist and are null
        setFieldIfNull(bean, "externalId", UUID.randomUUID().toString());
        setFieldIfNull(bean, "uuid", UUID.randomUUID().toString());
        setFieldIfNull(bean, "correlationId", UUID.randomUUID().toString());
    }

    @Override
    public int getOrder() {
        return 60; // Run after timestamp calculator
    }

    private void setFieldIfNull(Object bean, String fieldName, Object value) {
        try {
            var field = findField(bean.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                if (field.get(bean) == null) {
                    field.set(bean, value);
                }
            }
        } catch (Exception e) {
            // Field doesn't exist or can't be set - ignore
        }
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String fieldName) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
}
