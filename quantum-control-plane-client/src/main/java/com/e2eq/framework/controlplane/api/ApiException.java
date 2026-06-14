package com.e2eq.framework.controlplane.api;

public class ApiException extends RuntimeException {
    public ApiException(String message) { super(message); }
    public ApiException(String message, Throwable cause) { super(message, cause); }
}
