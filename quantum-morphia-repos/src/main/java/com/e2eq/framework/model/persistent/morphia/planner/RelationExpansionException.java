package com.e2eq.framework.model.persistent.morphia.planner;

/** Why {@code expand(@relation)} was refused. Nothing is returned for a refused expand. */
public class RelationExpansionException extends RuntimeException {

    public enum Code {
        /** The TBox does not declare the relation. */
        UNKNOWN_RELATION("expand_unknown_relation", 400),
        /** The relation is internal: only stable relations may be expanded. */
        INTERNAL_RELATION("expand_internal_relation", 400),
        /** The relation's range does not name a mapped entity type. */
        NO_TARGET_TYPE("expand_no_target_type", 400),
        /** The target type's row or field policy cannot be resolved. */
        TARGET_NOT_GOVERNED("expand_target_not_governed", 403),
        /** The page's rows reach more targets than {@code quantum.ontology.expand.max-targets}. */
        TOO_MANY_TARGETS("expand_too_many_targets", 422),
        /** A row's DataDomain, which keys its edges, cannot be read. */
        NO_ROW_DATA_DOMAIN("expand_no_row_data_domain", 422),
        /** The realm's ontology registry was refused or cannot be built. */
        ONTOLOGY_UNAVAILABLE("expand_ontology_unavailable", 503),
        /** The ontology module is not installed. */
        NOT_AVAILABLE("expand_not_available", 501);

        private final String wireValue;
        private final int httpStatus;

        Code(String wireValue, int httpStatus) {
            this.wireValue = wireValue;
            this.httpStatus = httpStatus;
        }

        public String wireValue() {
            return wireValue;
        }

        /** The HTTP status a REST surface answers this refusal with. */
        public int httpStatus() {
            return httpStatus;
        }
    }

    private final Code code;
    private final String relation;

    public RelationExpansionException(Code code, String relation, String message) {
        this(code, relation, message, null);
    }

    public RelationExpansionException(Code code, String relation, String message, Throwable cause) {
        super(message, cause);
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

    /** The REST error body: {@code {error, code, message, relation?}}. */
    public java.util.Map<String, Object> toErrorBody() {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error", "RelationExpandRefused");
        body.put("code", code.wireValue());
        body.put("message", getMessage());
        if (relation != null) body.put("relation", relation);
        return body;
    }
}
