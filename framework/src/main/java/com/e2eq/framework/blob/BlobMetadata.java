package com.e2eq.framework.blob;
import java.util.Map;
public interface BlobMetadata {

    Map<String, String> toMap();

    void populateFromMap(Map<String, String> metadata);
    String getOriginIpAddress();

    public void setOriginIpAddress(String originIpAddress);

    String getOriginHostName();

    public void setOriginHostName(String originHostName);
    String getOwnerId();

    public void setOwnerId(String ownerId);
    String getTxId();

    public void setTxId(String txId);
    Map<String, String> getTags();

    void setTags(Map<String, String> tags);

    String getContentType();
    void setContentType(String contentType);
    String getFileName();
     void setFileName(String fileName);


}
