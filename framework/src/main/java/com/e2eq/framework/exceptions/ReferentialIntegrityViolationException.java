package com.e2eq.framework.exceptions;

public class ReferentialIntegrityViolationException extends Exception {
    protected String referringClass;
    protected String referringId;
    protected String referringIdType;

    public ReferentialIntegrityViolationException(String message) {
        super(message);
    }

    public ReferentialIntegrityViolationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ReferentialIntegrityViolationException(String message, String referringClass, String referringId, String referringIdType) {
        super(message);
        this.referringClass = referringClass;
        this.referringId = referringId;
        this.referringIdType = referringIdType;
    }
    public ReferentialIntegrityViolationException(String message, String referringClass, String referringId, String referringIdType, Throwable cause) {
        super(message, cause);
        this.referringClass = referringClass;
        this.referringId = referringId;
        this.referringIdType = referringIdType;
    }


    public String getReferringClass() {
        return referringClass;
    }

    public void setReferringClass(String referringClass) {
        this.referringClass = referringClass;
    }

    public String getReferringId() {
        return referringId;
    }

    public void setReferringId(String referringId) {
        this.referringId = referringId;
    }

    public String getReferringIdType() {
        return referringIdType;
    }

    public void setReferringIdType(String referringIdType) {
        this.referringIdType = referringIdType;
    }
}