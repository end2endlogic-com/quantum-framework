package com.e2eq.framework.controlplane.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ControlPlanePersistentEvent {
    private String eventType;
    private String eventMessage;
    private Long eventDate;
    private String userId;
    private Long version;
    private Map<String, Object> eventData;

    @JsonProperty("eventType")
    public String getEventType() { return eventType; }

    @JsonProperty("eventType")
    public void setEventType(String eventType) { this.eventType = eventType; }

    @JsonProperty("eventMessage")
    public String getEventMessage() { return eventMessage; }

    @JsonProperty("eventMessage")
    public void setEventMessage(String eventMessage) { this.eventMessage = eventMessage; }

    @JsonProperty("eventDate")
    public Long getEventDate() { return eventDate; }

    @JsonProperty("eventDate")
    public void setEventDate(Long eventDate) { this.eventDate = eventDate; }

    @JsonProperty("userId")
    public String getUserId() { return userId; }

    @JsonProperty("userId")
    public void setUserId(String userId) { this.userId = userId; }

    @JsonProperty("version")
    public Long getVersion() { return version; }

    @JsonProperty("version")
    public void setVersion(Long version) { this.version = version; }

    @JsonProperty("eventData")
    public Map<String, Object> getEventData() { return eventData; }

    @JsonProperty("eventData")
    public void setEventData(Map<String, Object> eventData) { this.eventData = eventData; }
}
