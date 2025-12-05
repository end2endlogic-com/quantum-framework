package com.e2eq.framework.model.persistent.general;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.annotations.Indexed;
import org.bson.types.ObjectId;
import java.time.Instant;

@Entity("sys_seed_history")
public class SeedHistory {
    @Id
    private ObjectId id;

    @Indexed
    private String seedPackName;

    @Indexed
    private String datasetCollection; // e.g., "counter"

    private String fileChecksum;      // SHA-256 of the JSONL file
    private Instant lastApplied;
    private int recordCount;

    // Getters, Setters, Constructors omitted for brevity
    public SeedHistory() {}
    public SeedHistory(String pack, String collection, String checksum, int count) {
        this.seedPackName = pack;
        this.datasetCollection = collection;
        this.fileChecksum = checksum;
        this.recordCount = count;
        this.lastApplied = Instant.now();
    }

    // Standard getters...
    public String getFileChecksum() { return fileChecksum; }
}
