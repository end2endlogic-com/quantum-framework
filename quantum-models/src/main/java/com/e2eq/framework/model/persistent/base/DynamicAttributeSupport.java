package com.e2eq.framework.model.persistent.base;

import java.util.List;

/**
 * Interface for models that support dynamic attribute sets.
 * Implement this interface to enable dynamic attribute import functionality.
 */
public interface DynamicAttributeSupport {

    /**
     * Get the list of dynamic attribute sets.
     *
     * @return list of dynamic attribute sets, or null if none
     */
    List<DynamicAttributeSet> getDynamicAttributeSets();

    /**
     * Set the list of dynamic attribute sets.
     *
     * @param dynamicAttributeSets the dynamic attribute sets to set
     */
    void setDynamicAttributeSets(List<DynamicAttributeSet> dynamicAttributeSets);
}
