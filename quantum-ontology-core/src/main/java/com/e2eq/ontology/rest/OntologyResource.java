package com.e2eq.ontology.rest;

import com.e2eq.ontology.rest.dto.OntologyVisualizationPayload;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/ontology")
@RolesAllowed({"admin", "user"})
@Tag(name = "ontology", description = "Ontology metadata and visualization helpers")
public class OntologyResource {

    private final OntologyVisualizationService visualizationService;

    @Inject
    public OntologyResource(OntologyVisualizationService visualizationService) {
        this.visualizationService = visualizationService;
    }

    @GET
    @Path("/visualization")
    @Produces(MediaType.APPLICATION_JSON)
    public OntologyVisualizationPayload getVisualization() {
        return visualizationService.buildVisualization();
    }
}
