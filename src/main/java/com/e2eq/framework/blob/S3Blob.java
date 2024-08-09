package com.e2eq.framework.blob;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;

public class S3Blob implements Blob {

    protected S3Object s3Object;
    protected S3Client s3Client;
    protected String bucketName;

    public S3Blob(S3Object s3Object, S3Client s3Client, String bucketName) {
        this.s3Object = s3Object;
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    @Override
    public long getSize() {
        return s3Object.size();
    }

    @Override
    public String getContentType()  {
        HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Object.key())
                .build();
        HeadObjectResponse response = s3Client.headObject(request);

        return response.contentType();
    }

    @Override
    public String getPathString() {
        return s3Object.key();
    }

    @Override
    public byte[] getContent() throws IOException {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Object.key())
                .build();
        return s3Client.getObject(request).readAllBytes();
    }

    @Override
    public Date getCreationDate() {
        return null;
    }

    @Override
    public BlobMetadata getMetadata() {
        HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Object.key())
                .build();
        HeadObjectResponse response = s3Client.headObject(request);
        return new S3Metadata(response.metadata());
    }

    @Override
    public InputStream getInputStream() throws IOException {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Object.key())
                .build();
        return s3Client.getObject(request);
    }
}
