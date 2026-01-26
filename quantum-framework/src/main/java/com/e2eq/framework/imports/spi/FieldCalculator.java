package com.e2eq.framework.imports.spi;

import com.e2eq.framework.model.persistent.base.BaseModel;

import java.util.Map;

/**
 * SPI for calculating field values during CSV import.
 * Implementations are discovered via CDI and invoked by name in ImportProfile.
 *
 * <p>Example implementation:</p>
 * <pre>{@code
 * @ApplicationScoped
 * @Named("timestampCalculator")
 * public class TimestampCalculator implements FieldCalculator {
 *     @Override
 *     public String getName() {
 *         return "timestampCalculator";
 *     }
 *
 *     @Override
 *     public void calculate(BaseModel bean, Map<String, Object> rowData, ImportContext context) {
 *         if (bean.getCreatedDate() == null) {
 *             bean.setCreatedDate(new Date());
 *         }
 *     }
 * }
 * }</pre>
 */
public interface FieldCalculator {

    /**
     * Get the unique name of this calculator.
     * This name is used in ImportProfile.fieldCalculatorNames.
     *
     * @return the calculator name
     */
    String getName();

    /**
     * Calculate and set field values on the bean.
     * Called after CSV parsing but before validation.
     *
     * @param bean the bean being imported
     * @param rowData the raw row data from CSV (column name -> value)
     * @param context the import context with profile and additional metadata
     */
    void calculate(BaseModel bean, Map<String, Object> rowData, ImportContext context);

    /**
     * Check if this calculator applies to the given bean type.
     * Default returns true (applies to all types).
     *
     * @param beanClass the class being imported
     * @return true if this calculator should run
     */
    default boolean appliesTo(Class<? extends BaseModel> beanClass) {
        return true;
    }

    /**
     * Get the priority order for this calculator.
     * Lower values run first. Default is 100.
     *
     * @return the priority order
     */
    default int getOrder() {
        return 100;
    }
}
