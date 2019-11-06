package com.hortonworks.registries.schemaregistry.authorization;

import com.hortonworks.registries.schemaregistry.*;
import com.hortonworks.registries.schemaregistry.errors.SchemaBranchNotFoundException;
import com.hortonworks.registries.schemaregistry.errors.SchemaNotFoundException;
import org.apache.hadoop.security.authorize.AuthorizationException;

import javax.ws.rs.core.SecurityContext;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

public interface AuthorizationAgent {

    Collection<AggregatedSchemaMetadataInfo> listAggregatedSchemas
            (SecurityContext sc,
             SupplierWithSchemaNotFoundException<Collection<AggregatedSchemaMetadataInfo>> func)
            throws SchemaNotFoundException;

    AggregatedSchemaMetadataInfo getAggregatedSchemaInfo(SecurityContext sc,
                                                         AggregatedSchemaMetadataInfo aggregatedSchemaMetadataInfo)
            throws AuthorizationException;

    Collection<SchemaMetadataInfo> findSchemas(SecurityContext sc,
                                               Supplier<Collection<SchemaMetadataInfo>> func);

    List<AggregatedSchemaMetadataInfo> findAggregatedSchemas(SecurityContext sc,
                                                             List<AggregatedSchemaMetadataInfo> asmi);

    Collection<SchemaVersionKey> findSchemasByFieldsWithAuthorization
            (SecurityContext sc,
             Function<String, SchemaMetadataInfo> getSchemaMetadataFunc,
             FunctionWithSchemaNotFoundException<SchemaVersionKey, SchemaVersionInfo> getVersionInfoFunc,
             SupplierWithSchemaNotFoundException<Collection<SchemaVersionKey>> func)
            throws SchemaNotFoundException;

    void  addSchemaInfoWithAuthorization(SecurityContext sc, SchemaMetadata schemaMetadata)
            throws AuthorizationException;

    void updateSchemaInfoWithAuthorization(SecurityContext sc, SchemaMetadata schemaMetadata)
            throws AuthorizationException;

    SchemaMetadataInfo getSchemaInfoWithAuthorization
            (SecurityContext sc,
             SchemaMetadataInfo schemaMetadataInfo)
            throws AuthorizationException;

    Collection<SchemaVersionInfo> getAllSchemaVersionsWithAuthorization
            (SecurityContext sc,
             SchemaMetadataInfo schemaMetadataInfo,
             String schemaBranchName,
             SupplierWithSchemaNotFoundException<Collection<SchemaVersionInfo>> func)
            throws SchemaNotFoundException, AuthorizationException;

    void deleteSchemaMetadataWithAuthorization
            (SecurityContext sc,
             SchemaMetadataInfo schemaMetadataInfo)
            throws AuthorizationException;


    void addSchemaVersion(SecurityContext securityContext,
                          SchemaMetadataInfo schemaMetadataInfo,
                          String schemaBranch)
            throws AuthorizationException;

    void getLatestSchemaVersion(SecurityContext securityContext,
                                SchemaMetadataInfo schemaMetadataInfo,
                                String schemaBranch)
        throws AuthorizationException;

    void authorizeGetSchemaVersion(SecurityContext securityContext,
                                   SchemaMetadataInfo schemaMetadataInfo,
                                   Collection<SchemaBranch> branches)
            throws AuthorizationException;


    void authorizeVerisonStateOperation(SecurityContext securityContext,
                                        SchemaMetadataInfo schemaMetadataInfo,
                                        Collection<SchemaBranch> branches)
            throws AuthorizationException;

    void checkCompatibilityWithSchema(SecurityContext securityContext,
                                 SchemaMetadataInfo schemaMetadataInfo,
                                 String schemaBranch)
            throws AuthorizationException;

    void getSerializers(SecurityContext securityContext,
                        SchemaMetadataInfo schemaMetadataInfo) throws AuthorizationException;

    void uploadFile (SecurityContext securityContext)  throws AuthorizationException;

    void downloadFile (SecurityContext securityContext)  throws AuthorizationException;

    void addSerDes (SecurityContext securityContext)  throws AuthorizationException;

    void getSerDes (SecurityContext securityContext)  throws AuthorizationException;

    void mapSchemaWithSerDes(SecurityContext securityContext,
                        SchemaMetadataInfo schemaMetadataInfo) throws AuthorizationException;

    void deleteSchemaVersion(SecurityContext securityContext,
                             SchemaMetadataInfo schemaMetadataInfo,
                             Collection<SchemaBranch> branches)
            throws AuthorizationException;

    Collection<SchemaBranch> getAllBranches(SecurityContext securityContext,
                                            SchemaMetadataInfo schemaMetadataInfo,
                                            SupplierWithSchemaNotFoundException<Collection<SchemaBranch>> func)
            throws SchemaNotFoundException;

    void createSchemaBranch(SecurityContext securityContext,
                            SchemaMetadataInfo schemaMetadataInfo,
                            Collection<SchemaBranch> branches,
                            String branchTocreate)
            throws AuthorizationException;

    void mergeSchemaVersion(SecurityContext securityContext,
                            SchemaMetadataInfo schemaMetadataInfo,
                            Collection<SchemaBranch> schemaBranches)
            throws AuthorizationException;

    void deleteSchemaBranch(SecurityContext securityContext,
                            SchemaMetadataInfo schemaMetadataInfo,
                            String schemaBranch)
            throws AuthorizationException;

    ///////////////// ConfluentCompatible APIs //////////////////////////////
    Stream<SchemaMetadataInfo> getSubjects(SecurityContext securityContext, Stream<SchemaMetadataInfo> stream);

    Stream<SchemaVersionInfo> getAllVersions(SecurityContext securityContext, Stream<SchemaVersionInfo> vStream,
                                             FunctionWithSchemaNotFoundException<Long, SchemaMetadataInfo> getMetadataFunc,
                                             FunctionWithBranchSchemaNotFoundException<Long, Collection<SchemaBranch>> getBranches);

    @FunctionalInterface
    interface SupplierWithSchemaNotFoundException<T> {
        T get() throws SchemaNotFoundException;
    }

    @FunctionalInterface
    interface FunctionWithSchemaNotFoundException<T, R> {
        R apply(T arg) throws SchemaNotFoundException;
    }

    @FunctionalInterface
    interface FunctionWithBranchSchemaNotFoundException<T, R> {
        R apply(T arg) throws SchemaBranchNotFoundException;
    }
}
