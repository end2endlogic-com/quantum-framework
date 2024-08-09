package com.e2eq.framework.api.cloud.aws;

import com.e2eq.framework.config.B2BIntegratorConfig;
import com.e2eq.framework.cloud.aws.AwsClient;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@QuarkusTest
public class TestPureAwsClient {
    @Inject
    AwsClient awsClient;
    @Inject
    B2BIntegratorConfig config;


    @Test
    public void testAwsClient() {

        // Set up developer role.
        AwsSessionCredentials credentials = awsClient.assumeRole(config.awsRoleArn(), "testSession-001");

        // List things in the bucket

        if (credentials != null )
            Log.info(credentials.accessKeyId());
        else
            throw new RuntimeException("Failed");

        awsClient.listBuckets(credentials);

        Map<String, String> metadata = new HashMap<>();
        metadata.put("author","Mary Doe");
        metadata.put("version","1.0.0.0");

        URL url = awsClient.generateSignedURL(credentials,"b2bintegrator-mailboxdata-000000", "testfile", metadata, "text/plain");

        Log.info(url.toString());
    }

    @Test
    public void testAwsClientUpload() throws IOException {
        AwsSessionCredentials credentials = awsClient.assumeRole(config.awsRoleArn(), "testSession-001");

        try (InputStream in  = this.getClass().getClassLoader().getResourceAsStream("testData/test.txt")) {
            awsClient.createFile(in, in.available(),"b2bintegrator-mailboxdata-000000", "testfile1", "text/plain");
        }

        List<S3Object> objects = awsClient.listBucketContents(credentials, "b2bintegrator-mailboxdata-000000", null);
        objects.forEach(s3Object -> Log.info(s3Object.key()));
    }

    @Test
    public void testAwsClientUpDownDelete() throws IOException {
        // Set up developer role.
        AwsSessionCredentials credentials = awsClient.assumeRole(config.awsRoleArn(), "testSession-001");
        awsClient.createFile("TestContent 123", "b2bintegrator-mailboxdata-000000", "testfile1", "text/plain");
        List<S3Object> objects = awsClient.listBucketContents(credentials, "b2bintegrator-mailboxdata-000000", null);
        objects.forEach(s3Object -> Log.info(s3Object.key()));

        InputStream in  = awsClient.getBlob(credentials, "b2bintegrator-mailboxdata-000000", "testfile1");
        // read in into string buffer
        String content = new String(in.readAllBytes());
        in.close();
        Log.info("--- Content ---");
        Log.info(content);

        awsClient.deleteBlob(credentials, "b2bintegrator-mailboxdata-000000","testfile1");
    }
}
