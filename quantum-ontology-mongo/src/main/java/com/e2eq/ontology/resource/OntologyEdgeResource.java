package com.e2eq.ontology.resource;

import com.e2eq.framework.rest.resources.BaseResource;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class OntologyEdgeResource extends BaseResource<OntologyEdge, OntologyEdgeRepo> {
   // Quarkus CDI requires a non-private no-args constructor for normal-scoped beans
   public OntologyEdgeResource() {
      super(null);
   }

   @Inject
   void initRepo(OntologyEdgeRepo repo) {
      this.repo = repo;
   }
}
