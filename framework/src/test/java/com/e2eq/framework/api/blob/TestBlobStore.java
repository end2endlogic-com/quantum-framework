package com.e2eq.framework.api.blob;

import com.e2eq.framework.blob.*;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;

@QuarkusTest
public class TestBlobStore {
    @Inject
    S3BlobStore blobStore;
    @Test
    public void testListBlobs() throws BlobStoreException {
        if (!blobStore.isEnabled()) {
            Log.warn("Skipping test as S3 blob store is not enabled. Please set up AWS credentials in your config");
            return;
        }

        List<Blob> blobs = blobStore.getBlobList();
        blobs.forEach( blob -> {
            Log.info(blob.getPathString());
        });
    }

    @Test
    void testUpload() throws BlobStoreException, IOException {
        if (!blobStore.isEnabled()) {
            Log.warn("Skipping test as S3 blob store is not enabled. Please set up AWS credentials in your config");
            return;
        }

        String fileContent;
       try (InputStream in  = this.getClass().getClassLoader().getResourceAsStream("testData/test.txt")) {
           BlobMetadata metadata = new S3Metadata();
           metadata.setContentType("text/plain");
           metadata.setFileName("test.txt");
           metadata.setOwnerId("testOwner");

           blobStore.createBlob(in, in.available(), blobStore.determineBucket(), "mailbox/test1", "text/plain",metadata);
       }
    }

    @Test
    public void testSignedURL() throws BlobStoreException, IOException {
        if (!blobStore.isEnabled()) {
            Log.warn("Skipping test as S3 blob store is not enabled. Please set up AWS credentials in your config");
            return;
        }

        String fileContent;
       try (InputStream in  = this.getClass().getClassLoader().getResourceAsStream("testData/test.txt")) {
           byte[] buffer = new byte[in.available()];
           in.read(buffer);
           fileContent = new String(buffer);
       }
       S3Metadata metadata = new S3Metadata();
       metadata.setContentType("text/plain");
       metadata.setFileName("test.txt");
       metadata.setContentLength(fileContent.length());

       URL url = blobStore.generateSignedURL("test.txt",  metadata);

       Log.info("URL:" + url.toString());
    }
}
