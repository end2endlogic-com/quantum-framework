package com.e2eq.framework.rest.models;

import org.jboss.resteasy.reactive.RestForm;

import java.io.File;

public class FileUpload {
    @RestForm("file")
    public File file;
}