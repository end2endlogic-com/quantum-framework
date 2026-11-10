package com.e2eq.framework.model.persistent.morphia.planner;

/** Why {@code expand(@relation)} was refused. Nothing is returned for a refused expand. */
public class RelationExpansionException extends RuntimeException {

    public enum Code {
        /** The TBox does not declare the relation. */
        UNKNOWN_RELATION("expand_unknown_relation"),
        /** The relation is internal: only stable relations may be expanded. */
        INTERNAL_RELATION("expand_internal_relation"),
        /** The relation's range does not name a mapped entity type. */
        NO_TARGET_TYPE("expand_no_target_type"),
        /** The target type's row or field policy cannot be resolved. */
        TARGET_NOT_GOVERNED("expand_target_not_governed"),
        /** The page's rows reach more targets than {@code quantum.ontology.expand.max-targets}. */
        TOO_MANY_TARGETS("expand_too_many_targets"),
        /** The ontology module is not installed. */
        NOT_AVAILABLE("expand_not_available");

        private final String wireValue;

        Code(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    private final Code code;
    private final String relation;

    public RelationExpansionException(Code code, String relation, String message) {
        super(message);
        this.code = code;
        this.relation = relation;
    }

    public Code code() {
        return code;
    }

    /** The refused relation, or null when the refusal is not about one relation. */
    public String relation() {
        return relation;
    }
}
