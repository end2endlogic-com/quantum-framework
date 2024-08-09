package com.e2eq.framework.blob;

import java.io.InputStream;
import java.net.URL;
import java.util.List;

public interface BlobStore {
    BlobMetadata createMetadata();
    void validateMetadata(BlobMetadata metadata) throws IllegalArgumentException;

    public void createBlob(String content, String bucket, String path, String contentType,
                           BlobMetadata metadata) throws BlobStoreException;

    public void createBlob(InputStream inputStream, long length, String bucket, String path, String contentType,
                           BlobMetadata metadata) throws BlobStoreException;

    InputStream getBlob(String bucket, String fullPath, BlobMetadata metadata) throws BlobStoreException;

    List<Blob> getBlobList() throws BlobStoreException;

    List<Blob> getBlobList(String regularExpression, String session) throws BlobStoreException;

    URL generateSignedURL(String fileName, BlobMetadata metadata) throws BlobStoreException;
}
