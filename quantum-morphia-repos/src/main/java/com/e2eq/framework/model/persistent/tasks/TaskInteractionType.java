package com.e2eq.framework.model.persistent.tasks;

/**
 * The experience used to execute a task; the lifecycle remains the same.
 */
public enum TaskInteractionType {
    WORKBOOK,
    SURVEY,
    FORM,
    APPROVAL,
    MESSAGE,
    EXTERNAL_SYSTEM
}
