package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.FullBaseModel;
import com.e2eq.ontology.annotations.OntologyClass;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Entity("application")
@RegisterForReflection
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@OntologyClass(id = "Application")

public class Application extends FullBaseModel {

   /**
    * The roles a binding for this application may carry. Empty means the application declares
    * none yet and is resolved on the legacy realm-assignment path.
    */
   protected List<ApplicationRole> roles = new ArrayList<>();

   /** Declared roles a tenant's owner receives when the application is installed for the tenant. */
   protected List<String> ownerRoles = new ArrayList<>();

   /** Declared roles an invite grants when it names none. */
   protected List<String> memberRoles = new ArrayList<>();

   /** Where the application's tenants live. */
   protected ApplicationTenancy tenancy;

   /** Functional domains borrowed from other applications' vocabularies. */
   protected List<VocabularyReference> uses = new ArrayList<>();

   @Override
   public String bmFunctionalArea() {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain() {
      return "APPLICATION";
   }
}
