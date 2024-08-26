package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.morphia.BaseRepo;
import com.e2eq.framework.model.securityrules.SecurityCheckException;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.rest.models.Collection;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.util.JSONUtils;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;
import java.util.Optional;

/**
 A base resource class
 */
@RolesAllowed({ "user", "admin" })
public class BaseResource<T extends BaseModel, R extends BaseRepo<T>> {
   protected R repo;

   @Inject
   RuleContext ruleContext;

   protected BaseResource(R repo) {
      this.repo = repo;
   }

   @Path("refName")
   @GET
   @Produces(MediaType.APPLICATION_JSON)
   public Response byRefName (@QueryParam("refName") String refName) {
      Response response;
      Optional<T> opModel = repo.findByRefName(refName);

      if (opModel.isPresent()) {
         repo.fillUIActions(opModel.get());
         response  = Response.ok(opModel.get()).build();
      }
      else {
         RestError error = RestError.builder()
                 .status(Response.Status.NOT_FOUND.getStatusCode())
                 .statusMessage("RefName:" + refName + " was not found").build();

         response = Response.status(Response.Status.NOT_FOUND).entity(error).build();
      }

      return response;
   }

   @Path("id")
   @GET
   @Produces(MediaType.APPLICATION_JSON)
   public Response byId(@QueryParam("id") String id) {
      Response response;
      Optional<T> opModel = repo.findById(id);

      if (opModel.isPresent()) {
         repo.fillUIActions(opModel.get());
         response  = Response.ok(opModel.get()).build();
      }
      else {
         RestError error = RestError.builder()
                 .status(Response.Status.NOT_FOUND.getStatusCode())
                 .statusMessage("Id:" + id + " was not found").build();
         response = Response.status(Response.Status.NOT_FOUND).entity(error).build();
      }

      return response;
   }

   @Path("list")
   @GET
   @Produces(MediaType.APPLICATION_JSON)
   public Response getList(@DefaultValue("0") @QueryParam("skip") int skip,  @DefaultValue("50")@QueryParam("limit") int limit, @QueryParam("filter") String filter) {
      try {
         List<T> ups = repo.getListByQuery(skip, limit, filter);
         Collection<T> collection = new Collection<>(ups, skip, limit, filter);
         // fill in ui-actions
         collection = repo.fillUIActions(collection);

         return Response.ok().entity(collection).build();
      } catch (SecurityCheckException ex) {
         RestError error = RestError.builder()
                 .status(Response.Status.UNAUTHORIZED.getStatusCode())
                 .statusMessage(ex.getMessage())
                 .securityResponse(ex.getResponse()).build();
         return Response.status(Response.Status.UNAUTHORIZED).entity(error).build();
      }
   }



   @Path("schema")
   @GET
   @Produces(MediaType.APPLICATION_JSON)
   public Response getSchema() throws JsonMappingException {
      JSONUtils utils = JSONUtils.instance();
      JsonSchema schema = utils.getSchema(repo.getPersistentClass());
      return Response.ok().entity(schema).build();
   }

   @POST
   @Produces(MediaType.APPLICATION_JSON)
   @Consumes(MediaType.APPLICATION_JSON)
   public Response save(T model) {
      model = repo.save(model);
      return Response.ok().entity(model).status(Response.Status.CREATED).build();
   }

   @Path("set")
   @PUT
   @Produces(MediaType.APPLICATION_JSON)
   @Consumes(MediaType.APPLICATION_JSON)
   public Response update(@QueryParam("id") String id, @QueryParam("pairs") Pair<String,Object>... pairs) {
      long updated = repo.update(id, pairs);
      if (updated > 0) {
         return Response.ok().build();
      } else {
         return Response.status(Response.Status.NOT_FOUND).entity("Entity not found for ID: " + id).build();
      }
   }

   @Path("id")
   @DELETE
   public Response delete(@QueryParam("id") String id) {
      Optional<T> model = repo.findById(id);
      if (model.isPresent()) {
         repo.delete(model.get());
         return Response.ok().entity("Entity deleted successfully").build();
      } else {
         return Response.status(Response.Status.NOT_FOUND).entity("Entity not found for ID: " + id).build();
      }
   }
}
