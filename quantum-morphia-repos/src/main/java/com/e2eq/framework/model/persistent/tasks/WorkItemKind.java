package com.e2eq.framework.model.persistent.tasks;

/** Distinguishes participant-created work from orchestrated queue work. */
public enum WorkItemKind {
    PROCESS_TASK,
    TODO
}
