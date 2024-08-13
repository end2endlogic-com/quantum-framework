package com.e2eq.framework.blob;

import com.e2eq.framework.model.general.GetUploadSignedURLRequest;
import com.e2eq.framework.cloud.aws.AwsClient;
import com.e2eq.framework.config.AWSConfig;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class S3BlobStore implements BlobStore {
    @Inject
    AWSConfig awsConfig;
    @Inject
    AwsClient awsClient;


    public boolean isEnabled() {
        if (awsConfig.awsRoleArn().isPresent() && awsConfig.region().isPresent())
            return true;
        else
            return false;
    }
    public String determineBucket() {
        return "b2bintegrator-mailboxdata-000000";
    }

    @Override
    public BlobMetadata createMetadata() {
       return new S3Metadata();
    }

    @Override
    public void validateMetadata(BlobMetadata metadata) throws IllegalArgumentException {

    }


    @Override
    public void createBlob(String content, String bucket, String path, String contentType, BlobMetadata metadata) throws BlobStoreException {
        awsClient.createFile(content, bucket, path, contentType);
    }

    @Override
    public void createBlob(InputStream inputStream, long length, String bucket, String path, String contentType, BlobMetadata metadata) throws BlobStoreException {
        awsClient.createFile(inputStream, length, bucket, path, contentType);
    }

    @Override
    public InputStream getBlob(String bucket, String fileFullPath,  BlobMetadata metadata) throws BlobStoreException {

        return null;
    }

    @Override
    public List<Blob> getBlobList() throws BlobStoreException {
       return getBlobList(null, "testSession-001");
    }

    @Override
    public List<Blob> getBlobList(String regexPattern, String sessionName) throws BlobStoreException {

        if (!awsConfig.region().isPresent() ||!awsConfig.awsRoleArn().isPresent())
        {
            throw new IllegalStateException("AWS credentials not provided.");
        }
        AwsSessionCredentials credentials = awsClient.assumeRole(awsConfig.awsRoleArn().get(), sessionName);

        try(S3Client s3 = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of(awsConfig.region().get()))
                .build()) {
            // List out the contents of a specific bucket.
            List<S3Object> contents = awsClient.listBucketContents(credentials, determineBucket(), regexPattern);
            List<Blob> rc = new ArrayList<>();
            for (S3Object content : contents) {
                rc.add(new S3Blob(content, s3, determineBucket()));
            }

            return rc;
        }

    }


    @Override
    public URL generateSignedURL(String fileName,  BlobMetadata metadata) throws BlobStoreException {
        GetUploadSignedURLRequest request = new GetUploadSignedURLRequest();
        request.setFileName(fileName);
        request.setContentType( MediaType.valueOf(metadata.getContentType()));

        if (request.validate()) {
            if (!awsConfig.region().isPresent() ||!awsConfig.awsRoleArn().isPresent()) {
                throw new IllegalStateException("AWS credentials not provided.");
            }

            // Set up developer role.
            // AwsSessionCredentials credentials = AwsClient.assumeRole("arn:aws:iam::103417400819:role/b2bintegrator-mailbox-access-000000", "testSession-001");
            AwsSessionCredentials credentials = awsClient.assumeRole(awsConfig.awsRoleArn().get(), "testSession-001");

            // List things in the bucket
            if (credentials != null)
                Log.info(credentials.accessKeyId());
            else
                throw new RuntimeException("Failed");

            awsClient.listBuckets(credentials);
            Map<String, String> requestMetadata = request.getMetaData();
            URL url = awsClient.generateSignedURL(credentials, determineBucket(), request.getFileName(), requestMetadata, request.getContentType().toString());
            return url;


        } else
            throw new BlobStoreException("Failed to validate request");
    }

}
