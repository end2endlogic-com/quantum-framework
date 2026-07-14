package com.e2eq.framework.rest.models;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Schedules one in-app reminder for visible participants. */
@RegisterForReflection
public class ParticipantWorkItemReminderRequest {
    @NotNull
    public Long expectedVersion;
    @NotNull
    public Date triggerAt;
    public List<String> recipientRefs = new ArrayList<>();
}
