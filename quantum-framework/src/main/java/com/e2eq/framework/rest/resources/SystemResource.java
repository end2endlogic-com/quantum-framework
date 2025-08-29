package com.e2eq.framework.rest.resources;


import com.e2eq.framework.model.persistent.morphia.FunctionalDomainRepo;

import com.e2eq.framework.model.persistent.security.FunctionalDomain;
import com.e2eq.framework.rest.models.ComponentVersion;
import com.e2eq.framework.rest.models.DeployedVersion;
import com.e2eq.framework.util.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import dev.morphia.mapping.codec.pojo.EntityModel;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.semver4j.Semver;

@Path("/system")
@Tag(name = "System", description = "System operations")
public class SystemResource {
    @Inject
    @Default
    MorphiaDatastore datastore;

    @Inject
    FunctionalDomainRepo fdRepo;

    @Inject
    SecurityUtils securityUtils;


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
    @Produces("application/json")
    @PermitAll
    public Response version() throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("quantum-version.properties")) {
            if (input == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("Version information not found").build();
            }

            java.util.Properties prop = new java.util.Properties();
            prop.load(input);

            Semver semver = new Semver(prop.getProperty("quantum-version"));
            String buildDateString = prop.getProperty("quantum-build.date");
            Instant instant = Instant.parse(buildDateString);
            Date buildDate = Date.from(instant);
            String buildNumber = prop.getProperty("quantum-build.number");
            // build a json object with the version and build details
            ComponentVersion version = ComponentVersion.builder()
                    .componentName("Quantum")
                    .major(semver.getMajor())
                    .minor(semver.getMinor())
                    .patch(semver.getPatch())
                    .suffix(semver.getPreRelease())
                    .buildNumber(buildNumber)
                    .timestamp(buildDate)
                    .strictVersionString(semver.getVersion())
                    .build();
            DeployedVersion deployed = new DeployedVersion();
            Map<String, ComponentVersion> versions = Map.of("Quantum", version);

            return Response.ok(version).build();
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
            f.setDataDomain(securityUtils.getSystemDataDomain());
            if (!fdRepo.findByRefName(f.getRefName()).isPresent())
                fdRepo.save(f);
        } );

        return Response.ok().build();
    }
}
