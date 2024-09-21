package com.e2eq.framework.cloud.aws;

import com.e2eq.framework.config.AWSConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jetbrains.annotations.NotNull;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.StsException;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class AwsClient {
    @Inject
    AWSConfig config;


    public void createFile(String content, String bucket, String path, String contentType)  {
        if (!config.awsRoleArn().isPresent()) {
            throw new IllegalStateException("AWS Role ARN not configured");
        }

        AwsSessionCredentials credentials =  assumeRole(config.awsRoleArn().get(), "testSessionName");
        try(S3Client s3 = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of(config.region().get()))
                .build()) {
            RequestBody body = RequestBody.fromString(content);
            PutObjectRequest objectRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(path)
                    .contentType(contentType)
                    .build();
            s3.putObject(objectRequest, body);
        }
    }

    public void createFile(InputStream inputStream, long length, String bucket, String path, String contentType)  {

        if (!config.awsRoleArn().isPresent() || !config.region().isPresent()) {
            throw new IllegalStateException("AWS Role ARN not configured");
        }
        AwsSessionCredentials credentials =  assumeRole(config.awsRoleArn().get(), "testSessionName");
        try(S3Client s3 = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of(config.region().get()))
                .build()) {
            RequestBody body = RequestBody.fromInputStream(inputStream, length);
            PutObjectRequest objectRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(path)
                    .contentType(contentType)
                    .build();
            s3.putObject(objectRequest, body);
        }
    }

    public AwsSessionCredentials assumeRole(String roleArn, String roleSessionName) throws StsException {
        // The IAM role represented by the roleArn parameter can be assumed by any user in the same account
        // and has read-only permissions when it accesses Amazon S3.

        // The default credentials provider chain will find the single sign-on settings in the [default] profile.
        if (!config.awsRoleArn().isPresent() ||!config.region().isPresent()) {
            throw new IllegalStateException("AWS Role ARN or region not configured");
        }

        StsClient stsClient = StsClient.builder()
                .region(Region.of(config.region().get()))
                .build();

        AwsSessionCredentials creds = null;

        AssumeRoleRequest roleRequest = AssumeRoleRequest.builder()
                .roleArn(roleArn)
                .roleSessionName(roleSessionName)
                .build();

        AssumeRoleResponse roleResponse = stsClient.assumeRole(roleRequest);
        Credentials tempRoleCredentials = roleResponse.credentials();
        // The following temporary credential items are used when Amazon S3 is called
        String key = tempRoleCredentials.accessKeyId();
        String secKey = tempRoleCredentials.secretAccessKey();
        String secToken = tempRoleCredentials.sessionToken();
        creds = AwsSessionCredentials.create(key, secKey, secToken);

        return creds;
    }

    public List<Bucket> listBuckets(AwsSessionCredentials creds) {
        // List all buckets in the account using the temporary credentials retrieved by invoking assumeRole.
        if (!config.region().isPresent()) {
            throw new IllegalStateException("AWS Region not configured");
        }


        try(S3Client s3 = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .region(Region.of(config.region().get()))
                .build()) {

            List<Bucket> buckets = s3.listBuckets().buckets();
            for (Bucket bucket : buckets) {
                System.out.println("bucket name: " + bucket.name());
            }
            return buckets;
        }
    }

    public List<S3Object> listBucketContents(@NotNull AwsSessionCredentials creds, @NotNull String bucketName, String regexPattern) {

        if (!config.region().isPresent()) {
            throw new IllegalStateException("AWS Region not configured");
        }

        try (S3Client s3 = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .region(Region.of(config.region().get()))
                .build()) {

            ListObjectsV2Request listObjectsRequest = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(regexPattern)
                    .build();

            ListObjectsV2Response response = s3.listObjectsV2(listObjectsRequest);
            List<S3Object> rc;
            if (regexPattern != null && !regexPattern.isEmpty()) {
                rc = new ArrayList<>();
                for (S3Object object : response.contents()) {
                    if (object.key().matches(regexPattern)) {
                        rc.add(object);
                    }
                }
            } else {
                rc = response.contents();
            }
            return rc;
        }
    }

    public InputStream getBlob(AwsSessionCredentials creds, String bucketName, String objectKey) {
        if (!config.region().isPresent()) {
            throw new IllegalStateException("AWS Region not configured");
        }

        S3Client s3 = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .region(Region.of(config.region().get()))
                .build();

            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();
            ResponseInputStream<GetObjectResponse> s3Object = s3.getObject(getObjectRequest);

            return s3Object;
    }

    public URL generateSignedURL(AwsSessionCredentials credentials, String bucketName, String objectKey, Map<String, String> metadata, String contentType ) {

        if (!config.region().isPresent()) {
            throw new IllegalStateException("AWS Region not configured");
        }

        S3Presigner s3Presigner = S3Presigner.builder()
                .region(Region.of(config.region().get())) // Change this to your desired AWS region
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .build();

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build();

      /*  Map<String, String> metadata = new HashMap<>();
        metadata.put("author","Mary Doe");
        metadata.put("version","1.0.0.0"); */

        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .contentType(contentType)
                .metadata(metadata)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .putObjectRequest(objectRequest)
                .build();

        PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest); // Change this to your desired expiration time

        return presignedRequest.url();
    }

    public void deleteBlob(AwsSessionCredentials credentials, String bucketName, String objectKey) {
        if (!config.region().isPresent()) {
            throw new IllegalStateException("AWS Region not configured");
        }

        try (S3Client s3 = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of(config.region().get()))
                .build()) {
      /*  try (S3Client s3 = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of(config.region().get()))
                .build()) { */

            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();

            s3.deleteObject(deleteObjectRequest);
        }
    }
}
