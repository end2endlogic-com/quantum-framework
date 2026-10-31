package com.e2eq.framework.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an entity whose {@code refName} is a canonical join identifier.
 *
 * <p>For annotated types the repository enforces {@link com.e2eq.framework.model.persistent.base.RefNameRule}
 * when a record is created, and rejects any change to {@code refName} once it exists. Existing records are never
 * re-validated or rewritten. A missing refName is rejected rather than defaulted to the ObjectId.</p>
 *
 * <p>{@link #legacy()} lists exact refNames that predate the rule and are still created for new realms by
 * framework code (changesets, startup services). Applications declare their own legacy names with the
 * {@code quantum.refname.legacy-allowed.<SimpleClassName>} config property. Each admitted legacy create is
 * logged with diagnostic code {@code REFNAME_LEGACY_ADMITTED}.</p>
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CanonicalRefName {
   String[] legacy() default {};
}
