package com.e2eq.framework.blob;

public class BlobStoreException extends Exception {
    private static final long serialVersionUID = 1L;
    public BlobStoreException() {
    }

    public BlobStoreException(String message) {
        super(message);
    }

    public BlobStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public BlobStoreException(Throwable cause) {
        super(cause);
    }

    public BlobStoreException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
