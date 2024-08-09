package com.e2eq.framework.api.cloud.aws;


import com.e2eq.framework.config.B2BIntegratorConfig;
import com.e2eq.framework.model.general.GetUploadSignedURLRequest;
import com.e2eq.framework.cloud.aws.AwsClient;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URL;
import java.util.List;
import java.util.Map;

@QuarkusTest
public class TestS3 {
    @Inject
    B2BIntegratorConfig b2bIntegratorConfig;

    @Inject
    AwsClient awsClient;

    @Test
    public void testConfig() {
        Log.info("RoleURN:" + b2bIntegratorConfig.awsRoleArn());
    }

    @Test
    public void testGenerateSignedURL() {
        GetUploadSignedURLRequest request = new GetUploadSignedURLRequest();
        request.setFileName("testFileName");

        if (request.validate()) {
            // Set up developer role.
            // AwsSessionCredentials credentials = AwsClient.assumeRole("arn:aws:iam::103417400819:role/b2bintegrator-mailbox-access-000000", "testSession-001");
            AwsSessionCredentials credentials = awsClient.assumeRole(b2bIntegratorConfig.awsRoleArn(), "testSession-001");

            // List things in the bucket

            if (credentials != null)
                Log.info(credentials.accessKeyId());
            else
                throw new RuntimeException("Failed");

            awsClient.listBuckets(credentials);

            Map<String, String> metadata = request.getMetaData();

            URL url = awsClient.generateSignedURL(credentials, "b2bintegrator-mailboxdata-000000", request.getFileName(), metadata, request.getContentType().toString());
            Log.info("Generated Signed URL:" + url.toString());

        }
    }

    @Test
    public void testListBuckets() {
        AwsSessionCredentials creds = awsClient.assumeRole(b2bIntegratorConfig.awsRoleArn(), "testSession-001");
       List<Bucket> buckets = awsClient.listBuckets(creds);
        for (Bucket b:buckets) {
            Log.info(b.name());

        }
    }

    @Test
    public void testListBucketContents() {
        AwsSessionCredentials creds = awsClient.assumeRole(b2bIntegratorConfig.awsRoleArn(), "testSession-001");
        // List out the contents of a specific bucket.
        List<S3Object> contends = awsClient.listBucketContents(creds, "b2bintegrator-mailboxdata-000000", null);
        for (S3Object c:contends) {
            Log.info(c.key());
        }



    }

}
