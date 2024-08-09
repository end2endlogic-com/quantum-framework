package com.e2eq.framework.model.general;

import jakarta.ws.rs.core.MediaType;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public @Data class GetUploadSignedURLRequest {
    protected String fileName;
    protected Map<String, String> metaData = new HashMap<String,String>();
    protected MediaType contentType;


    public boolean validate() {
        return Objects.nonNull(fileName) &&
        Objects.nonNull(contentType);
    }
}
