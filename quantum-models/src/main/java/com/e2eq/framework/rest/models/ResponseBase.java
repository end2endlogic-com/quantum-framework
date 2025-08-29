package com.e2eq.framework.rest.models;

import lombok.Data;

@Data
public class ResponseBase {
    String message;
    int statusCode;
}
