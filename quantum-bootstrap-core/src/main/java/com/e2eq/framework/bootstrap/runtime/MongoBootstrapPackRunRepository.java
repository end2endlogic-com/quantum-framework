package com.e2eq.framework.bootstrap.runtime;

import com.e2eq.framework.bootstrap.model.BootstrapPackRun;
import com.e2eq.framework.bootstrap.spi.BootstrapPackRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.List;
import java.util.Optional;

/** Durable history through the shared governed repository, in an application-owned metadata realm. */
@ApplicationScoped
public class MongoBootstrapPackRunRepository implements BootstrapPackRunRepository {
    @Inject BootstrapPackRunRecordRepo records;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="quantum.bootstrap.run-store-realm") Optional<String> storeRealm;

    private String realm() {
        return storeRealm.filter(value -> !value.isBlank()).orElseThrow(() ->
            new IllegalStateException("BOOTSTRAP_RUN_STORE_REQUIRED: configure an application-owned quantum.bootstrap.run-store-realm"));
    }
    @Override public boolean isDurable(){return true;}
    @Override public Creation createIfAbsent(BootstrapPackRun run) {
        String realm=realm();
        var datastore=records.getMorphiaDataStoreWrapper().getDataStore(realm);
        if (!"majority".equals(datastore.getDatabase().getWriteConcern().getWObject()))
            throw new IllegalStateException("BOOTSTRAP_DURABLE_WRITE_CONCERN_REQUIRED: configure Mongo majority acknowledgement");
        datastore.ensureIndexes(BootstrapPackRunRecord.class);
        var record=new BootstrapPackRunRecord();
        record.setRefName(run.packRunRef()); record.setRunJson(encode(run));
        try { records.save(realm,record); return new Creation(run,true); }
        catch(MongoWriteException e) {
            if(e.getError().getCategory()!=ErrorCategory.DUPLICATE_KEY) throw e;
            return new Creation(find(run.packRunRef()).orElseThrow(() -> e),false);
        }
    }
    @Override public BootstrapPackRun compareAndSet(BootstrapPackRun expected,BootstrapPackRun updated) {
        var record=records.findByRefName(expected.packRunRef(),realm()).orElseThrow(() -> new IllegalStateException("BOOTSTRAP_RUN_NOT_FOUND"));
        try {
            if (!mapper.readTree(record.getRunJson()).equals(mapper.readTree(encode(expected))))
                throw new IllegalStateException("BOOTSTRAP_CHECKPOINT_CONFLICT");
        } catch(java.io.IOException e) { throw new IllegalStateException("BOOTSTRAP_RUN_STORAGE_INVALID",e); }
        record.setRunJson(encode(updated));
        // The governed save uses BaseModel @Version, so simultaneous claims cannot both commit.
        try { records.save(realm(),record); }
        catch (dev.morphia.VersionMismatchException conflict) { throw new IllegalStateException("BOOTSTRAP_CHECKPOINT_CONFLICT",conflict); }
        return updated;
    }
    @Override public BootstrapPackRun save(BootstrapPackRun run) {
        String realm=realm();
        var record=records.findByRefName(run.packRunRef(),realm).orElseThrow(() ->
            new IllegalStateException("BOOTSTRAP_RUN_NOT_CREATED_OR_NOT_VISIBLE"));
        // Preserve id, version, data domain, audit and any persisted metadata.
        record.setRunJson(encode(run));
        records.save(realm,record);
        return run;
    }
    @Override public Optional<BootstrapPackRun> find(String key) {
        return records.findByRefName(key,realm()).map(this::decode);
    }
    @Override public List<BootstrapPackRun> list() {
        return records.getAllList(realm()).stream().map(this::decode).toList();
    }
    private String encode(BootstrapPackRun run) {
        try { return mapper.writeValueAsString(run); }
        catch(Exception e){throw new IllegalStateException("BOOTSTRAP_RUN_ENCODING_FAILED",e);}
    }
    private BootstrapPackRun decode(BootstrapPackRunRecord record) {
        try { var run=mapper.readValue(record.getRunJson(),BootstrapPackRun.class);
            if(!record.getRefName().equals(run.packRunRef()))throw new IllegalStateException("BOOTSTRAP_RUN_ID_MISMATCH");
            return run;
        } catch(Exception e){throw new IllegalStateException("BOOTSTRAP_RUN_STORAGE_INVALID",e);}
    }
}
