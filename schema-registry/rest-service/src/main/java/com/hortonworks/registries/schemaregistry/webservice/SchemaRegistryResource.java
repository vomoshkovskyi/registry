/**
 * Copyright 2016-2019 Cloudera, Inc.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
package com.hortonworks.registries.schemaregistry.webservice;

import com.codahale.metrics.annotation.Timed;
import com.hortonworks.registries.common.SchemaRegistryVersion;
import com.hortonworks.registries.common.catalog.CatalogResponse;
import com.hortonworks.registries.common.ha.LeadershipParticipant;
import com.hortonworks.registries.schemaregistry.authorization.AuthorizationAgent;
import com.hortonworks.registries.schemaregistry.authorization.AuthorizationAgentFactory;
import com.hortonworks.registries.storage.transaction.UnitOfWork;
import com.hortonworks.registries.common.util.WSUtils;
import com.hortonworks.registries.schemaregistry.AggregatedSchemaMetadataInfo;
import com.hortonworks.registries.schemaregistry.CompatibilityResult;
import com.hortonworks.registries.schemaregistry.ISchemaRegistry;
import com.hortonworks.registries.schemaregistry.SchemaBranch;
import com.hortonworks.registries.schemaregistry.SchemaFieldInfo;
import com.hortonworks.registries.schemaregistry.SchemaFieldQuery;
import com.hortonworks.registries.schemaregistry.SchemaIdVersion;
import com.hortonworks.registries.schemaregistry.SchemaMetadata;
import com.hortonworks.registries.schemaregistry.SchemaMetadataInfo;
import com.hortonworks.registries.schemaregistry.SchemaMetadataStorable;
import com.hortonworks.registries.schemaregistry.SchemaProviderInfo;
import com.hortonworks.registries.schemaregistry.SchemaVersion;
import com.hortonworks.registries.schemaregistry.SchemaVersionInfo;
import com.hortonworks.registries.schemaregistry.SchemaVersionKey;
import com.hortonworks.registries.schemaregistry.SchemaVersionMergeResult;
import com.hortonworks.registries.schemaregistry.SerDesInfo;
import com.hortonworks.registries.schemaregistry.SerDesPair;
import com.hortonworks.registries.schemaregistry.cache.SchemaRegistryCacheType;
import com.hortonworks.registries.schemaregistry.errors.IncompatibleSchemaException;
import com.hortonworks.registries.schemaregistry.errors.InvalidSchemaBranchDeletionException;
import com.hortonworks.registries.schemaregistry.errors.InvalidSchemaException;
import com.hortonworks.registries.schemaregistry.errors.SchemaBranchAlreadyExistsException;
import com.hortonworks.registries.schemaregistry.errors.SchemaBranchNotFoundException;
import com.hortonworks.registries.schemaregistry.errors.SchemaNotFoundException;
import com.hortonworks.registries.schemaregistry.errors.UnsupportedSchemaTypeException;
import com.hortonworks.registries.schemaregistry.state.SchemaLifecycleException;
import com.hortonworks.registries.schemaregistry.state.SchemaVersionLifecycleStateMachineInfo;
import com.hortonworks.registries.storage.exception.StorageException;
import com.hortonworks.registries.storage.search.OrderBy;
import com.hortonworks.registries.storage.search.WhereClause;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.security.authorize.AuthorizationException;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.*;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.hortonworks.registries.schemaregistry.DefaultSchemaRegistry.ORDER_BY_FIELDS_PARAM_NAME;
import static com.hortonworks.registries.schemaregistry.SchemaBranch.MASTER_BRANCH;

/**
 * Schema Registry resource that provides schema registry REST service.
 */
@Path("/api/v1/schemaregistry")
@Api(value = "/api/v1/schemaregistry", description = "Endpoint for Schema Registry service")
@Produces(MediaType.APPLICATION_JSON)
public class SchemaRegistryResource extends BaseRegistryResource {
    private static final Logger LOG = LoggerFactory.getLogger(SchemaRegistryResource.class);
    public static final String THROW_ERROR_IF_EXISTS = "_throwErrorIfExists";
    public static final String THROW_ERROR_IF_EXISTS_LOWER_CASE = THROW_ERROR_IF_EXISTS.toLowerCase();

    // reserved as schema related paths use these strings
    private static final String[] reservedNames = {"aggregate", "versions", "compatibility"};
    private final SchemaRegistryVersion schemaRegistryVersion;
    private final AuthorizationAgent authorizationAgent;

    public SchemaRegistryResource(ISchemaRegistry schemaRegistry,
                                  AtomicReference<LeadershipParticipant> leadershipParticipant,
                                  SchemaRegistryVersion schemaRegistryVersion) {
        super(schemaRegistry, leadershipParticipant);
        this.schemaRegistryVersion = schemaRegistryVersion;
        //TODO: Security is hardcoded should be read from config.
        this.authorizationAgent = AuthorizationAgentFactory.getAuthorizationAgent(true);
    }

    @GET
    @Path("/version")
    @ApiOperation(value = "Get the version information of this Schema Registry instance",
            response = SchemaRegistryVersion.class,
            tags = OPERATION_GROUP_OTHER)
    @Timed
    public Response getVersion(@Context UriInfo uriInfo) {
        return WSUtils.respondEntity(schemaRegistryVersion, Response.Status.OK);
    }

    @GET
    @Path("/schemaproviders")
    @ApiOperation(value = "Get list of registered Schema Providers",
            notes = "The Schema Registry supports different types of schemas, such as Avro, JSON etc. " + "" +
                    "A Schema Provider is needed for each type of schema supported by the Schema Registry. " +
                    "Schema Provider supports defining schema, serializing and deserializing data using the schema, " +
                    " and checking compatibility between different versions of the schema.",
            response = SchemaProviderInfo.class, responseContainer = "List",
            tags = OPERATION_GROUP_OTHER)
    @Timed
    public Response getRegisteredSchemaProviderInfos(@Context UriInfo uriInfo) {
        try {
            Collection<SchemaProviderInfo> schemaProviderInfos = schemaRegistry.getSupportedSchemaProviders();
            return WSUtils.respondEntities(schemaProviderInfos, Response.Status.OK);
        } catch (Exception ex) {
            LOG.error("Encountered error while listing schemas", ex);
            return WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }
    }

    //TODO : Get all the versions across all the branches

    @GET
    @Path("/schemas/aggregated")
    @ApiOperation(value = "Get list of schemas by filtering with the given query parameters",
            response = AggregatedSchemaMetadataInfo.class, responseContainer = "List", tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response listAggregatedSchemas(@Context UriInfo uriInfo,
                                          @Context SecurityContext securityContext) {
        try {
            MultivaluedMap<String, String> queryParameters = uriInfo.getQueryParameters();
            Map<String, String> filters = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : queryParameters.entrySet()) {
                List<String> value = entry.getValue();
                filters.put(entry.getKey(), value != null && !value.isEmpty() ? value.get(0) : null);
            }
            Collection<AggregatedSchemaMetadataInfo> schemaMetadatas = authorizationAgent
            .authorizeListAggregatedSchemas(securityContext, schemaRegistry.findAggregatedSchemaMetadata(filters));

            return WSUtils.respondEntities(schemaMetadatas, Response.Status.OK);
        } catch (SchemaBranchNotFoundException e) {
            return WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND,  e.getMessage());
        } catch (Exception ex) {
            LOG.error("Encountered error while listing schemas", ex);
            return WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }
    }

    @GET
    @Path("/schemas/{name}/aggregated")
    @ApiOperation(value = "Get aggregated schema information for the given schema name",
            response = SchemaMetadataInfo.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response getAggregatedSchemaInfo(@ApiParam(value = "Schema name", required = true) @PathParam("name") String schemaName,
                                            @Context SecurityContext securityContext) {
        Response response;
        try {
            AggregatedSchemaMetadataInfo schemaMetadataInfo = authorizationAgent
                    .authorizeGetAggregatedSchemaInfo(securityContext, schemaRegistry.getAggregatedSchemaMetadataInfo(schemaName));
            if (schemaMetadataInfo != null) {
                response = WSUtils.respondEntity(schemaMetadataInfo, Response.Status.OK);
            } else {
                response = WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, schemaName);
            }
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (SchemaBranchNotFoundException e) {
            return WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND,  e.getMessage());
        } catch (Exception ex) {
            LOG.error("Encountered error while retrieving SchemaInfo with name: [{}]", schemaName, ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;
    }

    @GET
    @Path("/schemas")
    @ApiOperation(value = "Get list of schemas by filtering with the given query parameters",
            response = SchemaMetadataInfo.class, responseContainer = "List", tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response listSchemas(@Context UriInfo uriInfo,
                                @Context SecurityContext securityContext) {
        try {
            MultivaluedMap<String, String> queryParameters = uriInfo.getQueryParameters();
            Map<String, String> filters = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : queryParameters.entrySet()) {
                List<String> value = entry.getValue();
                filters.put(entry.getKey(), value != null && !value.isEmpty() ? value.get(0) : null);
            }

            Collection<SchemaMetadataInfo> schemaMetadatas = authorizationAgent
                    .authorizeFindSchemas(securityContext, () -> schemaRegistry.findSchemaMetadata(filters));

            return WSUtils.respondEntities(schemaMetadatas, Response.Status.OK);
        } catch (Exception ex) {
            LOG.error("Encountered error while listing schemas", ex);
            return WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }
    }

    @GET
    @Path("/search/schemas")
    @ApiOperation(value = "Search for schemas containing the given name and description",
            notes = "Search the schemas for given name and description, return a list of schemas that contain the field.",
            response = SchemaMetadataInfo.class, responseContainer = "List", tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response findSchemas(@Context UriInfo uriInfo,
                                @Context SecurityContext securityContext) {
        MultivaluedMap<String, String> queryParameters = uriInfo.getQueryParameters();
        try {
            Collection<SchemaMetadataInfo> schemaMetadataInfos = authorizationAgent
                    .authorizeFindSchemas(securityContext, () -> findSchemaMetadataInfos(queryParameters));
            return WSUtils.respondEntities(schemaMetadataInfos, Response.Status.OK);
        } catch (Exception ex) {
            LOG.error("Encountered error while finding schemas for given fields [{}]", queryParameters, ex);
            return WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }
    }

    private Collection<SchemaMetadataInfo> findSchemaMetadataInfos(MultivaluedMap<String, String> queryParameters) {
        Collection<SchemaMetadataInfo> schemaMetadataInfos;
        // name and description for now, complex queries are supported by backend and front end can send the json
        // query for those complex queries.
        if (queryParameters.containsKey(SchemaMetadataStorable.NAME)
                || queryParameters.containsKey(SchemaMetadataStorable.DESCRIPTION)) {
            String name = queryParameters.getFirst(SchemaMetadataStorable.NAME);
            String description = queryParameters.getFirst(SchemaMetadataStorable.DESCRIPTION);
            WhereClause whereClause = WhereClause.begin()
                                                 .contains(SchemaMetadataStorable.NAME, name)
                                                 .or()
                                                 .contains(SchemaMetadataStorable.DESCRIPTION, description)
                                                 .combine();
            //todo refactor orderby field in DefaultSchemaRegistry#search APIs merge with these APIs
            String orderByFieldStr = queryParameters.getFirst(ORDER_BY_FIELDS_PARAM_NAME);
            schemaMetadataInfos = schemaRegistry.searchSchemas(whereClause, getOrderByFields(orderByFieldStr));
        } else {
            schemaMetadataInfos = Collections.emptyList();
        }
        return schemaMetadataInfos;
    }

    private List<OrderBy> getOrderByFields(String value) {
        List<OrderBy> orderByList = new ArrayList<>();
        // _orderByFields=[<field-name>,<a/d>,]*
        // example can be : _orderByFields=foo,a,bar,d
        // order by foo with ascending then bar with descending
        String[] splitStrings = value.split(",");
        for (int i = 0; i < splitStrings.length; i += 2) {
            String ascStr = splitStrings[i + 1];
            boolean descending;
            if ("a".equals(ascStr)) {
                descending = false;
            } else if ("d".equals(ascStr)) {
                descending = true;
            } else {
                throw new IllegalArgumentException("Ascending or Descending identifier can only be 'a' or 'd' respectively.");
            }

            String fieldName = splitStrings[i];
            orderByList.add(descending ? OrderBy.desc(fieldName) : OrderBy.asc(fieldName));
        }

        return orderByList;
    }

    @GET
    @Path("/search/schemas/aggregated")
    @ApiOperation(value = "Search for schemas containing the given name and description",
            notes = "Search the schemas for given name and description, return a list of schemas that contain the field.",
            response = AggregatedSchemaMetadataInfo.class, responseContainer = "List", tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response findAggregatedSchemas(@Context UriInfo uriInfo,
                                          @Context SecurityContext securityContext) {
        MultivaluedMap<String, String> queryParameters = uriInfo.getQueryParameters();
        try {
            Collection<SchemaMetadataInfo> schemaMetadataInfos = findSchemaMetadataInfos(uriInfo.getQueryParameters());
            List<AggregatedSchemaMetadataInfo> aggregatedSchemaMetadataInfos = new ArrayList<>();
            for (SchemaMetadataInfo schemaMetadataInfo : schemaMetadataInfos) {
                SchemaMetadata schemaMetadata = schemaMetadataInfo.getSchemaMetadata();
                List<SerDesInfo> serDesInfos = new ArrayList<>(schemaRegistry.getSerDes(schemaMetadataInfo
                                                                                                .getSchemaMetadata()
                                                                                                .getName()));
                aggregatedSchemaMetadataInfos.add(
                        new AggregatedSchemaMetadataInfo(schemaMetadata,
                                                         schemaMetadataInfo.getId(),
                                                         schemaMetadataInfo.getTimestamp(),
                                                         schemaRegistry.getAggregatedSchemaBranch(schemaMetadata.getName()),
                                                         serDesInfos));
            }

            return WSUtils.respondEntities(authorizationAgent.authorizeFindAggregatedSchemas(securityContext, aggregatedSchemaMetadataInfos),
                    Response.Status.OK);
        } catch (SchemaBranchNotFoundException e) {
            return WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND,  e.getMessage());
        } catch (Exception ex) {
            LOG.error("Encountered error while finding schemas for given fields [{}]", queryParameters, ex);
            return WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }
    }

    @GET
    @Path("/search/schemas/fields")
    @ApiOperation(value = "Search for schemas containing the given field names",
            notes = "Search the schemas for given field names and return a list of schemas that contain the field.",
            response = SchemaVersionKey.class, responseContainer = "List", tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response findSchemasByFields(@Context UriInfo uriInfo,
                                        @Context SecurityContext securityContext) {
        MultivaluedMap<String, String> queryParameters = uriInfo.getQueryParameters();
        try {
            Collection<SchemaVersionKey> schemaVersionKeys = authorizationAgent
                    .authorizeFindSchemasByFields(securityContext,
                            schemaRegistry::getSchemaMetadataInfo,
                            schemaRegistry::getSchemaVersionInfo,
                            () -> schemaRegistry.findSchemasByFields(buildSchemaFieldQuery(queryParameters)));

            return WSUtils.respondEntities(schemaVersionKeys, Response.Status.OK);
        } catch (Exception ex) {
            LOG.error("Encountered error while finding schemas for given fields [{}]", queryParameters, ex);
            return WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }
    }

    private SchemaFieldQuery buildSchemaFieldQuery(MultivaluedMap<String, String> queryParameters) {
        SchemaFieldQuery.Builder builder = new SchemaFieldQuery.Builder();
        for (Map.Entry<String, List<String>> entry : queryParameters.entrySet()) {
            List<String> entryValue = entry.getValue();
            String value = entryValue != null && !entryValue.isEmpty() ? entryValue.get(0) : null;
            if (value != null) {
                if (SchemaFieldInfo.FIELD_NAMESPACE.equals(entry.getKey())) {
                    builder.namespace(value);
                } else if (SchemaFieldInfo.NAME.equals(entry.getKey())) {
                    builder.name(value);
                } else if (SchemaFieldInfo.TYPE.equals(entry.getKey())) {
                    builder.type(value);
                }
            }
        }

        return builder.build();
    }

    @POST
    @Path("/schemas")
    @ApiOperation(value = "Create a schema if it does not already exist",
            notes = "Creates a schema with the given schema information if it does not already exist." +
                    " A unique schema identifier is returned.",
            response = Long.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response addSchemaInfo(@ApiParam(value = "Schema to be added to the registry", required = true)
                                          SchemaMetadata schemaMetadata,
                                  @Context UriInfo uriInfo,
                                  @Context HttpHeaders httpHeaders,
                                  @Context SecurityContext securityContext) {
        return handleLeaderAction(uriInfo, () -> {
            Response response;
            try {
                schemaMetadata.trim();
                checkValueAsNullOrEmpty("Schema name", schemaMetadata.getName());
                checkValueAsNullOrEmpty("Schema type", schemaMetadata.getType());
                checkValidNames(schemaMetadata.getName());

                boolean throwErrorIfExists = isThrowErrorIfExists(httpHeaders);
                authorizationAgent.authorizeAddSchemaInfo(securityContext, schemaMetadata);
                Long schemaId = schemaRegistry.addSchemaMetadata(schemaMetadata, throwErrorIfExists);
                response = WSUtils.respondEntity(schemaId, Response.Status.CREATED);
            } catch (AuthorizationException e) {
                return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
            } catch (IllegalArgumentException ex) {
                LOG.error("Expected parameter is invalid", schemaMetadata, ex);
                response = WSUtils.respond(Response.Status.BAD_REQUEST, CatalogResponse.ResponseMessage.BAD_REQUEST_PARAM_MISSING, ex.getMessage());
            } catch (UnsupportedSchemaTypeException ex) {
                LOG.error("Unsupported schema type encountered while adding schema metadata [{}]", schemaMetadata, ex);
                response = WSUtils.respond(Response.Status.BAD_REQUEST, CatalogResponse.ResponseMessage.UNSUPPORTED_SCHEMA_TYPE, ex.getMessage());
            } catch (StorageException ex) {
                LOG.error("Unable to add schema metadata [{}]", schemaMetadata, ex);
                response = WSUtils.respond(Response.Status.BAD_REQUEST, CatalogResponse.ResponseMessage.ENTITY_CONFLICT, ex.getMessage());
            }
            catch (Exception ex) {
                LOG.error("Error encountered while adding schema info [{}] ", schemaMetadata, ex);
                response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR,
                                           CatalogResponse.ResponseMessage.EXCEPTION,
                                           String.format("Storing the given SchemaMetadata [%s] is failed", schemaMetadata.toString()));
            }

            return response;
        });
    }

    @POST
    @Path("/schemas/{name}")
    @ApiOperation(value = "Updates schema information for the given schema name",
        response = SchemaMetadataInfo.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response updateSchemaInfo(@ApiParam(value = "Schema name", required = true) @PathParam("name") String schemaName, 
                                     @ApiParam(value = "Schema to be added to the registry", required = true)
                                         SchemaMetadata schemaMetadata,
                                     @Context UriInfo uriInfo,
                                     @Context SecurityContext securityContext) {
        return handleLeaderAction(uriInfo, () -> {
            Response response;
            try {
                authorizationAgent.authorizeUpdateSchemaInfo(securityContext, schemaMetadata);
                SchemaMetadataInfo schemaMetadataInfo = schemaRegistry.updateSchemaMetadata(schemaName, schemaMetadata);
                if (schemaMetadataInfo != null) {
                    response = WSUtils.respondEntity(schemaMetadataInfo, Response.Status.OK);
                } else {
                    response = WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, schemaName);
                }
            } catch (AuthorizationException e) {
                return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
            } catch (IllegalArgumentException ex) {
                LOG.error("Expected parameter is invalid", schemaName, schemaMetadata, ex);
                response = WSUtils.respond(Response.Status.BAD_REQUEST, CatalogResponse.ResponseMessage.BAD_REQUEST_PARAM_MISSING, ex.getMessage());
            } catch (Exception ex) {
                LOG.error("Encountered error while retrieving SchemaInfo with name: [{}]", schemaName, ex);
                response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
            }
            return response;
        });
    }

    private void checkValidNames(String name) {
        for (String reservedName : reservedNames) {
            if (reservedName.equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("schema name [" + reservedName + "] is reserved");
            }
        }
    }

    private boolean isThrowErrorIfExists(HttpHeaders httpHeaders) {
        List<String> values = httpHeaders.getRequestHeader(THROW_ERROR_IF_EXISTS);
        if (values != null) {
            values = httpHeaders.getRequestHeader(THROW_ERROR_IF_EXISTS_LOWER_CASE);
        }
        return values != null && !values.isEmpty() && Boolean.parseBoolean(values.get(0));
    }

    @GET
    @Path("/schemas/{name}")
    @ApiOperation(value = "Get schema information for the given schema name",
            response = SchemaMetadataInfo.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response getSchemaInfo(@ApiParam(value = "Schema name", required = true) @PathParam("name") String schemaName,
                                  @Context SecurityContext securityContext) {
        Response response;
        try {
            SchemaMetadataInfo schemaMetadataInfo = authorizationAgent
                    .authorizeGetSchemaInfo(securityContext, schemaRegistry.getSchemaMetadataInfo(schemaName));
            if (schemaMetadataInfo != null) {
                response = WSUtils.respondEntity(schemaMetadataInfo, Response.Status.OK);
            } else {
                response = WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, schemaName);
            }
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (Exception ex) {
            LOG.error("Encountered error while retrieving SchemaInfo with name: [{}]", schemaName, ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;
    }

    @GET
    @Path("/schemasById/{schemaId}")
    @ApiOperation(value = "Get schema for a given schema identifier",
            response = SchemaMetadataInfo.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response getSchemaInfo(@ApiParam(value = "Schema identifier", required = true) @PathParam("schemaId") Long schemaId,
                                  @Context SecurityContext securityContext) {
        Response response;
        try {
            SchemaMetadataInfo schemaMetadataInfo = authorizationAgent
                    .authorizeGetSchemaInfo(securityContext, schemaRegistry.getSchemaMetadataInfo(schemaId));
            if (schemaMetadataInfo != null) {
                response = WSUtils.respondEntity(schemaMetadataInfo, Response.Status.OK);
            } else {
                response = WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, schemaId.toString());
            }
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (Exception ex) {
            LOG.error("Encountered error while retrieving SchemaInfo with schemaId: [{}]", schemaId, ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;
    }

    @DELETE
    @Path("/schemas/{name}")
    @ApiOperation(value = "Delete a schema metadata and all related data", tags = OPERATION_GROUP_SCHEMA)
    @UnitOfWork
    public Response deleteSchemaMetadata(@ApiParam(value = "Schema name", required = true) @PathParam("name") String schemaName,
                                        @Context UriInfo uriInfo,
                                        @Context SecurityContext securityContext) {
        try {
            authorizationAgent.authorizeDeleteSchemaMetadata(securityContext,
                    schemaRegistry.getSchemaMetadataInfo(schemaName));
            schemaRegistry.deleteSchema(schemaName);
            return WSUtils.respond(Response.Status.OK);
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (SchemaNotFoundException e) {
            LOG.error("No schema metadata found with name: [{}]", schemaName);
            return WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, schemaName);
        } catch (Exception ex) {
            LOG.error("Encountered error while deleting schema with name: [{}]", schemaName, ex);
            return WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }
    }

    @POST
    @Path("/schemas/{name}/versions/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @ApiOperation(value = "Register a new version of the schema by uploading schema version text",
            notes = "Registers the given schema version to schema with name if the given file content is not registered as a version for this schema, " +
                    "and returns respective version number." +
                    "In case of incompatible schema errors, it throws error message like 'Unable to read schema: <> using schema <>' ",
            response = Integer.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response uploadSchemaVersion(@ApiParam(value = "Schema name", required = true) @PathParam("name")
                                                String schemaName,
                                        @QueryParam("branch") @DefaultValue(MASTER_BRANCH) String schemaBranchName,
                                        @ApiParam(value = "Schema version text file to be uploaded", required = true)
                                        @FormDataParam("file") final InputStream inputStream,
                                        @ApiParam(value = "Description about the schema version to be uploaded", required = true)
                                        @FormDataParam("description") final String description,
                                        @QueryParam("disableCanonicalCheck") @DefaultValue("false") Boolean disableCanonicalCheck,
                                        @Context UriInfo uriInfo,
                                        @Context SecurityContext securityContext) {
        return handleLeaderAction(uriInfo, () -> {
            Response response;
            SchemaVersion schemaVersion = null;
            try {
                authorizationAgent.addSchemaVersion(securityContext,
                        schemaRegistry.getSchemaMetadataInfo(schemaName),
                        schemaBranchName);
                schemaVersion = new SchemaVersion(IOUtils.toString(inputStream, "UTF-8"),
                                                  description);
                response = addSchemaVersion(schemaBranchName,
                        schemaName,
                        schemaVersion,
                        disableCanonicalCheck,
                        uriInfo,
                        securityContext);
            } catch (AuthorizationException e) {
                return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
            } catch (IOException ex) {
                LOG.error("Encountered error while adding schema [{}] with key [{}]", schemaVersion, schemaName, ex, ex);
                response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
            }

            return response;
        });
    }

    @POST
    @Path("/schemas/{name}/versions")
    @ApiOperation(value = "Register a new version of the schema",
            notes = "Registers the given schema version to schema with name if the given schemaText is not registered as a version for this schema, " +
                    "and returns respective version number." +
                    "In case of incompatible schema errors, it throws error message like 'Unable to read schema: <> using schema <>' ",
            response = Integer.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response addSchemaVersion(@QueryParam("branch") @DefaultValue(MASTER_BRANCH) String schemaBranchName,
                                     @ApiParam(value = "Schema name", required = true) @PathParam("name")
                                      String schemaName,
                                     @ApiParam(value = "Details about the schema", required = true)
                                      SchemaVersion schemaVersion,
                                     @QueryParam("disableCanonicalCheck") @DefaultValue("false") Boolean disableCanonicalCheck,
                                     @Context UriInfo uriInfo,
                                     @Context SecurityContext securityContext) {
        return handleLeaderAction(uriInfo, () -> {
            Response response;
            try {
                LOG.info("adding schema version for name [{}] with [{}]", schemaName, schemaVersion);
                authorizationAgent.addSchemaVersion(securityContext,
                        schemaRegistry.getSchemaMetadataInfo(schemaName),
                        schemaBranchName);
                SchemaIdVersion version = schemaRegistry.addSchemaVersion(schemaBranchName, schemaName, schemaVersion, disableCanonicalCheck);
                response = WSUtils.respondEntity(version.getVersion(), Response.Status.CREATED);
            } catch (AuthorizationException e) {
                return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
            } catch (InvalidSchemaException ex) {
                LOG.error("Invalid schema error encountered while adding schema [{}] with key [{}]", schemaVersion, schemaName, ex);
                response = WSUtils.respond(Response.Status.BAD_REQUEST, CatalogResponse.ResponseMessage.INVALID_SCHEMA, ex.getMessage());
            } catch (IncompatibleSchemaException ex) {
                LOG.error("Incompatible schema error encountered while adding schema [{}] with key [{}]", schemaVersion, schemaName, ex);
                response = WSUtils.respond(Response.Status.BAD_REQUEST, CatalogResponse.ResponseMessage.INCOMPATIBLE_SCHEMA, ex.getMessage());
            } catch (UnsupportedSchemaTypeException ex) {
                LOG.error("Unsupported schema type encountered while adding schema [{}] with key [{}]", schemaVersion, schemaName, ex);
                response = WSUtils.respond(Response.Status.BAD_REQUEST, CatalogResponse.ResponseMessage.UNSUPPORTED_SCHEMA_TYPE, ex.getMessage());
            } catch (SchemaBranchNotFoundException e) {
                return WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND,  e.getMessage());
            } catch (Exception ex) {
                LOG.error("Encountered error while adding schema [{}] with key [{}]", schemaVersion, schemaName, ex, ex);
                response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
            }

            return response;
        });
    }

    @GET
    @Path("/schemas/{name}/versions/latest")
    @ApiOperation(value = "Get the latest version of the schema for the given schema name",
            response = SchemaVersionInfo.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response getLatestSchemaVersion(@ApiParam(value = "Schema name", required = true) @PathParam("name") String schemaName,
                                           @QueryParam("branch") @DefaultValue(MASTER_BRANCH) String schemaBranchName,
                                           @Context SecurityContext securityContext) {

        Response response;
        try {
            authorizationAgent.authorizeGetLatestSchemaVersion(securityContext,
                    schemaRegistry.getSchemaMetadataInfo(schemaName),
                    schemaBranchName);
            SchemaVersionInfo schemaVersionInfo = schemaRegistry.getLatestSchemaVersionInfo(schemaBranchName, schemaName);
            if (schemaVersionInfo != null) {
                response = WSUtils.respondEntity(schemaVersionInfo, Response.Status.OK);
            } else {
                LOG.info("No schemas found with schemakey: [{}]", schemaName);
                response = WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, schemaName);
            }
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (SchemaBranchNotFoundException e) {
            return WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND,  e.getMessage());
        } catch (Exception ex) {
            LOG.error("Encountered error while getting latest schema version for schemakey [{}]", schemaName, ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;

    }

    @GET
    @Path("/schemas/{name}/versions")
    @ApiOperation(value = "Get all the versions of the schema for the given schema name)",
            response = SchemaVersionInfo.class, responseContainer = "List", tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response getAllSchemaVersions(@ApiParam(value = "Schema name", required = true) @PathParam("name") String schemaName,
                                         @QueryParam("branch") @DefaultValue(MASTER_BRANCH) String schemaBranchName,
                                         @QueryParam("states") List<Byte> stateIds,
                                         @Context SecurityContext securityContext) {

        Response response;
        try {
            Collection<SchemaVersionInfo> schemaVersionInfos = authorizationAgent
                    .authorizeGetAllSchemaVersions(securityContext,
                            schemaRegistry.getSchemaMetadataInfo(schemaName),
                            schemaBranchName,
                            () -> schemaRegistry.getAllVersions(schemaBranchName, schemaName, stateIds));
            if (schemaVersionInfos != null) {
                response = WSUtils.respondEntities(schemaVersionInfos, Response.Status.OK);
            } else {
                LOG.info("No schemas found with schemakey: [{}]", schemaName);
                response = WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, schemaName);
            }
        } catch (SchemaBranchNotFoundException e) {
            return WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, e.getMessage());
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (Exception ex) {
            LOG.error("Encountered error while getting all schema versions for schemakey [{}]", schemaName, ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;
    }

    @GET
    @Path("/schemas/{name}/versions/{version}")
    @ApiOperation(value = "Get a version of the schema identified by the schema name",
            response = SchemaVersionInfo.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response getSchemaVersion(@ApiParam(value = "Schema name", required = true) @PathParam("name") String schemaMetadata,
                                     @ApiParam(value = "version of the schema", required = true) @PathParam("version") Integer versionNumber,
                                     @Context SecurityContext securityContext) {
        SchemaVersionKey schemaVersionKey = new SchemaVersionKey(schemaMetadata, versionNumber);

        Response response;
        try {
            SchemaVersionInfo schemaVersionInfo = schemaRegistry.getSchemaVersionInfo(schemaVersionKey);
            authorizationAgent
                    .authorizeGetSchemaVersion(securityContext,
                            schemaRegistry.getSchemaMetadataInfo(schemaMetadata),
                            schemaRegistry.getSchemaBranchesForVersion(schemaVersionInfo.getId()));
            response = WSUtils.respondEntity(schemaVersionInfo, Response.Status.OK);
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (SchemaNotFoundException e) {
            LOG.info("No schemas found with schemaVersionKey: [{}]", schemaVersionKey);
            response = WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, schemaVersionKey.toString());
        } catch (Exception ex) {
            LOG.error("Encountered error while getting all schema versions for schemakey [{}]", schemaMetadata, ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;
    }

    @GET
    @Path("/schemas/versionsById/{id}")
    @ApiOperation(value = "Get a version of the schema identified by the given versionid",
            response = SchemaVersionInfo.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response getSchemaVersionById(@ApiParam(value = "version identifier of the schema", required = true) @PathParam("id") Long versionId,
                                         @Context SecurityContext securityContext) {
        SchemaIdVersion schemaIdVersion = new SchemaIdVersion(versionId);

        Response response;
        try {
            SchemaVersionInfo schemaVersionInfo = schemaRegistry.getSchemaVersionInfo(schemaIdVersion);
            authorizationAgent.authorizeGetSchemaVersion(securityContext,
                    schemaRegistry.getSchemaMetadataInfo(schemaIdVersion.getSchemaMetadataId()),
                    schemaRegistry.getSchemaBranchesForVersion(versionId));
            response = WSUtils.respondEntity(schemaVersionInfo, Response.Status.OK);
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (SchemaNotFoundException e) {
            LOG.info("No schema version is found with schema version id : [{}]", versionId);
            response = WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, versionId.toString());
        } catch (Exception ex) {
            LOG.error("Encountered error while getting schema version with id [{}]", versionId, ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;
    }

    @GET
    @Path("/schemas/versionsByFingerprint/{fingerprint}")
    @ApiOperation(value = "Get a version of the schema with the given fingerprint",
            response = SchemaVersionInfo.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response getSchemaVersionByFingerprint(@ApiParam(value = "fingerprint of the schema text", required = true) @PathParam("fingerprint") String fingerprint,
                                                  @Context SecurityContext securityContext) {
        try {
            final SchemaVersionInfo schemaVersionInfo = schemaRegistry.findSchemaVersionByFingerprint(fingerprint);
            authorizationAgent.authorizeGetSchemaVersion(securityContext,
                    schemaRegistry.getSchemaMetadataInfo(schemaVersionInfo.getSchemaMetadataId()),
                    schemaRegistry.getSchemaBranchesForVersion(schemaVersionInfo.getId()));
            return WSUtils.respondEntity(schemaVersionInfo, Response.Status.OK);
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (SchemaNotFoundException e) {
            LOG.info("No schema version is found with fingerprint : [{}]", fingerprint);
            return WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, fingerprint);
        } catch (Exception ex) {
            LOG.error("Encountered error while getting schema version with fingerprint [{}]", fingerprint, ex);
            return WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }
    }

    @GET
    @Path("/schemas/versions/statemachine")
    @ApiOperation(value = "Get schema version life cycle states",
            response = SchemaVersionInfo.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    public Response getSchemaVersionLifeCycleStates() {
        Response response;
        try {
            SchemaVersionLifecycleStateMachineInfo states = schemaRegistry.getSchemaVersionLifecycleStateMachineInfo();
            response = WSUtils.respondEntity(states, Response.Status.OK);
        } catch (Exception ex) {
            LOG.error("Encountered error while getting schema version lifecycle states", ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;
    }

    @POST
    @Path("/schemas/versions/{id}/state/enable")
    @ApiOperation(value = "Enables version of the schema identified by the given versionid",
            response = Boolean.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response enableSchema(@ApiParam(value = "version identifier of the schema", required = true) @PathParam("id") Long versionId,
                                 @Context SecurityContext securityContext) {

        Response response;
        try {
            SchemaVersionInfo schemaVersionInfo = schemaRegistry.getSchemaVersionInfo(new SchemaIdVersion(versionId));
            authorizationAgent.authorizeVersionStateOperation(securityContext,
                    schemaRegistry.getSchemaMetadataInfo(schemaVersionInfo.getSchemaMetadataId()),
                    schemaRegistry.getSchemaBranchesForVersion(versionId));
            schemaRegistry.enableSchemaVersion(versionId);
            response = WSUtils.respondEntity(true, Response.Status.OK);
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (SchemaNotFoundException e) {
            LOG.info("No schema version is found with schema version id : [{}]", versionId);
            response = WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, versionId.toString());
        } catch(IncompatibleSchemaException e) {
            LOG.error("Encountered error while enabling schema version with id [{}]", versionId, e);
            response = WSUtils.respond(Response.Status.BAD_REQUEST, CatalogResponse.ResponseMessage.INCOMPATIBLE_SCHEMA, e.getMessage());
        } catch(SchemaLifecycleException e) {
            LOG.error("Encountered error while enabling schema version with id [{}]", versionId, e);
            response = WSUtils.respond(Response.Status.BAD_REQUEST, CatalogResponse.ResponseMessage.BAD_REQUEST, e.getMessage());
        } catch (Exception ex) {
            LOG.error("Encountered error while getting schema version with id [{}]", versionId, ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;
    }

    @POST
    @Path("/schemas/versions/{id}/state/disable")
    @ApiOperation(value = "Disables version of the schema identified by the given version id",
            response = Boolean.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response disableSchema(@ApiParam(value = "version identifier of the schema", required = true) @PathParam("id") Long versionId,
                                  @Context SecurityContext securityContext) {

        Response response;
        try {
            SchemaVersionInfo schemaVersionInfo = schemaRegistry.getSchemaVersionInfo(new SchemaIdVersion(versionId));
            authorizationAgent.authorizeVersionStateOperation(securityContext,
                    schemaRegistry.getSchemaMetadataInfo(schemaVersionInfo.getSchemaMetadataId()),
                    schemaRegistry.getSchemaBranchesForVersion(versionId));
            schemaRegistry.disableSchemaVersion(versionId);
            response = WSUtils.respondEntity(true, Response.Status.OK);
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (SchemaNotFoundException e) {
            LOG.info("No schema version is found with schema version id : [{}]", versionId);
            response = WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, versionId.toString());
        } catch(SchemaLifecycleException e) {
            LOG.error("Encountered error while disabling schema version with id [{}]", versionId, e);
            response = WSUtils.respond(Response.Status.BAD_REQUEST, CatalogResponse.ResponseMessage.BAD_REQUEST, e.getMessage());
        }catch (Exception ex) {
            LOG.error("Encountered error while getting schema version with id [{}]", versionId, ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;
    }

    @POST
    @Path("/schemas/versions/{id}/state/archive")
    @ApiOperation(value = "Disables version of the schema identified by the given version id",
            response = Boolean.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response archiveSchema(@ApiParam(value = "version identifier of the schema", required = true) @PathParam("id") Long versionId,
                                  @Context SecurityContext securityContext) {

        Response response;
        try {
            SchemaVersionInfo schemaVersionInfo = schemaRegistry.getSchemaVersionInfo(new SchemaIdVersion(versionId));
            authorizationAgent.authorizeVersionStateOperation(securityContext,
                    schemaRegistry.getSchemaMetadataInfo(schemaVersionInfo.getSchemaMetadataId()),
                    schemaRegistry.getSchemaBranchesForVersion(versionId));
            schemaRegistry.archiveSchemaVersion(versionId);
            response = WSUtils.respondEntity(true, Response.Status.OK);
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (SchemaNotFoundException e) {
            LOG.info("No schema version is found with schema version id : [{}]", versionId);
            response = WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, versionId.toString());
        } catch(SchemaLifecycleException e) {
            LOG.error("Encountered error while disabling schema version with id [{}]", versionId, e);
            response = WSUtils.respond(Response.Status.BAD_REQUEST, CatalogResponse.ResponseMessage.BAD_REQUEST, e.getMessage());
        }catch (Exception ex) {
            LOG.error("Encountered error while getting schema version with id [{}]", versionId, ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;
    }


    @POST
    @Path("/schemas/versions/{id}/state/delete")
    @ApiOperation(value = "Disables version of the schema identified by the given version id",
            response = Boolean.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response deleteSchema(@ApiParam(value = "version identifier of the schema", required = true) @PathParam("id") Long versionId,
                                 @Context SecurityContext securityContext) {

        Response response;
        try {
            SchemaVersionInfo schemaVersionInfo = schemaRegistry.getSchemaVersionInfo(new SchemaIdVersion(versionId));
            authorizationAgent.authorizeVersionStateOperation(securityContext,
                    schemaRegistry.getSchemaMetadataInfo(schemaVersionInfo.getSchemaMetadataId()),
                    schemaRegistry.getSchemaBranchesForVersion(versionId));
            schemaRegistry.deleteSchemaVersion(versionId);
            response = WSUtils.respondEntity(true, Response.Status.OK);
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (SchemaNotFoundException e) {
            LOG.info("No schema version is found with schema version id : [{}]", versionId);
            response = WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, versionId.toString());
        } catch(SchemaLifecycleException e) {
            LOG.error("Encountered error while disabling schema version with id [{}]", versionId, e);
            response = WSUtils.respond(Response.Status.BAD_REQUEST, CatalogResponse.ResponseMessage.BAD_REQUEST_WITH_MESSAGE, e.getMessage());
        } catch (Exception ex) {
            LOG.error("Encountered error while getting schema version with id [{}]", versionId, ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;
    }

    @POST
    @Path("/schemas/versions/{id}/state/startReview")
    @ApiOperation(value = "Disables version of the schema identified by the given version id",
            response = Boolean.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response startReviewSchema(@ApiParam(value = "version identifier of the schema", required = true) @PathParam("id") Long versionId,
                                      @Context SecurityContext securityContext) {

        Response response;
        try {
            SchemaVersionInfo schemaVersionInfo = schemaRegistry.getSchemaVersionInfo(new SchemaIdVersion(versionId));
            authorizationAgent.authorizeVersionStateOperation(securityContext,
                    schemaRegistry.getSchemaMetadataInfo(schemaVersionInfo.getSchemaMetadataId()),
                    schemaRegistry.getSchemaBranchesForVersion(versionId));
            schemaRegistry.startSchemaVersionReview(versionId);
            response = WSUtils.respondEntity(true, Response.Status.OK);
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (SchemaNotFoundException e) {
            LOG.info("No schema version is found with schema version id : [{}]", versionId);
            response = WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, versionId.toString());
        } catch(SchemaLifecycleException e) {
            LOG.error("Encountered error while disabling schema version with id [{}]", versionId, e);
            response = WSUtils.respond(Response.Status.BAD_REQUEST, CatalogResponse.ResponseMessage.BAD_REQUEST, e.getMessage());
        } catch (Exception ex) {
            LOG.error("Encountered error while getting schema version with id [{}]", versionId, ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;
    }

    @POST
    @Path("/schemas/versions/{id}/state/{stateId}")
    @ApiOperation(value = "Runs the state execution for schema version identified by the given version id and executes action associated with target state id",
            response = Boolean.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response executeState(@ApiParam(value = "version identifier of the schema", required = true) @PathParam("id") Long versionId,
                                 @ApiParam(value = "", required = true) @PathParam("stateId") Byte stateId,
                                 byte [] transitionDetails,
                                 @Context SecurityContext securityContext) {

        Response response;
        try {
            SchemaVersionInfo schemaVersionInfo = schemaRegistry.getSchemaVersionInfo(new SchemaIdVersion(versionId));
            authorizationAgent.authorizeVersionStateOperation(securityContext,
                    schemaRegistry.getSchemaMetadataInfo(schemaVersionInfo.getSchemaMetadataId()),
                    schemaRegistry.getSchemaBranchesForVersion(versionId));
            schemaRegistry.transitionState(versionId, stateId, transitionDetails);
            response = WSUtils.respondEntity(true, Response.Status.OK);
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (SchemaNotFoundException e) {
            LOG.info("No schema version is found with schema version id : [{}]", versionId);
            response = WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, versionId.toString());
        } catch(SchemaLifecycleException e) {
            LOG.error("Encountered error while disabling schema version with id [{}]", versionId, e);
            CatalogResponse.ResponseMessage badRequestResponse =
                    e.getCause() != null && e.getCause() instanceof IncompatibleSchemaException
                    ? CatalogResponse.ResponseMessage.INCOMPATIBLE_SCHEMA
                    : CatalogResponse.ResponseMessage.BAD_REQUEST;
            response = WSUtils.respond(Response.Status.BAD_REQUEST, badRequestResponse, e.getMessage());
        } catch (Exception ex) {
            LOG.error("Encountered error while getting schema version with id [{}]", versionId, ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;
    }

    @POST
    @Path("/schemas/{name}/compatibility")
    @ApiOperation(value = "Checks if the given schema text is compatible with all the versions of the schema identified by the name",
            response = CompatibilityResult.class, tags = OPERATION_GROUP_SCHEMA)
    @Timed
    @UnitOfWork
    public Response checkCompatibilityWithSchema(@QueryParam("branch") @DefaultValue(MASTER_BRANCH) String schemaBranchName,
                                                 @ApiParam(value = "Schema name", required = true) @PathParam("name") String schemaName,
                                                 @ApiParam(value = "schema text", required = true) String schemaText,
                                                 @Context SecurityContext securityContext) {
        Response response;
        try {
            authorizationAgent.authorizeCheckCompatibilityWithSchema(securityContext,
                    schemaRegistry.getSchemaMetadataInfo(schemaName),
                    schemaBranchName);
            CompatibilityResult compatibilityResult = schemaRegistry.checkCompatibility(schemaBranchName, schemaName, schemaText);
            response = WSUtils.respondEntity(compatibilityResult, Response.Status.OK);
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (SchemaNotFoundException e) {
            LOG.error("No schemas found with schemakey: [{}]", schemaName, e);
            response = WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, schemaName);
        } catch (SchemaBranchNotFoundException e) {
            return WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND,  e.getMessage());
        } catch (Exception ex) {
            LOG.error("Encountered error while checking compatibility with versions of schema with [{}] for given schema text [{}]", schemaName, schemaText, ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;
    }

    @GET
    @Path("/schemas/{name}/serdes")
    @ApiOperation(value = "Get list of Serializers registered for the given schema name",
            response = SerDesInfo.class, responseContainer = "List", tags = OPERATION_GROUP_SERDE)
    @Timed
    @UnitOfWork
    public Response getSerializers(@ApiParam(value = "Schema name", required = true) @PathParam("name") String schemaName,
                                   @Context SecurityContext securityContext) {
        Response response;
        try {
            SchemaMetadataInfo schemaMetadataInfoStorable = schemaRegistry.getSchemaMetadataInfo(schemaName);
            authorizationAgent.authorizeGetSerializers(securityContext, schemaMetadataInfoStorable);
            if (schemaMetadataInfoStorable != null) {
                Collection<SerDesInfo> schemaSerializers = schemaRegistry.getSerDes(schemaMetadataInfoStorable.getSchemaMetadata().getName());
                response = WSUtils.respondEntities(schemaSerializers, Response.Status.OK);
            } else {
                LOG.info("No schemas found with schemakey: [{}]", schemaName);
                response = WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, schemaName);
            }
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (Exception ex) {
            LOG.error("Encountered error while getting serializers for schemaKey [{}]", schemaName, ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Path("/files")
    @ApiOperation(value = "Upload the given file and returns respective identifier.", response = String.class, tags = OPERATION_GROUP_OTHER)
    @Timed
    public Response uploadFile(@FormDataParam("file") final InputStream inputStream,
                               @FormDataParam("file") final FormDataContentDisposition contentDispositionHeader,
                               @Context SecurityContext securityContext) {
        Response response;
        try {
            LOG.info("Received contentDispositionHeader: [{}]", contentDispositionHeader);
            authorizationAgent.authorizeUploadFile(securityContext);
            String uploadedFileId = schemaRegistry.uploadFile(inputStream);
            response = WSUtils.respondEntity(uploadedFileId, Response.Status.OK);
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (Exception ex) {
            LOG.error("Encountered error while uploading file", ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;
    }

    @GET
    @Produces({"application/octet-stream", "application/json"})
    @Path("/files/download/{fileId}")
    @ApiOperation(value = "Downloads the respective for the given fileId if it exists", response = StreamingOutput.class, tags = OPERATION_GROUP_OTHER)
    @Timed
    public Response downloadFile(@ApiParam(value = "Identifier of the file to be downloaded", required = true) @PathParam("fileId") String fileId,
                                 @Context SecurityContext securityContext) {
        Response response;
        try {
            authorizationAgent.authorizeDownloadFile(securityContext);
            StreamingOutput streamOutput = WSUtils.wrapWithStreamingOutput(schemaRegistry.downloadFile(fileId));
            response = Response.ok(streamOutput).build();
            return response;
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (FileNotFoundException e) {
            LOG.error("No file found for fileId [{}]", fileId, e);
            response = WSUtils.respondEntity(fileId, Response.Status.NOT_FOUND);
        } catch (Exception ex) {
            LOG.error("Encountered error while downloading file [{}]", fileId, ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;
    }

    @POST
    @Path("/serdes")
    @ApiOperation(value = "Add a Serializer/Deserializer into the Schema Registry", response = Long.class, tags = OPERATION_GROUP_SERDE)
    @Timed
    @UnitOfWork
    public Response addSerDes(@ApiParam(value = "Serializer/Deserializer information to be registered", required = true) SerDesPair serDesPair,
                              @Context UriInfo uriInfo,
                              @Context SecurityContext securityContext) {
        return handleLeaderAction(uriInfo, () -> _addSerDesInfo(serDesPair, securityContext));
    }

    @GET
    @Path("/serdes/{id}")
    @ApiOperation(value = "Get a Serializer for the given serializer id", response = SerDesInfo.class, tags = OPERATION_GROUP_SERDE)
    @Timed
    @UnitOfWork
    public Response getSerDes(@ApiParam(value = "Serializer identifier", required = true) @PathParam("id") Long serializerId,
                              @Context SecurityContext securityContext) {
        return _getSerDesInfo(serializerId, securityContext);
    }

    private Response _addSerDesInfo(SerDesPair serDesInfo, SecurityContext securityContext) {
        Response response;
        try {
            authorizationAgent.authorizeAddSerDes(securityContext);
            Long serializerId = schemaRegistry.addSerDes(serDesInfo);
            response = WSUtils.respondEntity(serializerId, Response.Status.OK);
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (Exception ex) {
            LOG.error("Encountered error while adding serializer/deserializer  [{}]", serDesInfo, ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }

        return response;
    }

    private Response _getSerDesInfo(Long serializerId, SecurityContext securityContext) {
        Response response;
        try {
            authorizationAgent.authorizeGetSerDes(securityContext);
            SerDesInfo serializerInfo = schemaRegistry.getSerDes(serializerId);
            response = WSUtils.respondEntity(serializerInfo, Response.Status.OK);
        } catch (Exception ex) {
            LOG.error("Encountered error while getting serializer/deserializer [{}]", serializerId, ex);
            response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }
        return response;
    }

    @POST
    @Path("/schemas/{name}/mapping/{serDesId}")
    @ApiOperation(value = "Bind the given Serializer/Deserializer to the schema identified by the schema name", tags = OPERATION_GROUP_SERDE)
    @Timed
    @UnitOfWork
    public Response mapSchemaWithSerDes(@ApiParam(value = "Schema name", required = true) @PathParam("name") String schemaName,
                                        @ApiParam(value = "Serializer/deserializer identifier", required = true) @PathParam("serDesId") Long serDesId,
                                        @Context UriInfo uriInfo,
                                        @Context SecurityContext securityContext) {
        return handleLeaderAction(uriInfo, () -> {
            Response response;
            try {
                authorizationAgent.authorizeMapSchemaWithSerDes(securityContext, schemaRegistry.getSchemaMetadataInfo(schemaName));
                schemaRegistry.mapSchemaWithSerDes(schemaName, serDesId);
                response = WSUtils.respondEntity(true, Response.Status.OK);
            } catch (AuthorizationException e) {
                return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
            } catch (Exception ex) {
                response = WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
            }

            return response;
        });
    }

    @DELETE
    @Path("/schemas/{name}/versions/{version}")
    @ApiOperation(value = "Delete a schema version given its schema name and version id", tags = OPERATION_GROUP_SCHEMA)
    @UnitOfWork
    public Response deleteSchemaVersion(@ApiParam(value = "Schema name", required = true) @PathParam("name") String schemaName,
                                        @ApiParam(value = "version of the schema", required = true) @PathParam("version") Integer versionNumber,
                                        @Context UriInfo uriInfo,
                                        @Context SecurityContext securityContext) {
        SchemaVersionKey schemaVersionKey = null;
        try {
            schemaVersionKey = new SchemaVersionKey(schemaName, versionNumber);
            authorizationAgent.authorizeDeleteSchemaVersion(securityContext,
                    schemaRegistry.getSchemaMetadataInfo(schemaName),
                    schemaRegistry.getSchemaBranchesForVersion(schemaRegistry
                            .getSchemaVersionInfo(schemaVersionKey)
                            .getId()));
            schemaRegistry.deleteSchemaVersion(schemaVersionKey);
            return WSUtils.respond(Response.Status.OK);
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (SchemaNotFoundException e) {
            LOG.error("No schemaVersion found with name: [{}], version : [{}]", schemaName, versionNumber);
            return WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, schemaVersionKey.toString());
        } catch (SchemaLifecycleException e) {
            LOG.error("Failed to delete schema name: [{}], version : [{}]", schemaName, versionNumber, e);
            return WSUtils.respond(Response.Status.BAD_REQUEST, CatalogResponse.ResponseMessage.BAD_REQUEST_WITH_MESSAGE, e.getMessage());
        } catch (Exception ex) {
            LOG.error("Encountered error while deleting schemaVersion with name: [{}], version : [{}]", schemaName, versionNumber, ex);
            return WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }
    }

    @GET
    @Path("/schemas/{name}/branches")
    @ApiOperation(value = "Get list of registered schema branches",
            response = SchemaBranch.class, responseContainer = "List",
            tags = OPERATION_GROUP_OTHER)
    @Timed
    @UnitOfWork
    public Response getAllBranches(@ApiParam(value = "Details about schema name",required = true) @PathParam("name") String schemaName,
                                   @Context UriInfo uriInfo,
                                   @Context SecurityContext securityContext) {
        try {
            Collection<SchemaBranch> schemaBranches = authorizationAgent.authorizeGetAllBranches(securityContext,
                    schemaRegistry.getSchemaMetadataInfo(schemaName),
                    () -> schemaRegistry.getSchemaBranches(schemaName));
            return WSUtils.respondEntities(schemaBranches, Response.Status.OK);
        }  catch(SchemaNotFoundException e) {
            return WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND, schemaName);
        } catch (Exception ex) {
            LOG.error("Encountered error while listing schema branches", ex);
            return WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }
    }

    @POST
    @Path("/schemas/versionsById/{versionId}/branch")
    @ApiOperation(value = "Fork a new schema branch given its schema name and version id",
            response = SchemaBranch.class,
            tags = OPERATION_GROUP_SCHEMA)
    @UnitOfWork
    public Response createSchemaBranch( @ApiParam(value = "Details about schema version",required = true) @PathParam("versionId") Long schemaVersionId,
                                        @ApiParam(value = "Schema Branch Name", required = true) SchemaBranch schemaBranch,
                                        @Context SecurityContext securityContext) {
        try {
            SchemaVersionInfo schemaVersionInfo = schemaRegistry.getSchemaVersionInfo(new SchemaIdVersion(schemaVersionId));
            authorizationAgent.authorizeCreateSchemaBranch(securityContext,
                    schemaRegistry.getSchemaMetadataInfo(schemaBranch.getSchemaMetadataName()),
                    schemaRegistry.getSchemaBranchesForVersion(schemaVersionId),
                    schemaBranch.getName());
            SchemaBranch createdSchemaBranch = schemaRegistry.createSchemaBranch(schemaVersionId, schemaBranch);
            return WSUtils.respondEntity(createdSchemaBranch, Response.Status.OK) ;
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (SchemaBranchAlreadyExistsException e) {
            return WSUtils.respond(Response.Status.CONFLICT, CatalogResponse.ResponseMessage.ENTITY_CONFLICT,  schemaBranch.getName());
        } catch (SchemaNotFoundException e) {
            return WSUtils.respond(Response.Status.BAD_REQUEST, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND,  schemaVersionId.toString());
        } catch (Exception ex) {
            LOG.error("Encountered error while creating a new branch with name: [{}], version : [{}]", schemaBranch.getName(), schemaVersionId, ex);
            return WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }
    }

    @POST
    @Path("/schemas/{versionId}/merge")
    @ApiOperation(value = "Merge a schema version to master given its version id",
            response = SchemaVersionMergeResult.class,
            tags = OPERATION_GROUP_SCHEMA)
    @UnitOfWork
    public Response mergeSchemaVersion(@ApiParam(value = "Details about schema version",required = true) @PathParam("versionId") Long schemaVersionId,
                                       @QueryParam("disableCanonicalCheck") @DefaultValue("false") Boolean disableCanonicalCheck,
                                       @Context SecurityContext securityContext) {
        try {
            SchemaVersionInfo schemaVersionInfo = schemaRegistry.getSchemaVersionInfo(new SchemaIdVersion(schemaVersionId));
            authorizationAgent.authorizeMergeSchemaVersion(securityContext,
                    schemaRegistry.getSchemaMetadataInfo(schemaVersionInfo.getSchemaMetadataId()),
                    schemaRegistry.getSchemaBranchesForVersion(schemaVersionId));
            SchemaVersionMergeResult schemaVersionMergeResult = schemaRegistry.mergeSchemaVersion(schemaVersionId, disableCanonicalCheck);
            return WSUtils.respondEntity(schemaVersionMergeResult, Response.Status.OK);
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (SchemaNotFoundException e) {
            return WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND,  schemaVersionId.toString());
        } catch (IncompatibleSchemaException e) {
            return WSUtils.respond(Response.Status.BAD_REQUEST, CatalogResponse.ResponseMessage.INCOMPATIBLE_SCHEMA, e.getMessage());
        } catch (Exception ex) {
            LOG.error("Encountered error while merging a schema version to {} branch with version : [{}]", SchemaBranch.MASTER_BRANCH, schemaVersionId, ex);
            return WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }
    }

    @DELETE
    @Path("/schemas/branch/{branchId}")
    @ApiOperation(value = "Delete a branch give its name", tags = OPERATION_GROUP_SCHEMA)
    @UnitOfWork
    public Response deleteSchemaBranch(@ApiParam(value = "Schema Branch Name", required = true) @PathParam("branchId") Long schemaBranchId,
                                       @Context SecurityContext securityContext) {
        try {
            SchemaBranch sb = schemaRegistry.getSchemaBranch(schemaBranchId);
            authorizationAgent.authorizeDeleteSchemaBranch(securityContext,
                    schemaRegistry.getSchemaMetadataInfo(sb.getSchemaMetadataName()),
                    sb.getName());
            schemaRegistry.deleteSchemaBranch(schemaBranchId);
            return WSUtils.respond(Response.Status.OK);
        } catch (AuthorizationException e) {
            return WSUtils.respond(Response.Status.FORBIDDEN, CatalogResponse.ResponseMessage.ACCESS_DENIED, null);
        } catch (SchemaBranchNotFoundException e) {
            return WSUtils.respond(Response.Status.NOT_FOUND, CatalogResponse.ResponseMessage.ENTITY_NOT_FOUND,  schemaBranchId.toString());
        } catch (InvalidSchemaBranchDeletionException e) {
            return WSUtils.respond(Response.Status.BAD_REQUEST, CatalogResponse.ResponseMessage.BAD_REQUEST_WITH_MESSAGE, e.getMessage());
        } catch (Exception ex) {
            LOG.error("Encountered error while deleting a branch with name: [{}]", schemaBranchId, ex);
            return WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, ex.getMessage());
        }
    }


    // When ever SCHEMA_BRANCH or SCHEMA_VERSION is updated in one of the node in the cluster, then it will use this API to notify rest of the node in the
    // cluster to update their corresponding cache.
    // TODO: This API was introduced as a temporary solution to address HA requirements with cache synchronization. A more permanent and stable fix should be incorporated.
    @POST
    @Path("/cache/{cacheType}/invalidate")
    @UnitOfWork
    public Response invalidateCache(@ApiParam(value = "Cache Id to be invalidated", required = true) @PathParam("cacheType") SchemaRegistryCacheType cacheType, String keyString) {
        try {
            LOG.debug("Request to invalidate cache : {} with key : {} accepted", cacheType.name(), keyString);
            schemaRegistry.invalidateCache(cacheType, keyString);
            return WSUtils.respond(Response.Status.OK);
        } catch (Exception e) {
            return WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, e.getMessage());
        }
    }

    // When a new node joins registry cluster, it invokes this API of every node which are already part of the cluster.
    // The existing nodes then update their internal list of nodes part of their cluster.
    // TODO: This API was introduced as a temporary solution to address HA requirements with cache synchronization. A more permanent and stable fix should be incorporated.
    @POST
    @Path(("/notifications/node/debut"))
    public Response registerNodeDebut(String nodeUrl) {
        try {
            LOG.debug("Acknowledged another peer server : {}", nodeUrl);
            schemaRegistry.registerNodeDebut(nodeUrl);
            return WSUtils.respond(Response.Status.OK);
        } catch (Exception e) {
            return WSUtils.respond(Response.Status.INTERNAL_SERVER_ERROR, CatalogResponse.ResponseMessage.EXCEPTION, e.getMessage());
        }
    }

}
