package com.e2eq.framework.rest.resources;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.InvalidStateTransitionException;
import com.e2eq.framework.model.persistent.base.*;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.rest.models.*;
import com.e2eq.framework.rest.models.Collection;
import com.e2eq.framework.model.persistent.imports.ImportProfile;
import com.e2eq.framework.model.persistent.morphia.ImportProfileRepo;
import com.e2eq.framework.util.CSVExportHelper;
import com.e2eq.framework.util.CSVImportHelper;
import com.e2eq.framework.util.FilterUtils;
import com.e2eq.framework.util.JSONUtils;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.module.jsonSchema.jakarta.JsonSchema;

import dev.morphia.query.ValidationException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.apache.commons.lang3.tuple.Pair;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.*;

import static java.lang.String.format;

/**
 * Base REST resource exposing CRUD style operations for an entity type.
 *
 * @param <T> The type of the entity
 * @param <R> The repository used to access the entity
 */
@SecurityScheme(
        securitySchemeName = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
@RolesAllowed({ "user", "admin", "system" })
public class BaseResource<T extends UnversionedBaseModel, R extends BaseMorphiaRepo<T>> {
   protected R repo;
   protected Class<T> modelClass;

   @Inject
   protected JsonWebToken jwt;

   @Inject
   protected RuleContext ruleContext;

   private static final int MAXIMUM_REJECTS_SHOWN = 5;

   @Inject
   protected CSVImportHelper csvImportHelper;

   protected BaseResource(R repo) {
      this.repo = repo;
      this.modelClass = extractModelClass();
   }

   /**
    * Extract the model class from the generic type parameter.
    * This allows us to get the @FunctionalMapping annotation from the model.
    */
   @SuppressWarnings("unchecked")
   private Class<T> extractModelClass() {
      try {
         Class<?> clazz = getClass();
         while (clazz != null && clazz != Object.class) {
            java.lang.reflect.Type genericSuperclass = clazz.getGenericSuperclass();
            if (genericSuperclass instanceof java.lang.reflect.ParameterizedType) {
               java.lang.reflect.ParameterizedType pt = (java.lang.reflect.ParameterizedType) genericSuperclass;
               if (BaseResource.class.isAssignableFrom((Class<?>) pt.getRawType())) {
                  java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
                  if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                     return (Class<T>) typeArgs[0];
                  }
               }
            }
            clazz = clazz.getSuperclass();
         }
      } catch (Exception e) {
         // Ignore - will return null
      }
      return null;
   }

   /**
    * Get the functional mapping from the model class.
    * This is the single source of truth for area/domain.
    */
   public com.e2eq.framework.annotations.FunctionalMapping getModelFunctionalMapping() {
      if (modelClass != null) {
         return modelClass.getAnnotation(com.e2eq.framework.annotations.FunctionalMapping.class);
      }
      return null;
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
   public Response byPathRefName (@Context HttpHeaders headers, @Parameter(description = "refName of the entity", required = true)
                                     @PathParam("refName") String refName) {
        return byRefName(headers,refName);
   }

   @Path("indexes/ensureIndexes/{realm}")
   @POST
   @SecurityRequirement(name = "bearerAuth")
   @RolesAllowed({"admin", "system"})
   @Produces(MediaType.APPLICATION_JSON)
   public Response ensureIndexes(@PathParam( "realm") String realm, @QueryParam("collectionName") String collectionName) {
       repo.ensureIndexes(realm, collectionName);
       return Response.ok().build();
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
   public Response byRefName (@Context HttpHeaders headers,
                              @Parameter(description = "Reference name of the entity", required = true)
                              @QueryParam("refName")
                              String refName) {
       if (refName == null || refName.isEmpty()) {
           throw new WebApplicationException( "refName is required to be non null and not empty", Response.Status.BAD_REQUEST);
       }

      String realm = headers.getHeaderString("X-Realm");
      Response response;
      Optional<T> opModel;
         if (realm == null )
            opModel = repo.findByRefName(refName);
         else
            opModel = repo.findByRefName(refName, realm);

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
           @Context HttpHeaders headers,
           @Parameter(description = "Id of the entity", required = true)
           @PathParam("id") String id) {
       if (id == null || id.isEmpty()) {
           throw new WebApplicationException( "id is required to be non null and not empty", Response.Status.BAD_REQUEST);
       }
      return byId(headers,id);
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
      @Context HttpHeaders headers,
           @Parameter(description = "Id of the entity", required = true)
           @QueryParam("id") String id) {

       String realmId = headers.getHeaderString("X-Realm");
       if (id == null || id.isEmpty()) {
           throw new WebApplicationException( "id is required to be non null and not empty", Response.Status.BAD_REQUEST);
       }
       Response response;

       Optional<T> opModel;

      if (realmId == null ) {
         opModel = repo.findById(id);
      } else {
         opModel = repo.findById(id, realmId);
      }

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
   public CounterResponse getCount(@Context HttpHeaders headers, @QueryParam("filter") String filter) {
      String realmId = headers.getHeaderString("X-Realm");
      try {
         long count;
         if (realmId == null) count = repo.getCount(filter); else count = repo.getCount(realmId, filter);
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
            @Parameter(description = "A non-empty list of the names of the columns expected in the CSV file that map to the model fields")
            @QueryParam("requestedColumns") List<String> requestedColumns) {
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

            CSVImportHelper.ImportResult<T> result = csvImportHelper.importCSV(
                    repo,
                    new FileInputStream(fileUpload.file),
                    fieldSeparator.charAt(0),
                    quoteChar.charAt(0),
                    skipHeaderRow,
                    requestedColumns,
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

    @POST
    @Path("csv/session")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create an import session by analyzing an uploaded CSV file and returning a preview")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "CSV analyzed; session created and preview returned"),
            @APIResponse(responseCode = "400", description = "Bad request - invalid CSV file or parameters")
    })
    public Response createCsvImportSession(
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
            @Parameter(description = "A non-empty list of the names of the columns expected in the CSV file that map to the model fields")
            @QueryParam("requestedColumns") List<String> requestedColumns
    ) {
        try {
            if (fileUpload.file == null) {
                throw new WebApplicationException("No file uploaded", Response.Status.BAD_REQUEST);
            }
            rejectUnrecognizedQueryParams(info, "fieldSeparator", "quotingStrategy",
                    "quoteChar", "skipHeaderRow", "charsetEncoding", "requestedColumns");

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

            CSVImportHelper.ImportResult<T> preview = csvImportHelper.analyzeCSV(
                    repo,
                    new FileInputStream(fileUpload.file),
                    fieldSeparator.charAt(0),
                    quoteChar.charAt(0),
                    skipHeaderRow,
                    requestedColumns,
                    chosenCharset,
                    mustUseBOM,
                    quotingStrategy
            );
            return Response.ok(preview).build();
        } catch (Exception e) {
            RestError error = RestError.builder()
                    .status(Response.Status.BAD_REQUEST.getStatusCode())
                    .statusMessage("Error analyzing CSV file: " + e.getMessage())
                    .build();
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
    }

    @POST
    @Path("csv/session/{sessionId}/commit")
    @Produces(MediaType.APPLICATION_JSON)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Commit a previously analyzed CSV import session (imports only error-free rows)")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Import committed"),
            @APIResponse(responseCode = "400", description = "Unknown or invalid session")
    })
    public Response commitCsvImportSession(
            @PathParam("sessionId") String sessionId
    ) {
        try {
            CSVImportHelper.CommitResult result = csvImportHelper.commitImport(sessionId, repo);
            return Response.ok(result).build();
        } catch (Exception e) {
            RestError error = RestError.builder()
                    .status(Response.Status.BAD_REQUEST.getStatusCode())
                    .statusMessage("Error committing import session: " + e.getMessage())
                    .build();
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
    }

    @DELETE
    @Path("csv/session/{sessionId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Cancel a CSV import session and discard its state")
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Session canceled (or did not exist)")
    })
    public Response cancelCsvImportSession(@PathParam("sessionId") String sessionId) {
        try {
            csvImportHelper.cancelImport(sessionId);
            return Response.noContent().build();
        } catch (Exception e) {
            // Even on error, do not leak details; treat as not found/canceled
            return Response.noContent().build();
        }
    }

    @Inject
    protected com.e2eq.framework.model.persistent.morphia.ImportSessionRowRepo importSessionRowRepo;

    @Inject
    protected ImportProfileRepo importProfileRepo;

    @GET
    @Path("csv/session/{sessionId}/rows")
    @Produces(MediaType.APPLICATION_JSON)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Fetch analyzed CSV session rows with pagination",
            description = "Returns persisted per-row analysis results for a session. Supports paging and basic filtering.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Rows returned"),
            @APIResponse(responseCode = "400", description = "Bad request - invalid parameters or session")
    })
    public Response getCsvImportSessionRows(
            @PathParam("sessionId") String sessionId,
            @DefaultValue("0") @QueryParam("skip") int skip,
            @DefaultValue("50") @QueryParam("limit") int limit,
            @DefaultValue("false") @QueryParam("onlyErrors") boolean onlyErrors,
            @QueryParam("intent") String intent // INSERT, UPDATE, SKIP
    ) {
        try {
            if (sessionId == null || sessionId.isEmpty()) {
                throw new WebApplicationException("sessionId is required", Response.Status.BAD_REQUEST);
            }
            java.util.List<dev.morphia.query.filters.Filter> filters = new java.util.ArrayList<>();
            filters.add(dev.morphia.query.filters.Filters.eq("sessionRefName", sessionId));
            if (onlyErrors) {
                filters.add(dev.morphia.query.filters.Filters.eq("hasErrors", true));
            }
            if (intent != null && !intent.isEmpty()) {
                filters.add(dev.morphia.query.filters.Filters.eq("intent", intent));
            }
            java.util.List<com.e2eq.framework.model.persistent.base.SortField> sortFields = java.util.List.of(
                    new com.e2eq.framework.model.persistent.base.SortField("rowNumber",
                            com.e2eq.framework.model.persistent.base.SortField.SortDirection.ASC)
            );
            java.util.List<com.e2eq.framework.model.persistent.imports.ImportSessionRow> rows =
                    importSessionRowRepo.getList(skip, limit, filters, sortFields);
            return Response.ok(rows).build();
        } catch (Exception e) {
            RestError error = RestError.builder()
                    .status(Response.Status.BAD_REQUEST.getStatusCode())
                    .statusMessage("Error fetching session rows: " + e.getMessage())
                    .build();
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
    }

    @POST
    @Path("csv/session/profile")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create an import session with ImportProfile support",
            description = "Analyzes CSV with value mappings, lookups, and transformations defined in the profile")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "CSV analyzed with profile; session created and preview returned"),
            @APIResponse(responseCode = "400", description = "Bad request - invalid CSV file, profile, or parameters")
    })
    public Response createCsvImportSessionWithProfile(
            @Context HttpHeaders headers,
            @Context UriInfo info,
            @BeanParam FileUpload fileUpload,
            @Parameter(description = "The refName of the ImportProfile to use for transformations")
            @QueryParam("profileRefName") String profileRefName,
            @Parameter(description = "A non-empty list of the names of the columns expected in the CSV file (optional if profile provides mappings)")
            @QueryParam("requestedColumns") List<String> requestedColumns
    ) {
        try {
            if (fileUpload.file == null) {
                throw new WebApplicationException("No file uploaded", Response.Status.BAD_REQUEST);
            }
            rejectUnrecognizedQueryParams(info, "profileRefName", "requestedColumns");

            String realmId = headers.getHeaderString("X-Realm");

            // Load import profile if specified
            ImportProfile profile = null;
            if (profileRefName != null && !profileRefName.isEmpty()) {
                profile = importProfileRepo.findByRefName(profileRefName, realmId)
                        .orElseThrow(() -> new ValidationException(
                                "ImportProfile not found: " + profileRefName));
            }

            CSVImportHelper.ImportResult<T> preview = csvImportHelper.analyzeCSVWithProfile(
                    repo,
                    new FileInputStream(fileUpload.file),
                    profile,
                    requestedColumns,
                    realmId
            );

            return Response.ok(preview).build();
        } catch (ValidationException e) {
            RestError error = RestError.builder()
                    .status(Response.Status.BAD_REQUEST.getStatusCode())
                    .statusMessage(e.getMessage())
                    .build();
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        } catch (Exception e) {
            RestError error = RestError.builder()
                    .status(Response.Status.BAD_REQUEST.getStatusCode())
                    .statusMessage("Error analyzing CSV file with profile: " + e.getMessage())
                    .build();
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
    }

    @PUT
    @Path("activeStatus/{id}")
    public Response updateActiveStatus(@PathParam("id") ObjectId id, boolean active) {
      long modifyCount = repo.updateActiveStatus(id, active);
      if (modifyCount == 0) {
         return Response.status(Response.Status.NOT_FOUND).build();
      } else {
         return Response.ok().build();
      }
    }


   @GET
   @Path("csv")
   @SecurityRequirement(name = "bearerAuth")
   @Operation(summary = "Retrieve a list of Entities in CSV format")
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




   @Path("entityref")
   @POST
   @Produces(MediaType.APPLICATION_JSON)
   @Consumes(MediaType.APPLICATION_JSON)
   @SecurityRequirement(name = "bearerAuth")
   @APIResponses(value = {
           @APIResponse(responseCode = "200", description = "Success", content = @Content(mediaType = "application/json", schema = @Schema(implementation = EntityReference.class))),
           @APIResponse(responseCode = "400", description = "Bad Request / bad argument", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
   })
   public List<T> convertEntityRefList(@Context HttpHeaders headers, @RequestBody List<EntityReference> entityRefList) {
      String realmId = headers.getHeaderString("X-Realm");
      if (realmId == null)
        return repo.getListFromReferences(entityRefList);
      else
         return repo.getListFromReferences(realmId, entityRefList);
   }


    @Path("entityref")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @SecurityRequirement(name = "bearerAuth")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "Success", content = @Content(mediaType = "application/json", schema = @Schema(implementation = EntityReference.class))),
            @APIResponse(responseCode = "400", description = "Bad Request / bad argument", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
    })
    public List<EntityReference> getEntityRefList(@Context HttpHeaders headers,
                                                  @QueryParam("skip") int skip,
                                                  @DefaultValue("50") @QueryParam("limit") int limit,
                                                  @QueryParam("filter") String filter,
                                                  @QueryParam("sort") String sort) {

        List<SortField> sortFields = null;
        if (sort != null) {
            sortFields = convertToSortField(sort);
        } else {
            sortFields = null;
        }
        String realm = headers.getHeaderString("X-Realm");
        if (realm == null)
          return repo.getEntityReferenceListByQuery(skip, limit, filter, sortFields);
        else
            return repo.getEntityReferenceListByQuery(realm, skip, limit, filter, sortFields);
    }



   @Path("list")
   @GET
   @Produces(MediaType.APPLICATION_JSON)
   @SecurityRequirement(name = "bearerAuth")
   @APIResponses(value = {
           @APIResponse(responseCode = "200", description = "Success", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Collection.class))),
           @APIResponse(responseCode = "400", description = "Bad Request / bad argument", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
   })
   public Collection<T> getList(@Context HttpHeaders headers,
                                @DefaultValue("0")
                                @QueryParam("skip") int skip,
                                @DefaultValue("50")@QueryParam("limit") int limit,
                                @QueryParam("filter") String filter,
                                @QueryParam("sort") String sort,
                                @QueryParam("projection") String projection,
                                @QueryParam("uiActions") Boolean uiActions) {

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
            // Only convert projection if projection parameter is actually provided
            if (projection != null) {
               projectionFields = FilterUtils.convertProjectionFields(projection);
            }
         }

         String realmId = headers.getHeaderString("X-Realm");
         List<T> ups;
         long count;
         // Determine whether to compute UI actions.
         // Policy:
         // - If projection is absent: compute unless uiActions explicitly false.
         // - If projection is present: compute only if uiActions explicitly true.
         boolean projectionPresent = projection != null;
         boolean computeUiActions = !projectionPresent ? (uiActions == null || Boolean.TRUE.equals(uiActions))
                 : Boolean.TRUE.equals(uiActions);

         // If we will compute UI actions and projections are present, ensure dataDomain is included
         if (computeUiActions && projectionFields != null) {
            boolean hasDataDomain = projectionFields.stream()
                    .anyMatch(pf -> "dataDomain".equals(pf.getFieldName()));
            if (!hasDataDomain) {
               // add include for dataDomain
               projectionFields.add(new ProjectionField("dataDomain", ProjectionField.ProjectionType.INCLUDE));
            }
         }
         if (realmId == null) {
            ups = repo.getListByQuery(skip, limit, filter, sortFields, projectionFields);
            count =repo.getCount(filter);
         }
         else {
            ups = repo.getListByQuery(realmId, skip, limit, filter, sortFields, projectionFields);
            count = repo.getCount(realmId, filter);
         }


         Collection<T> collection;
         if (sortFields == null )
            collection = new Collection<>(ups, skip, limit, filter, count);
         else
            collection = new Collection<>(ups, skip, limit, filter, count, sortFields);

         // fill in ui-actions (conditionally based on projection/uiActions)
         if (computeUiActions) {
            collection = repo.fillUIActions(collection);
         }
         collection.setFilter(filter);
         collection.setRealm(realmId == null ? repo.getDatabaseName() : realmId);

         return collection;
      } catch (IllegalArgumentException | ValidationException e) {
         RestError error = RestError.builder()
                 .status(Response.Status.BAD_REQUEST.getStatusCode())
                 .statusMessage(e.getMessage()).build();
         throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).entity(error).build());

      }

   }

   /**
    * Backward-compatible overload for subclasses and callers that use the historical signature
    * (without the uiActions parameter). This method intentionally has no JAX-RS annotations to
    * avoid duplicate endpoint exposure from the framework class. Resource subclasses may still
    * expose their own endpoints with this signature via annotations.
    *
    * Delegates to the newer signature with a null uiActions flag, which preserves the new policy:
    * - Without projection: compute UI actions by default.
    * - With projection: skip UI actions unless explicitly requested (uiActions=true).
    */
   @Deprecated
   public Collection<T> getList(@Context HttpHeaders headers,
                                @DefaultValue("0") @QueryParam("skip") int skip,
                                @DefaultValue("50") @QueryParam("limit") int limit,
                                @QueryParam("filter") String filter,
                                @QueryParam("sort") String sort,
                                @QueryParam("projection") String projection) {
      return getList(headers, skip, limit, filter, sort, projection, null);
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
   public T save(@Context HttpHeaders headers, T model) {
       if (model == null) {
           throw new WebApplicationException( "Attempt to save null, check body of request, or the serialization of the body failed", Response.Status.BAD_REQUEST);
       }
       String realmId = headers.getHeaderString("X-Realm");
       if (realmId == null) {
          model = repo.save(model);
       } else {
          model = repo.save(realmId, model);
       }
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
   public Response update(@Context HttpHeaders headers, @QueryParam("id") String id, @QueryParam("pairs") Pair<String,Object>... pairs) throws InvalidStateTransitionException {

      String realmId = headers.getHeaderString("X-Realm");
      long updated = 0;
      if (realmId == null) {
        updated =  repo.update(id, pairs);
      } else {
        updated = repo.update(realmId, id, pairs);
      }

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

   // --- Bulk update endpoints ---
   @Path("bulk/setByQuery")
   @PUT
   @Produces(MediaType.APPLICATION_JSON)
   @Consumes(MediaType.APPLICATION_JSON)
   @SecurityRequirement(name = "bearerAuth")
   @Operation(summary = "Bulk update entities by query (ANTLR DSL)")
   @APIResponses(value = {
           @APIResponse(responseCode = "200", description = "Success", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CounterResponse.class))),
           @APIResponse(responseCode = "400", description = "Bad Request - bad arguments", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
   })
   public Response updateManyByQuery(@Context HttpHeaders headers,
                                     @QueryParam("filter") String filter,
                                     @QueryParam("ignoreRules") @DefaultValue("false") boolean ignoreRules,
                                     @QueryParam("pairs") Pair<String,Object>... pairs) {
       try {
           String realmId = headers.getHeaderString("X-Realm");
           long modified;
           if (realmId == null) {
               if (!ignoreRules) {
                   modified = repo.updateManyByQuery(filter, pairs);
               } else {
                   RestError error = RestError.builder()
                           .status(Response.Status.BAD_REQUEST.getStatusCode())
                           .statusMessage("ignoreRules=true requires X-Realm header to be specified")
                           .build();
                   return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
               }
           } else {
               if (!ignoreRules) {
                   modified = repo.updateManyByQuery(realmId, filter, pairs);
               } else {
                   RestError error = RestError.builder()
                           .status(Response.Status.BAD_REQUEST.getStatusCode())
                           .statusMessage("ignoreRules=true is not supported in this endpoint")
                           .build();
                   return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
               }
           }
           CounterResponse response = new CounterResponse(modified);
           response.setStatusCode(Response.Status.OK.getStatusCode());
           response.setMessage(String.format("Updated: %d", modified));
           return Response.ok(response).build();
       } catch (IllegalArgumentException | ValidationException | InvalidStateTransitionException e) {
           RestError error = RestError.builder()
                   .status(Response.Status.BAD_REQUEST.getStatusCode())
                   .statusMessage(e.getMessage()).build();
           return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
       }
   }

   @Path("bulk/setByIds")
   @PUT
   @Produces(MediaType.APPLICATION_JSON)
   @Consumes(MediaType.APPLICATION_JSON)
   @SecurityRequirement(name = "bearerAuth")
   @Operation(summary = "Bulk update entities by a list of ids")
   @APIResponses(value = {
           @APIResponse(responseCode = "200", description = "Success", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CounterResponse.class))),
           @APIResponse(responseCode = "400", description = "Bad Request - bad arguments", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
   })
   public Response updateManyByIds(@Context HttpHeaders headers,
                                   @RequestBody(description = "List of ids to update") List<String> ids,
                                   @QueryParam("pairs") Pair<String,Object>... pairs) {
       try {
           if (ids == null || ids.isEmpty()) {
               CounterResponse response = new CounterResponse(0);
               response.setStatusCode(Response.Status.OK.getStatusCode());
               response.setMessage("Updated: 0");
               return Response.ok(response).build();
           }
           List<ObjectId> objectIds = new ArrayList<>(ids.size());
           for (String s : ids) objectIds.add(new ObjectId(s));
           String realmId = headers.getHeaderString("X-Realm");
           long modified = (realmId == null)
                   ? repo.updateManyByIds(objectIds, pairs)
                   : repo.updateManyByIds(realmId, objectIds, pairs);
           CounterResponse response = new CounterResponse(modified);
           response.setStatusCode(Response.Status.OK.getStatusCode());
           response.setMessage(String.format("Updated: %d", modified));
           return Response.ok(response).build();
       } catch (IllegalArgumentException | ValidationException | InvalidStateTransitionException e) {
           RestError error = RestError.builder()
                   .status(Response.Status.BAD_REQUEST.getStatusCode())
                   .statusMessage(e.getMessage()).build();
           return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
       }
   }

   @Path("bulk/setByRefAndDomain")
   @PUT
   @Produces(MediaType.APPLICATION_JSON)
   @Consumes(MediaType.APPLICATION_JSON)
   @SecurityRequirement(name = "bearerAuth")
   @Operation(summary = "Bulk update entities by (refName, dataDomain) pairs")
   @APIResponses(value = {
           @APIResponse(responseCode = "200", description = "Success", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CounterResponse.class))),
           @APIResponse(responseCode = "400", description = "Bad Request - bad arguments", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RestError.class)))
   })
   public Response updateManyByRefAndDomain(@Context HttpHeaders headers,
                                            @RequestBody(description = "List of (refName, dataDomain) pairs") List<EntityRefDomainSelector> selectors,
                                            @QueryParam("pairs") Pair<String,Object>... pairs) {
       try {
           if (selectors == null || selectors.isEmpty()) {
               CounterResponse response = new CounterResponse(0);
               response.setStatusCode(Response.Status.OK.getStatusCode());
               response.setMessage("Updated: 0");
               return Response.ok(response).build();
           }
           List<org.apache.commons.lang3.tuple.Pair<String, DataDomain>> items = new ArrayList<>(selectors.size());
           for (EntityRefDomainSelector s : selectors) {
               items.add(org.apache.commons.lang3.tuple.Pair.of(s.refName, s.dataDomain));
           }
           String realmId = headers.getHeaderString("X-Realm");
           long modified = (realmId == null)
                   ? repo.updateManyByRefAndDomain(items, pairs)
                   : repo.updateManyByRefAndDomain(realmId, items, pairs);
           CounterResponse response = new CounterResponse(modified);
           response.setStatusCode(Response.Status.OK.getStatusCode());
           response.setMessage(String.format("Updated: %d", modified));
           return Response.ok(response).build();
       } catch (IllegalArgumentException | ValidationException | InvalidStateTransitionException e) {
           RestError error = RestError.builder()
                   .status(Response.Status.BAD_REQUEST.getStatusCode())
                   .statusMessage(e.getMessage()).build();
           return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
       }
   }

   // helper request body type
   public static class EntityRefDomainSelector {
       public String refName;
       public DataDomain dataDomain;
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
   public Response deleteByPathRefName(@Context HttpHeaders headers, @PathParam("refName") String refName) throws ReferentialIntegrityViolationException {
      return deleteByRefName(headers,refName);
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
   public Response deleteByRefName(@Context HttpHeaders headers,  @QueryParam("refName") String refName) throws ReferentialIntegrityViolationException {
      Objects.requireNonNull(refName, "Null argument passed to delete, api requires a non-null refName");
      String realmId = headers.getHeaderString("X-Realm");
      if (realmId == null) {
         Optional<T> model = repo.findByRefName(refName);
         // Use the realm where the entity was found, not the default realm
         String actualRealmId = getRealmIdFromModel(model);
         return deleteEntity(actualRealmId, model);
      } else {
         Optional<T> model = repo.findByRefName(realmId, refName);
         return deleteEntity(realmId, model);
      }
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
   public Response deleteByPathId(@Context HttpHeaders headers, @PathParam("id") String id) throws ReferentialIntegrityViolationException {
      return delete(headers,id);
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
   public Response delete(@Context HttpHeaders headers, @QueryParam("id") String id) throws ReferentialIntegrityViolationException {
     Objects.requireNonNull(id, "Null argument passed to delete, api requires a non-null id");
     String realmId = headers.getHeaderString("X-Realm");
     if (realmId == null) {
        Optional<T> model = repo.findById(id);
        // Use the realm where the entity was found, not the default realm
        String actualRealmId = getRealmIdFromModel(model);
        return deleteEntity(actualRealmId, id, model);
     } else {
        Optional<T> model = repo.findById(realmId, id);
        return deleteEntity(realmId, id, model);
     }
   }

   /**
    * Gets the realm ID from the model if available, otherwise from the security context.
    * Falls back to default realm if neither is available.
    *
    * @param model the optional model that may have modelSourceRealm set
    * @return the realm ID to use for the operation
    */
   private String getRealmIdFromModel(Optional<T> model) {
      // First, try to get the realm from the model (where it was found)
      if (model.isPresent() && model.get().getModelSourceRealm() != null && !model.get().getModelSourceRealm().isEmpty()) {
         return model.get().getModelSourceRealm();
      }
      
      // Otherwise, try to get it from the security context via the repo
      if (repo instanceof com.e2eq.framework.model.persistent.morphia.MorphiaRepo) {
         return ((com.e2eq.framework.model.persistent.morphia.MorphiaRepo<?>) repo).getSecurityContextRealmId();
      }
      
      // Fall back to default realm
      return ruleContext.getDefaultRealm();
   }

   /**
    * Delete entity by realmId and model (for refName-based deletes).
    * Delegates to the 3-parameter version with id extracted from the model if present.
    *
    * @param realmId the realm ID where the entity should be deleted
    * @param model the optional model to delete
    * @return the response indicating success or failure
    */
   protected Response deleteEntity(String realmId, Optional<T> model) throws ReferentialIntegrityViolationException {
      String id = model.isPresent() ? model.get().getId().toHexString() : null;
      return deleteEntity(realmId, id, model);
   }

   protected Response deleteEntity(String realmId,String id, Optional<T> model) throws ReferentialIntegrityViolationException {
      if (model.isPresent()) {
         long deletedCount = repo.delete(realmId,model.get());
         if (deletedCount != 0) {
            SuccessResponse r =  new SuccessResponse();
            r.setMessage("Delete successful");
            r.setStatusCode(Response.Status.OK.getStatusCode());
            return Response.ok().entity(r).build();
         } else {
            RestError error = RestError.builder()
                    .statusMessage("Entity with identifier:" +  model.get().getId().toHexString() + " was found but delete returned 0 indicating the entity may not have been deleted.  Retry your request")
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
