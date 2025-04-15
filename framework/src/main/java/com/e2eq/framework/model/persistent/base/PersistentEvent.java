package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.antlr.v4.codegen.SourceGenTriggers;

import java.util.Date;
import java.util.Map;

@Data
@RegisterForReflection
@EqualsAndHashCode
@ToString
@Entity
public class PersistentEvent {
    protected String eventType;
    protected String eventMessage;
    protected Date eventDate;
    protected String userId;
    protected Map<String, Object> eventData;
}
