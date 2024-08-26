package com.e2eq.framework.model.persistent.interfaces;

import com.e2eq.framework.model.general.ValidationViolation;

import java.util.Set;

/**
 indicates that this object can be saved in an invalid state.  There are times
 when we want to capture the state of the object but for what ever reasons it
 fails validation constraints defined on the object, requiring a user or system
 to correct the object prior to it being persisted in a "valid" state.
 */
public interface InvalidSavable {
   boolean isInvalid();
   boolean isCanSaveInvalid();
   Set<ValidationViolation> getViolationSet();
   void setViolationSet(Set<ValidationViolation> violationSet);
}
