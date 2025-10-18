package com.end2endlogic.quantum.seed.s3;

import software.amazon.awssdk.regions.Region;

import java.time.Duration;

public final class S3SeedSourceBuilder {
    private String id;
    private String bucket;
    private String prefix = "";
    private String manifestFileName = "manifest.yaml";
    private Region region;
    private String roleArn;
    private String roleSessionName = "quantum-seed";
    private String externalId;
    private Duration roleDuration = Duration.ofMinutes(30);

    public S3SeedSourceBuilder id(String id) { this.id = id; return this; }
    public S3SeedSourceBuilder bucket(String bucket) { this.bucket = bucket; return this; }
    public S3SeedSourceBuilder prefix(String prefix) { this.prefix = prefix; return this; }
    public S3SeedSourceBuilder manifestFileName(String name) { this.manifestFileName = name; return this; }
    public S3SeedSourceBuilder region(Region region) { this.region = region; return this; }
    public S3SeedSourceBuilder roleArn(String roleArn) { this.roleArn = roleArn; return this; }
    public S3SeedSourceBuilder roleSessionName(String roleSessionName) { this.roleSessionName = roleSessionName; return this; }
    public S3SeedSourceBuilder externalId(String externalId) { this.externalId = externalId; return this; }
    public S3SeedSourceBuilder roleDuration(Duration d) { this.roleDuration = d; return this; }

    public S3SeedSource build() {
        return new S3SeedSource(id, bucket, prefix, manifestFileName, region, roleArn, roleSessionName, externalId, roleDuration);
    }
}
