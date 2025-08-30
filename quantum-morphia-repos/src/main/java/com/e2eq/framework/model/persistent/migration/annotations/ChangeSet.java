package com.e2eq.framework.model.persistent.migration.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ChangeSet {
   /**
    The id of this change set
    @return a unique string used to identify this change set
    */
   String id();

   /**
    The version of the database that this change set knows how to convert from
    @return
    */
   String dbFromVersion();

   /**
    The version of the database that this change set is targeting
    */
   String dbToVersion();

   /**
    The priority that this changeset takes compared to other changesets for the same
    from / to pairing.
    @return
    */
   int priority() default 100;

   /**
    The author of the change set
    @return
    */
   String author() default "";


   /**
    description of the change that will be executed.
    @return
    */
   String description();


   /**
    The scope of this changeSet ( Dev, Test, Prod )
    @return
    */
   String scope();

   /**
    Weather to run this change set within a transction or not
    @return
    */
   boolean transactional() default true;
}
