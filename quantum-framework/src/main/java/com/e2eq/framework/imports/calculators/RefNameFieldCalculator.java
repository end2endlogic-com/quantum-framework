package com.e2eq.framework.imports.calculators;

import com.e2eq.framework.imports.spi.FieldCalculator;
import com.e2eq.framework.imports.spi.ImportContext;
import com.e2eq.framework.model.persistent.base.BaseModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.apache.commons.text.WordUtils;

import java.util.Map;
import java.util.UUID;

/**
 * Built-in field calculator that generates refName if not present.
 * Uses displayName to generate refName, or falls back to UUID.
 */
@ApplicationScoped
@Named("refNameCalculator")
public class RefNameFieldCalculator implements FieldCalculator {

    @Override
    public String getName() {
        return "refNameCalculator";
    }

    @Override
    public void calculate(BaseModel bean, Map<String, Object> rowData, ImportContext context) {
        if (bean.getRefName() == null || bean.getRefName().isEmpty()) {
            String refName = generateRefName(bean, rowData);
            bean.setRefName(refName);
        }
    }

    @Override
    public int getOrder() {
        return 70; // Run after UUID calculator
    }

    private String generateRefName(BaseModel bean, Map<String, Object> rowData) {
        // Try to derive from displayName
        String displayName = bean.getDisplayName();
        if (displayName != null && !displayName.isEmpty()) {
            return normalizeRefName(displayName);
        }

        // Try common name fields from row data
        for (String fieldName : new String[]{"name", "title", "code", "identifier"}) {
            Object value = rowData.get(fieldName);
            if (value != null && !value.toString().isEmpty()) {
                return normalizeRefName(value.toString());
            }
        }

        // Fallback to UUID
        return UUID.randomUUID().toString();
    }

    private String normalizeRefName(String value) {
        if (value == null) {
            return UUID.randomUUID().toString();
        }

        // Convert to lowercase, replace spaces with hyphens, remove special chars
        String normalized = value.toLowerCase()
                .replaceAll("\\s+", "-")
                .replaceAll("[^a-z0-9\\-_]", "")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        // Ensure minimum length
        if (normalized.length() < 3) {
            normalized = normalized + "-" + UUID.randomUUID().toString().substring(0, 8);
        }

        // Truncate if too long (keeping it reasonable for database indexes)
        if (normalized.length() > 100) {
            normalized = normalized.substring(0, 100);
        }

        return normalized;
    }
}
