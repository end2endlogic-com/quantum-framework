package com.end2endlogic.quantum.seed.s3;

import com.e2eq.framework.service.seed.SeedContext;
import com.e2eq.framework.service.seed.SeedPackDescriptor;
import com.e2eq.framework.service.seed.SeedPackManifest;
import com.e2eq.framework.service.seed.SeedSource;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SeedSource implementation backed by Amazon S3. Supports optional STS AssumeRole.
 */
public final class S3SeedSource implements SeedSource, com.e2eq.framework.service.seed.SchemeAware, AutoCloseable {

    private final String id;
    private final String bucket;
    private final String prefix; // may be "" or end with '/'
    private final String manifestFileName;

    private final Region region; // optional; if null, SDK default chain resolves
    private final String roleArn; // optional; if null, use default credentials chain
    private final String roleSessionName; // optional; default if null
    private final String externalId; // optional
    private final Duration roleDuration; // optional

    private final S3Client s3;
    private final StsClient sts; // may be null if not using role

    public S3SeedSource(String id,
                        String bucket,
                        String prefix,
                        String manifestFileName,
                        Region region,
                        String roleArn,
                        String roleSessionName,
                        String externalId,
                        Duration roleDuration) {
        this.id = Objects.requireNonNull(id, "id");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.prefix = normalizePrefix(prefix);
        this.manifestFileName = manifestFileName != null ? manifestFileName : "manifest.yaml";
        this.region = region; // may be null
        this.roleArn = roleArn; // may be null
        this.roleSessionName = (roleSessionName == null || roleSessionName.isBlank()) ? "quantum-seed" : roleSessionName;
        this.externalId = externalId;
        this.roleDuration = roleDuration != null ? roleDuration : Duration.ofMinutes(30);

        AwsCredentialsProvider baseProvider = DefaultCredentialsProvider.create();

        this.sts = (roleArn == null || roleArn.isBlank()) ? null : buildStsClient(baseProvider, region);

        AwsCredentialsProvider effectiveProvider =
            (roleArn == null || roleArn.isBlank()) ? baseProvider :
            createAssumeRoleProvider(this.sts, this.roleArn, this.roleSessionName, this.externalId, this.roleDuration);

        this.s3 = buildS3Client(effectiveProvider, region);
    }

    private static String normalizePrefix(String p) {
        if (p == null || p.isBlank()) return "";
        String x = p.startsWith("/") ? p.substring(1) : p;
        return x.endsWith("/") ? x : x + "/";
    }

    private static AwsCredentialsProvider createAssumeRoleProvider(StsClient sts,
                                                                   String roleArn,
                                                                   String sessionName,
                                                                   String externalId,
                                                                   Duration roleDuration) {
        return () -> {
            AssumeRoleRequest.Builder b = AssumeRoleRequest.builder()
                    .roleArn(roleArn)
                    .roleSessionName((sessionName == null || sessionName.isBlank()) ? "quantum-seed" : sessionName)
                    .durationSeconds((int) (roleDuration == null ? Duration.ofMinutes(30).getSeconds() : roleDuration.getSeconds()));
            if (externalId != null && !externalId.isBlank()) {
                b = b.externalId(externalId);
            }
            var resp = sts.assumeRole(b.build());
            var creds = resp.credentials();
            return AwsSessionCredentials.create(creds.accessKeyId(), creds.secretAccessKey(), creds.sessionToken());
        };
    }

    private static S3Client buildS3Client(AwsCredentialsProvider creds, Region region) {
        var builder = S3Client.builder().credentialsProvider(creds);
        if (region != null) builder = builder.region(region);
        return builder.build();
    }

    private static StsClient buildStsClient(AwsCredentialsProvider creds, Region region) {
        var builder = StsClient.builder().credentialsProvider(creds);
        if (region != null) builder = builder.region(region);
        return builder.build();
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public List<SeedPackDescriptor> loadSeedPacks(SeedContext context) throws IOException {
        List<SeedPackDescriptor> out = new ArrayList<>();
        String marker = null;
        try {
            while (true) {
                ListObjectsV2Request req = ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .continuationToken(marker)
                        .build();
                ListObjectsV2Response resp = s3.listObjectsV2(req);
                for (S3Object obj : resp.contents()) {
                    String key = obj.key();
                    if (!key.endsWith("/" + manifestFileName) && !key.equals(prefix + manifestFileName)) {
                        continue;
                    }
                    try (InputStream in = openS3(key)) {
                        String description = "s3://" + bucket + "/" + key;
                        SeedPackManifest manifest = SeedPackManifest.load(in, description);
                        Path pseudoPath = manifestPathForKey(key);
                        out.add(new SeedPackDescriptor(this, manifest, pseudoPath));
                    } catch (IOException e) {
                        throw new IOException("Failed to read seed manifest at s3://" + bucket + "/" + key, e);
                    }
                }
                if (resp.isTruncated()) {
                    marker = resp.nextContinuationToken();
                } else {
                    break;
                }
            }
            return out;
        } catch (RuntimeException re) {
            throw new IOException("S3 listing failed for bucket=" + bucket + ", prefix=" + prefix, re);
        }
    }

    @Override
    public InputStream openDataset(SeedPackDescriptor descriptor, String relativePath) throws IOException {
        Path manifestPath = descriptor.getManifestPath();
        String manifestKey = keyForManifestPath(manifestPath);
        String base = manifestKey.substring(0, manifestKey.length() - manifestFileName.length());
        String datasetKey = normalizeS3Key(base + relativePath);
        try {
            return openS3(datasetKey);
        } catch (RuntimeException re) {
            throw new IOException("Failed to open dataset s3://" + bucket + "/" + datasetKey, re);
        }
    }

    private ResponseInputStream<?> openS3(String key) {
        GetObjectRequest req = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return s3.getObject(req);
    }

    private Path manifestPathForKey(String key) {
        // Provide a pseudo path for compatibility with FileSeedSource path logic
        return Paths.get("/s3", bucket, key);
    }

    private String keyForManifestPath(Path manifestPath) {
        String p = manifestPath.toString().replace('\\', '/');
        int idx = p.indexOf("/" + bucket + "/");
        if (idx >= 0) {
            return p.substring(idx + bucket.length() + 2);
        }
        return prefix + manifestFileName;
    }

    private static String normalizeS3Key(String k) {
        return k.replace("//", "/");
    }

    @Override
    public void close() {
        if (s3 != null) s3.close();
        if (sts != null) sts.close();
    }

    // SchemeAware implementation
    @Override
    public boolean supportsScheme(String scheme) {
        return "s3".equalsIgnoreCase(scheme);
    }

    @Override
    public InputStream openUri(java.net.URI uri, com.e2eq.framework.service.seed.SeedPackDescriptor context) throws IOException {
        if (!supportsScheme(uri.getScheme())) {
            throw new IOException("Unsupported scheme for S3SeedSource: " + uri.getScheme());
        }
        String b = uri.getHost();
        String key = uri.getPath();
        if (key.startsWith("/")) key = key.substring(1);
        try {
            GetObjectRequest req = GetObjectRequest.builder().bucket(b).key(key).build();
            return s3.getObject(req);
        } catch (RuntimeException e) {
            throw new IOException("Failed to open S3 object " + uri, e);
        }
    }

    @Override
    public com.e2eq.framework.service.seed.SeedPackDescriptor loadManifestByUri(java.net.URI uri, com.e2eq.framework.service.seed.SeedContext ctx) throws IOException {
        if (!supportsScheme(uri.getScheme())) {
            throw new IOException("Unsupported scheme for S3SeedSource: " + uri.getScheme());
        }
        String b = uri.getHost();
        String key = uri.getPath();
        if (key.startsWith("/")) key = key.substring(1);
        try (InputStream in = s3.getObject(GetObjectRequest.builder().bucket(b).key(key).build())) {
            String description = uri.toString();
            com.e2eq.framework.service.seed.SeedPackManifest manifest = com.e2eq.framework.service.seed.SeedPackManifest.load(in, description);
            java.nio.file.Path pseudoPath = java.nio.file.Paths.get("/s3", b, key);
            return new com.e2eq.framework.service.seed.SeedPackDescriptor(this, manifest, pseudoPath);
        } catch (RuntimeException e) {
            throw new IOException("Failed to load manifest from " + uri, e);
        }
    }
}
