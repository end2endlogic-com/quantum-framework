package com.e2eq.framework.model.security;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;

/**
 * Describes how each component of a {@link com.e2eq.framework.model.persistent.base.DataDomain}
 * is derived when resolving placement for a source/ingestion write
 * ({@link DataDomainPolicyEntry.ResolutionMode#FROM_SOURCE}).
 *
 * <p>Each component is a small union ({@link Binding}) of either a literal value, fixed at
 * configuration time (typically because it is bound to the source itself, e.g. orgRefName /
 * accountNum), or a reference to a named attribute of the ingested row whose value is read at
 * resolution time (e.g. {@code tenantId = fromAttribute("tenant_id")}).</p>
 *
 * <p>Typical configuration:
 * <ul>
 *   <li>{@code orgRefName} / {@code accountNum} = literal (source-bound)</li>
 *   <li>{@code tenantId} = fromAttribute("tenant_id")</li>
 *   <li>{@code dataSegment} = literal("0") (default)</li>
 *   <li>{@code ownerId} = literal("system") (default)</li>
 * </ul>
 * </p>
 */
@Entity
@RegisterForReflection
public @Data class DataDomainComponentBinding {

    public enum Kind {
        LITERAL,
        FROM_ATTRIBUTE
    }

    /**
     * A single component binding: either a literal value or a reference to a named source
     * attribute. {@code literalValue} is significant iff {@code kind == LITERAL};
     * {@code attributeName} is significant iff {@code kind == FROM_ATTRIBUTE}.
     */
    @Entity
    @RegisterForReflection
    public static @Data class Binding {
        protected Kind kind = Kind.LITERAL;
        // Significant iff kind == LITERAL. Stored as String; coerced to the target component type.
        protected String literalValue;
        // Significant iff kind == FROM_ATTRIBUTE. Name of the source-row attribute to read.
        protected String attributeName;

        public Binding() {
        }

        public Binding(Kind kind, String literalValue, String attributeName) {
            this.kind = kind;
            this.literalValue = literalValue;
            this.attributeName = attributeName;
        }

        public static Binding literal(String value) {
            return new Binding(Kind.LITERAL, value, null);
        }

        public static Binding fromAttribute(String attributeName) {
            return new Binding(Kind.FROM_ATTRIBUTE, null, attributeName);
        }
    }

    protected Binding orgRefName;
    protected Binding accountNum;
    protected Binding tenantId;
    protected Binding dataSegment;
    protected Binding ownerId;
}
