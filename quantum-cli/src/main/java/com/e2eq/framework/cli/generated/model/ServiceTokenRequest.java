package com.e2eq.framework.cli.generated.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotNull;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceTokenRequest {
    @NotNull
    private String kind;
    private String realm;
    private String description;
    @JsonIgnore
    private final Set<String> $present = new HashSet<>();

    @JsonProperty("kind")
    public String getKind() { return kind; }

    @JsonSetter(nulls = Nulls.FAIL)
    @JsonProperty("kind")
    public void setKind(String kind) { this.kind = kind; }

    @JsonIgnore
    public String getRealm() { return realm; }

    @JsonProperty("realm")
    public void setRealm(String realm) { this.realm = realm; this.$present.add("realm"); }

    /** Whether realm was set or read from the wire; an unset realm is omitted from the JSON body. */
    public boolean hasRealm() { return $present.contains("realm"); }

    @JsonProperty("realm")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AtomicReference<String> realm$wire() {
        return hasRealm() ? new AtomicReference<>(realm) : null;
    }

    @JsonIgnore
    public String getDescription() { return description; }

    @JsonProperty("description")
    public void setDescription(String description) { this.description = description; this.$present.add("description"); }

    /** Whether description was set or read from the wire; an unset description is omitted from the JSON body. */
    public boolean hasDescription() { return $present.contains("description"); }

    @JsonProperty("description")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AtomicReference<String> description$wire() {
        return hasDescription() ? new AtomicReference<>(description) : null;
    }
}
