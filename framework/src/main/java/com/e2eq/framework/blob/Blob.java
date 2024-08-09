package com.e2eq.framework.blob;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
public interface Blob {
    ;
    long getSize();
    String getContentType();


    String getPathString();

    byte[] getContent() throws IOException;
    Date getCreationDate();

    BlobMetadata getMetadata();

    InputStream getInputStream() throws IOException;
}
