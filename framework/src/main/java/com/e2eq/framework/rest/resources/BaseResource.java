package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.base.*;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.model.securityrules.SecurityCheckException;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.rest.models.Collection;
import com.e2eq.framework.rest.models.CounterResponse;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.rest.models.SuccessResponse;
import com.e2eq.framework.util.JSONUtils;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 A base resource class
 */
@RolesAllowed({ "user", "admin" })
public class BaseResource<T extends BaseModel, R extends BaseMorphiaRepo<T>> {
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

   protected List<SortField> convertToSortField(String sort) {
      List<SortField> sortFields = new ArrayList<>();
      if (sort != null) {
         for (String sortPart : sort.split(",")) {
            String cleanSortPart = sortPart.trim();
            if (cleanSortPart.startsWith("-")) {
               sortFields.add(new SortField(cleanSortPart.substring(1),
                       SortField.SortDirection.DESC));
            } else if (cleanSortPart.startsWith("+")) {
               sortFields.add(new SortField(cleanSortPart.substring(1),
                       SortField.SortDirection.ASC));
            } else {
               sortFields.add(new SortField(cleanSortPart,
                       SortField.SortDirection.ASC));
            }
         }
      }
      return sortFields;
   }

   protected List<ProjectionField> convertProjectionFields(String projection) {
      List<ProjectionField> projectionFields = new ArrayList<>();
      if (projection != null) {
         for (String projectionPart : projection.split(",")) {
            String cleanProjectionPart = projectionPart.trim();
            if (cleanProjectionPart.startsWith("-")) {
               projectionFields.add(new ProjectionField(cleanProjectionPart.substring(1),
                       ProjectionField.ProjectionType.EXCLUDE));
            } else if (cleanProjectionPart.startsWith("+")) {
               projectionFields.add(new ProjectionField(
                       cleanProjectionPart.substring(1),
                       ProjectionField.ProjectionType.INCLUDE));
            } else {
               projectionFields.add(new ProjectionField(cleanProjectionPart,
                       ProjectionField.ProjectionType.INCLUDE));
            }
         }
      }
      return projectionFields;
   }


   @Path("count")
   @GET
   @Produces(MediaType.APPLICATION_JSON)
   public CounterResponse getCount(@QueryParam("filter") String filter) {
      long count = repo.getCount(filter);
      CounterResponse response = new CounterResponse(count);
      response.setStatusCode(Response.Status.OK.getStatusCode());
      response.setMessage(String.format("Count: %d", count));
      return response;
   }


   @Path("list")
   @GET
   @Produces(MediaType.APPLICATION_JSON)
   public Collection<T> getList(@DefaultValue("0")
                           @QueryParam("skip") int skip,
                           @DefaultValue("50")@QueryParam("limit") int limit,
                           @QueryParam("filter") String filter,
                           @QueryParam("sort") String sort,
                           @QueryParam("projection") String projection) {

      List<ProjectionField> projectionFields = null;
      List<SortField> sortFields = null;
         if (sort != null || projection != null) {

            if (sort!=null) {
               sortFields = convertToSortField(sort);

               /*List<Sort> sorts = new ArrayList<>();
               for (SortParameter sortField : sortFields) {
                  if (sortField.getDirection().equals(SortParameter.SortOrderEnum.ASC)) {
                     sorts.add(ascending(sortField.getFieldName()));
                  } else {
                     sorts.add(descending(sortField.getFieldName()));
                  }
               } */
            } else {
               sortFields = null;
            }
            projectionFields = convertProjectionFields(projection);
         }


         List<T> ups = repo.getListByQuery(skip, limit, filter, sortFields, projectionFields);
         long count = repo.getCount(filter);
         Collection<T> collection = new Collection<>(ups, skip, limit, filter, count);
         // fill in ui-actions
         collection = repo.fillUIActions(collection);

         return collection;

   }

   @Path("schema")
   @GET
   @Produces(MediaType.APPLICATION_JSON)
   public JsonSchema getSchema() throws JsonMappingException {
      JSONUtils utils = JSONUtils.instance();
      JsonSchema schema = utils.getSchema(repo.getPersistentClass());
      return schema;
   }

   @POST
   @Produces(MediaType.APPLICATION_JSON)
   @Consumes(MediaType.APPLICATION_JSON)
   public T save(T model) {
      model = repo.save(model);
      return model;
   }

   @APIResponses(value = {
           @APIResponse(responseCode = "200", description = "Success", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SuccessResponse.class))),
           @APIResponse(responseCode = "404", description = "Not Found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
   })
   @Path("set")
   @PUT
   @Produces(MediaType.APPLICATION_JSON)
   @Consumes(MediaType.APPLICATION_JSON)
   public Response update(@QueryParam("id") String id, @QueryParam("pairs") Pair<String,Object>... pairs) {
      long updated = repo.update(id, pairs);
      if (updated > 0) {
         SuccessResponse r =  new SuccessResponse();
         r.setMessage("Update successful");
         r.setStatusCode(Response.Status.OK.getStatusCode());
         return Response.ok().entity(r).build();
      } else {
         RestError error = RestError.builder()
                 .status(Response.Status.NOT_FOUND.getStatusCode())
                 .statusMessage("Id:" + id + " was not found").build();
         return Response.status(Response.Status.NOT_FOUND).entity(error).build();
      }
   }

   @Path("id")
   @DELETE
   public Response delete(@QueryParam("id") String id) {
      Optional<T> model = repo.findById(id);
      if (model.isPresent()) {
         long deletedCount = repo.delete(model.get());
         if (deletedCount != 0) {
            SuccessResponse r =  new SuccessResponse();
            r.setMessage("Delete successful");
            r.setStatusCode(Response.Status.OK.getStatusCode());
            return Response.ok().entity(r).build();
         } else {
            RestError error = RestError.builder()
                    .statusMessage("Entity with Id:" + id + " was found but delete returned 0 indicating the entity may not have been deleted.  Retry your request")
                    .reasonMessage("Delete Operation returned 0 when 1 was expected")
                    .debugMessage("MongoDB delete operation returned 0")
                    .build();

            return Response.status(Response.Status.NOT_MODIFIED).entity(error).build();
         }
      } else {
         RestError error = RestError.builder()
                 .status(Response.Status.NOT_FOUND.getStatusCode())
                 .statusMessage("Id:" + id + " was not found").build();
         return Response.status(Response.Status.NOT_FOUND).entity(error).build();
      }
   }
}
