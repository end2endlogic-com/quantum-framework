package com.e2eq.framework.model.persistent.usage;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.annotations.Indexed;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.types.ObjectId;

import java.time.Instant;

/**
 * Single API call usage record for metering and billing.
 * Stored per tenant; caller and area/domain/action allow granular reporting.
 *
 * @see LlmUsageRecord
 * @see TenantTokenAllocation
 */
@RegisterForReflection
@Entity(value = "api_call_usage", useDiscriminator = false)
public class ApiCallUsageRecord {

    @Id
    private ObjectId id;

    @Indexed
    private String realm;
    /** Caller user id (principal). */
    @Indexed
    private String callerUserId;
    /** API area (e.g. integration). */
    private String area;
    /** API functional domain (e.g. query). */
    private String functionalDomain;
    /** API action (e.g. find, save). */
    private String action;
    /** Optional request path for extra granularity. */
    private String path;
    /** When the call occurred. */
    @Indexed
    private Instant at;
    /** Allocation id that was debited (if any). */
    private ObjectId allocationId;

    public ApiCallUsageRecord() {
    }

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public String getCallerUserId() {
        return callerUserId;
    }

    public void setCallerUserId(String callerUserId) {
        this.callerUserId = callerUserId;
    }

    public String getArea() {
        return area;
    }

    public void setArea(String area) {
        this.area = area;
    }

    public String getFunctionalDomain() {
        return functionalDomain;
    }

    public void setFunctionalDomain(String functionalDomain) {
        this.functionalDomain = functionalDomain;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }

    public ObjectId getAllocationId() {
        return allocationId;
    }

    public void setAllocationId(ObjectId allocationId) {
        this.allocationId = allocationId;
    }

    /** API identifier in form area/domain/action. */
    public String apiIdentifier() {
        return (area != null ? area : "") + "/" + (functionalDomain != null ? functionalDomain : "") + "/" + (action != null ? action : "");
    }
}
