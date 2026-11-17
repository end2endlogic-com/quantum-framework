package com.e2eq.framework.model.security;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Functional domains an {@link Application} borrows from another application's vocabulary. The
 * borrower may write policy rules over them for its own roles only; it never adds or redefines
 * actions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class VocabularyReference {
   /** The application that owns the area. */
   protected String application;
   protected String area;
   /** Domains of that area this application uses. */
   @Builder.Default
   protected List<String> domains = new ArrayList<>();
}
