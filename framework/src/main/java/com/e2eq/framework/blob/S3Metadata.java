package com.e2eq.framework.blob;


import lombok.Data;

import java.util.HashMap;
import java.util.Map;

public @Data class S3Metadata implements BlobMetadata{

    protected String fileName;
    protected  String contentType;
    protected long contentLength;
    protected String originIpAddress;
    protected String originHostName;
    protected String ownerId;
    protected String txId;
    Map<String, String> tags = new HashMap<String, String>();


    public S3Metadata() {

    }
    public S3Metadata(Map<String, String> metadata) {
        this.fileName = metadata.get("name");
        this.contentType = metadata.get("contentType");
        this.contentLength = Long.parseLong(metadata.get("contentLength"));
        this.originIpAddress = metadata.get("originIpAddress");
        this.originHostName = metadata.get("originHostName");
        this.ownerId =  metadata.get("ownerId");
        this.txId = metadata.get("txId");
    }

    @Override
    public void populateFromMap(Map<String, String> metadata) {
        this.fileName = metadata.get("name");
        this.contentType = metadata.get("contentType");
        this.contentLength = Long.parseLong(metadata.get("contentLength"));
        this.originIpAddress = metadata.get("originIpAddress");
        this.originHostName = metadata.get("originHostName");
        this.ownerId =  metadata.get("ownerId");
        this.txId = metadata.get("txId");
    }

    @Override
    public Map<String, String> toMap() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("fileName", fileName);
        metadata.put("contentType", contentType);
        metadata.put("contentLength", String.valueOf(contentLength));
        metadata.put("originIpAddress", originIpAddress);
        metadata.put("originHostName", originHostName);
        metadata.put("ownerId", ownerId);
        metadata.put("txId", txId);
        return metadata;
    }

}
