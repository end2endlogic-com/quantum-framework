package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.model.persistent.morphia.FunctionalDomainRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import com.e2eq.framework.model.persistent.security.FunctionalDomain;
import com.e2eq.framework.util.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.morphia.Datastore;
import dev.morphia.MorphiaDatastore;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.inject.Default;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.Produces;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import dev.morphia.mapping.codec.pojo.EntityModel;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/system")
@Tag(name = "System", description = "System operations")
public class SystemResource {
    @Inject
    @Default
    MorphiaDatastore datastore;

    @Inject
    FunctionalDomainRepo fdRepo;


    @GET
    @Path("/mapping")
    @Produces("application/text")
    public Response mapping() {
        MorphiaDatastore ds = datastore;
        var entities = ds.getMapper().getMappedEntities();
        var list = entities.stream()
                    .map(EntityModel::getName).sorted()
                    .collect(Collectors.joining(", "));
            return Response.ok(list).build();
    }

    @GET
    @Path("/quantumVersion")
    @Produces("application/text")
    @PermitAll
    public Response version() throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("version.properties")) {
            if (input == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("Version information not found").build();
            }

            java.util.Properties prop = new java.util.Properties();
            prop.load(input);
            String version = prop.getProperty("quantum-version");
            String buildDate = prop.getProperty("build.date");
            return Response.ok(version + ":" + buildDate).build();
        }
    }

    @PUT
    @Path("/update-security-model")
    @Produces("application/json")
    public Response updateSecurityModel() throws IOException {
        ObjectMapper mapper = new ObjectMapper( new YAMLFactory());
        CollectionType listType = mapper.getTypeFactory().constructCollectionType(ArrayList.class, FunctionalDomain.class);
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        InputStream inputStream = classLoader.getResourceAsStream("securityModel.yaml");
        List<FunctionalDomain> domains = mapper.readValue(inputStream, listType);

        domains.forEach((f) -> {
            f.setDataDomain(SecurityUtils.systemDataDomain);
            if (!fdRepo.findByRefName(f.getRefName()).isPresent())
                fdRepo.save(f);
        } );

        return Response.ok().build();
    }
}
