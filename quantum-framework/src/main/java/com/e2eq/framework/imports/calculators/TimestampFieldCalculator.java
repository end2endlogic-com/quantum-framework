package com.e2eq.framework.imports.calculators;

import com.e2eq.framework.imports.spi.FieldCalculator;
import com.e2eq.framework.imports.spi.ImportContext;
import com.e2eq.framework.model.persistent.base.BaseModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

import java.util.Date;
import java.util.Map;

/**
 * Built-in field calculator that sets timestamp fields.
 * Sets createdDate and modifiedDate if not already present.
 */
@ApplicationScoped
@Named("timestampCalculator")
public class TimestampFieldCalculator implements FieldCalculator {

    @Override
    public String getName() {
        return "timestampCalculator";
    }

    @Override
    public void calculate(BaseModel bean, Map<String, Object> rowData, ImportContext context) {
        // BaseModel doesn't have date fields by default, but subclasses might
        // This is a template for custom implementations
        Date now = new Date();

        // Use reflection to set common timestamp fields if they exist
        setFieldIfNull(bean, "createdDate", now);
        setFieldIfNull(bean, "createdAt", now);
        setFieldIfNull(bean, "modifiedDate", now);
        setFieldIfNull(bean, "modifiedAt", now);
        setFieldIfNull(bean, "lastModified", now);
    }

    @Override
    public int getOrder() {
        return 50; // Run early
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
