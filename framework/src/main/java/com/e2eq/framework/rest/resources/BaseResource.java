package com.e2eq.framework.rest.resources;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.base.*;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.model.securityrules.SecurityCheckException;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.rest.models.*;
import com.e2eq.framework.rest.models.Collection;
import com.e2eq.framework.util.CSVExportHelper;
import com.e2eq.framework.util.CSVImportHelper;
import com.e2eq.framework.util.FilterUtils;
import com.e2eq.framework.util.JSONUtils;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;

import dev.morphia.query.ValidationException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.jboss.resteasy.reactive.MultipartForm;

import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.io.CsvBeanReader;
import org.supercsv.io.ICsvBeanReader;

import org.supercsv.prefs.CsvPreference;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.*;
import java.lang.reflect.Field;

import static java.lang.String.format;

/**
 A base resource class
 */
@SecurityScheme(
        securitySchemeName = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
@RolesAllowed({ "user", "admin" })
public class BaseResource<T extends BaseModel, R extends BaseMorphiaRepo<T>> {
   protected R repo;

   @Inject
   protected JsonWebToken jwt;

   @Inject
   protected RuleContext ruleContext;

   private static final int MAXIMUM_REJECTS_SHOWN = 5;

   protected BaseResource(R repo) {
      this.repo = repo;
   }

   @Path("refName/{refName}")
   @GET
   @Produces(MediaType.APPLICATION_JSON)
   @Operation(summary = "Get The entity by refName",
           description = "Will get the entity or return a 404 if not found")
   @SecurityRequirement(name = "bearerAuth")
   @APIResponses(value = {
           @APIResponse(responseCode = "200", description = "Entity found", content = @Content(mediaType = "application/json")),
           @APIResponse(responseCode = "404", description = "Entity not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
   })
   public Response byPathRefName (@Parameter(description = "refName of the entity", required = true)
                                     @PathParam("refName") String refName) {
      return byRefName(refName);
   }

   @Path("refName")
   @GET
   @Produces(MediaType.APPLICATION_JSON)
   @Operation(summary = "Get The entity by refName",
           description = "Will get the entity or return a 404 if not found")
   @SecurityRequirement(name = "bearerAuth")
   @APIResponses(value = {
           @APIResponse(responseCode = "200", description = "Entity found", content = @Content(mediaType = "application/json")),
           @APIResponse(responseCode = "404", description = "Entity not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
   })
   public Response byRefName (
           @Parameter(description = "Reference name of the entity", required = true)
                   @QueryParam("refName") String refName) {
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


   @Path("id/{id}")
   @GET
   @Produces(MediaType.APPLICATION_JSON)
   @SecurityRequirement(name = "bearerAuth")
   @APIResponses(value = {
           @APIResponse(responseCode = "200", description = "Entity found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = BaseModel.class))),
           @APIResponse(responseCode = "404", description = "Entity not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
   })
   public Response byPathId(
           @Parameter(description = "Id of the entity", required = true)
           @PathParam("id") String id) {
      return byId(id);
   }

   @Path("id")
   @GET
   @Produces(MediaType.APPLICATION_JSON)
   @SecurityRequirement(name = "bearerAuth")
   @APIResponses(value = {
           @APIResponse(responseCode = "200", description = "Entity found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = BaseModel.class))),
           @APIResponse(responseCode = "404", description = "Entity not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
   })
   public Response byId(
           @Parameter(description = "Id of the entity", required = true)
           @QueryParam("id") String id) {
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




   @Path("count")
   @GET
   @Operation(summary = "Provides the count of entities")
   @Produces(MediaType.APPLICATION_JSON)
   @Consumes(MediaType.APPLICATION_JSON)
   @SecurityRequirement(name = "bearerAuth")
   @APIResponses(value = {
           @APIResponse(responseCode = "200", description = "Success", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CounterResponse.class))),
           @APIResponse(responseCode = "400", description = "Bad Request - bad arguments", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
   })
   public CounterResponse getCount(@QueryParam("filter") String filter) {
      try {
         long count = repo.getCount(filter);
         CounterResponse response = new CounterResponse(count);
         response.setStatusCode(Response.Status.OK.getStatusCode());
         response.setMessage(String.format("Count: %d", count));
         return response;
      } catch (IllegalArgumentException | ValidationException e) {
         RestError error = RestError.builder()
                 .status(Response.Status.BAD_REQUEST.getStatusCode())
                 .statusMessage(e.getMessage()).build();
         throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity(error).build());
      }
   }

    @POST
    @Path("csv")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Import a list of entities from a CSV file")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "CSV file successfully imported"),
            @APIResponse(responseCode = "400", description = "Bad request - invalid CSV file or data")
    })
    public Response importCSVList(
            @Context UriInfo info,
            @BeanParam FileUpload fileUpload,
            @Parameter(description = "The character that must be used to separate fields of the same record")
            @QueryParam("fieldSeparator") @DefaultValue(",") String fieldSeparator,
            @Parameter(description = "The choice of strategy for quoting columns. One of \"QUOTE_WHERE_ESSENTIAL\" or \"QUOTE_ALL_COLUMNS\"")
            @QueryParam("quotingStrategy") @DefaultValue("QUOTE_WHERE_ESSENTIAL") String quotingStrategy,
            @Parameter(description = "The character that is used to surround the values of specific (or all) fields")
            @QueryParam("quoteChar") @DefaultValue("\"") String quoteChar,
            @Parameter(description = "Whether to skip the header row in the CSV file")
            @QueryParam("skipHeaderRow") @DefaultValue("true") boolean skipHeaderRow,
            @Parameter(description = "The charset encoding to use for the file")
            @QueryParam("charsetEncoding") @DefaultValue("UTF-8-without-BOM") String charsetEncoding,
            @Parameter(description = "A non-empty list of the names of the columns expected in the CSV file")
            @QueryParam("requestedColumns") List<String> requestedColumns,
            @Parameter(description = "A list of preferred column names to use in place of the requested columns")
            @QueryParam("preferredColumnNames") List<String> preferredColumnNames) {
        try {
            if (fileUpload.file == null) {
                throw new WebApplicationException("No file uploaded", Response.Status.BAD_REQUEST);
            }
            rejectUnrecognizedQueryParams(info, "fieldSeparator", "quotingStrategy",
                    "quoteChar", "skipHeaderRow", "charsetEncoding",
                    "requestedColumns", "preferredColumnNames");
    
            String charsetName = charsetEncoding.replaceAll("-with.*", "");
            final Charset chosenCharset;
            final boolean mustUseBOM;
    
            try {
                chosenCharset = Charset.forName(charsetName);
                mustUseBOM = charsetEncoding.contains("-with-BOM");
            } catch (UnsupportedCharsetException | IllegalCharsetNameException e) {
                throw new ValidationException(format("The value %s is not one of the supported charsetEncodings",
                        charsetEncoding));
            }
    
            List<T> failedRecords = new ArrayList<>();
            CSVImportHelper.FailedRecordHandler<T> failedRecordHandler = failedRecords::add;
    
            CSVImportHelper.ImportResult<T> result = CSVImportHelper.importCSV(
                    repo,
                    new FileInputStream(fileUpload.file),
                    fieldSeparator.charAt(0),
                    quoteChar.charAt(0),
                    skipHeaderRow,
                    requestedColumns,
                    preferredColumnNames,
                    chosenCharset,
                    mustUseBOM,
                    quotingStrategy,
                    failedRecordHandler
            );
    
            String message = String.format("Successfully imported %d entities. Failed to import %d entities.",
                    result.getImportedCount(), result.getFailedCount());
    
            return Response.ok()
                    .entity(result)
                    .header("X-Import-Success-Count", result.getImportedCount())
                    .header("X-Import-Failed-Count", result.getFailedCount())
                    .header("X-Import-Message", message)
                    .build();
    
        } catch (Exception e) {
            RestError error = RestError.builder()
                    .status(Response.Status.BAD_REQUEST.getStatusCode())
                    .statusMessage("Error processing CSV file: " + e.getMessage())
                    .build();
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
    }



   @GET
   @Path("csv")
   @SecurityRequirement(name = "bearerAuth")
   @Operation(summary = "Retrieve a list of Account in CSV format")
   @APIResponses({@APIResponse(responseCode = "401", description = "Not Authorized, caller does not have the privilege assigned to" +
           " their id"),
           @APIResponse(responseCode = "403", description = "Not authenticated caller did not authenticate before making the call"),
           @APIResponse(responseCode = "500", description = "Internal System error, ask operator to check server side logs")})
   @Produces({"text/csv", MediaType.TEXT_PLAIN, MediaType.WILDCARD})
   public Response getListAsCSV(
           @Context UriInfo info,

           @Parameter(description="the character that must be used to separate fields of the same record")
           @QueryParam("fieldSeparator")
           @DefaultValue(",") final String fieldSeparator,

           @Parameter(description="a non-empty list of the names of the columns expected in the delimited text;"
                   + " if unspecified, refName is returned."
                   + " It would be very unusual for this list to contain duplicates but that is not expressly"
                   + " prohibited")
           @QueryParam("requestedColumns")
           @DefaultValue("refName")
           final List<String> requestedColumns,

           @Parameter(description="the choice of strategy for the way in which columns are quoted when"
                   + " they contain values that embed the quoteChar character. One of \"QUOTE_WHERE_ESSENTIAL\""
                   + " or \"QUOTE_ALL_COLUMNS\"")
           @QueryParam("quotingStrategy")
           @DefaultValue("QUOTE_WHERE_ESSENTIAL")
           final String quotingStrategy,

           @Parameter(description="the character that is used to surround the values of specific (or all)"
                   + "fields to protect them from being fragmented up when loaded back later")
           @QueryParam("quoteChar")
           @DefaultValue("\"") final String quoteChar,

           @Parameter(description="the character that must be used when formatting a decimal value to separate the integer"
                   + " part from the fractional part")
           @QueryParam("decimalSeparator")
           @DefaultValue(".") String decimalSeparator,

           @Parameter(description="the charset to which the CSV file must be encoded, including in some cases the choice"
                   + " of whether or not a \"byte order mark\" (BOM) must precede the CSV data. These are supported:"
                   + "US-ASCII, UTF-8-without-BOM, UTF-8-with-BOM, UTF-16-with-BOM, UTF-16BE and UTF-16LE")
           @QueryParam("charsetEncoding")
           @DefaultValue("UTF-8-without-BOM")
           String charsetEncoding,

           @Parameter(description="a String that represents a syntactically valid filter expression; if set to null, the"
                   + " implication is that filtering must be performed")
           @QueryParam("filter")
           String filter,

           @Parameter(description="the name of the file that's created as a result of this request; if set to null, an"
                   + " arbitrary name will be chosen")
           @QueryParam("filename")
           @DefaultValue("downloaded.csv")
           String filename,

           @Parameter(description="the position of the record from which to start converting into delimited values")
           @QueryParam("offset")
           @DefaultValue("0")
           int offset,

           @Parameter(description="the maximum number of records to convert into delimited values, starting from" +
                   " offset.  Pass -1 to specify all records")
           @QueryParam("length")
           @DefaultValue("1000")
           int length,

           @Parameter(description="when set to 'true', the first row is to contain the name of each requested column" +
                   " (which implies it is erroneous to set this parameter when requestedColumns hasn't been)." +
                   " The names to use for each column can be overridden using the preferredColumnNames parameter")
           @QueryParam("prependHeaderRow")
           final boolean prependHeaderRow,

           @Parameter(description ="a list of column names meant to match those in requestedColumns that must appear in" +
                   " the column header row (which implies it is erroneous to set this parameter when" +
                   " requestedColumns and prependHeaderRow are not both set). This list of column names is allowed" +
                   " to have fewer entries than requestedColumns (in which case, the default names will be used" +
                   " for unmatched columns) but is not allowed to have more. Any entry in the list that is an" +
                   " empty string also signifies that the default name is acceptable for that column")
           @QueryParam("preferredColumnNames")
           List<String> preferredColumnNames) {
      rejectUnrecognizedQueryParams(info, "fieldSeparator", "requestedColumns", "quotingStrategy",
              "quoteChar", "decimalSeparator", "charsetEncoding", "filter", "filename", "offset", "length",
              "prependHeaderRow", "preferredColumnNames");

      CSVExportHelper csvExportHelper = new CSVExportHelper();
      String charsetName = charsetEncoding.replaceAll("-with.*", ""); // "UTF-8-with-BOM" becomes "UTF-8"
      final Charset chosenCharset;
      final boolean mustUseBOM;

      try {
         chosenCharset = Charset.forName(charsetName);
         mustUseBOM = charsetEncoding.contains("-with-BOM");
      } catch (UnsupportedCharsetException | IllegalCharsetNameException e) {
         throw new ValidationException(format("The value %s is not one of the supported charsetEncodings",
                 charsetEncoding));
      }

      StreamingOutput streamingOutput = csvExportHelper.streamCSVOut(repo, fieldSeparator.charAt(0), requestedColumns,
              quotingStrategy, quoteChar.charAt(0), chosenCharset, mustUseBOM, filter, offset,
              length, prependHeaderRow, preferredColumnNames);

      // using a string template generate a header

      return Response
              .ok(streamingOutput)
              .header("Content-Disposition", String.format("attachment; filename=\"%s\"", filename))
              .header("Content-Type", "text/csv")
              .build();
   }





   @Path("list")
   @GET
   @Produces(MediaType.APPLICATION_JSON)
   @SecurityRequirement(name = "bearerAuth")
   @APIResponses(value = {
           @APIResponse(responseCode = "200", description = "Success", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Collection.class))),
           @APIResponse(responseCode = "400", description = "Bad Request / bad argument", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
   })
   public Collection<T> getList(@DefaultValue("0")
                           @QueryParam("skip") int skip,
                           @DefaultValue("50")@QueryParam("limit") int limit,
                           @QueryParam("filter") String filter,
                           @QueryParam("sort") String sort,
                           @QueryParam("projection") String projection) {

      try {
         List<ProjectionField> projectionFields = null;
         List<SortField> sortFields = null;
         if (sort != null || projection != null) {

            if (sort != null) {
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
            projectionFields = FilterUtils.convertProjectionFields(projection);
         }


         List<T> ups = repo.getListByQuery(skip, limit, filter, sortFields, projectionFields);
         long count = repo.getCount(filter);

         Collection<T> collection;
         if (sortFields == null )
            collection = new Collection<>(ups, skip, limit, filter, count);
         else
            collection = new Collection<>(ups, skip, limit, filter, count, sortFields);

         // fill in ui-actions
         collection = repo.fillUIActions(collection);

         return collection;
      } catch (IllegalArgumentException | ValidationException e) {
         RestError error = RestError.builder()
                 .status(Response.Status.BAD_REQUEST.getStatusCode())
                 .statusMessage(e.getMessage()).build();
         throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity(error).build());

      }

   }

   @Path("schema")
   @GET
   @Produces(MediaType.APPLICATION_JSON)
   @SecurityRequirement(name = "bearerAuth")
   public JsonSchema getSchema() throws JsonMappingException {
      JSONUtils utils = JSONUtils.instance();
      JsonSchema schema = utils.getSchema(repo.getPersistentClass());
      return schema;
   }

   @APIResponses(value = {
           @APIResponse(responseCode = "200", description = "Success", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SuccessResponse.class))),
           @APIResponse(responseCode = "400", description = "Validation Error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
   })
   @POST
   @Produces(MediaType.APPLICATION_JSON)
   @Consumes(MediaType.APPLICATION_JSON)
   @SecurityRequirement(name = "bearerAuth")
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
   @SecurityRequirement(name = "bearerAuth")
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

   @Path("refName/{refName}")
   @DELETE
   @Produces(MediaType.APPLICATION_JSON)
   @Consumes(MediaType.APPLICATION_JSON)
   @SecurityRequirement(name = "bearerAuth")
   @APIResponses(value = {
           @APIResponse(responseCode = "200", description = "Entity found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SuccessResponse.class))),
           @APIResponse(responseCode = "404", description = "Entity not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
   })
   public Response deleteByPathRefName(@PathParam("refName") String refName) throws ReferentialIntegrityViolationException {
      return deleteByRefName(refName);
   }

   @Path("refName")
   @DELETE
   @Produces(MediaType.APPLICATION_JSON)
   @Consumes(MediaType.APPLICATION_JSON)
   @SecurityRequirement(name = "bearerAuth")
   @APIResponses(value = {
           @APIResponse(responseCode = "200", description = "Entity found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SuccessResponse.class))),
           @APIResponse(responseCode = "404", description = "Entity not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
   })
   public Response deleteByRefName(@QueryParam("refName") String refName) throws ReferentialIntegrityViolationException {
      Objects.requireNonNull(refName, "Null argument passed to delete, api requires a non-null refName");
      Optional<T> model = repo.findByRefName(refName);
      return deleteEntity(refName, model);
   }


   @Path("id/{id}")
   @DELETE
   @Produces(MediaType.APPLICATION_JSON)
   @Consumes(MediaType.APPLICATION_JSON)
   @SecurityRequirement(name = "bearerAuth")
   @APIResponses(value = {
           @APIResponse(responseCode = "200", description = "Entity found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SuccessResponse.class))),
           @APIResponse(responseCode = "404", description = "Entity not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
   })
   public Response deleteByPathId(@PathParam("id") String id) throws ReferentialIntegrityViolationException {
      return delete(id);
   }

   @Path("id")
   @DELETE
   @Produces(MediaType.APPLICATION_JSON)
   @Consumes(MediaType.APPLICATION_JSON)
   @SecurityRequirement(name = "bearerAuth")
   @APIResponses(value = {
           @APIResponse(responseCode = "200", description = "Entity found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SuccessResponse.class))),
           @APIResponse(responseCode = "404", description = "Entity not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
   })
   public Response delete(@QueryParam("id") String id) throws ReferentialIntegrityViolationException {
     Objects.requireNonNull(id, "Null argument passed to delete, api requires a non-null id");
     Optional<T> model = repo.findById(id);
     return deleteEntity(id, model);
   }

   protected Response deleteEntity(String id, Optional<T> model) throws ReferentialIntegrityViolationException {
      if (model.isPresent()) {
         long deletedCount = repo.delete(model.get());
         if (deletedCount != 0) {
            SuccessResponse r =  new SuccessResponse();
            r.setMessage("Delete successful");
            r.setStatusCode(Response.Status.OK.getStatusCode());
            return Response.ok().entity(r).build();
         } else {
            RestError error = RestError.builder()
                    .statusMessage("Entity with identifier:" +  id + " was found but delete returned 0 indicating the entity may not have been deleted.  Retry your request")
                    .reasonMessage("Delete Operation returned 0 when 1 was expected")
                    .debugMessage("MongoDB delete operation returned 0")
                    .build();

            return Response.status(Response.Status.NOT_MODIFIED).entity(error).build();
         }
      } else {
         RestError error = RestError.builder()
                 .status(Response.Status.NOT_FOUND.getStatusCode())
                 .statusMessage("identifier:" + id + " was not found").build();
         return Response.status(Response.Status.NOT_FOUND).entity(error).build();
      }
   }
   protected void rejectUnrecognizedQueryParams(UriInfo info, String... recognizedQueryParams)
           throws ValidationException {

      Set<String> supplied = new HashSet<>(info.getQueryParameters().keySet());

      supplied.removeAll(Arrays.asList(recognizedQueryParams));

      if (! supplied.isEmpty()) {
         List sampleRejects = new ArrayList(supplied);

         if (supplied.size() > MAXIMUM_REJECTS_SHOWN) {
            sampleRejects = sampleRejects.subList(0, MAXIMUM_REJECTS_SHOWN);
         }

         throw new ValidationException(format("Several unrecognized query parameters were supplied, including %s",
                 sampleRejects));
      }
   }
}
