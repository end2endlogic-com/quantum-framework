package com.e2eq.framework.rest.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    protected String access_token;
    protected String refresh_token;
    protected long expires_at;



}
