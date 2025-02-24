package com.e2eq.framework.model.persistent.base;


import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RegisterForReflection
@EqualsAndHashCode
@Entity
public @Data class Tag {
   protected String category;
   protected String tagDisplayName;
   protected Set<String> additionalData;
   protected List<StringNameValue> attributes;
   protected Boolean visible;
}
