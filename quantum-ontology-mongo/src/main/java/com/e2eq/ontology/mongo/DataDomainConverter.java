package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.DataDomainInfo;

/**
 * Utility class for converting between DataDomain (from quantum-models) and
 * DataDomainInfo (from quantum-ontology-core).
 */
public final class DataDomainConverter {
    
    private DataDomainConverter() {
        // Utility class
    }
    
    /**
     * Converts a DataDomain to DataDomainInfo.
     * 
     * @param dd the DataDomain to convert
     * @return the corresponding DataDomainInfo, or null if dd is null
     */
    public static DataDomainInfo toInfo(DataDomain dd) {
        if (dd == null) {
            return null;
        }
        return new DataDomainInfo(
            dd.getOrgRefName(),
            dd.getAccountNum(),
            dd.getTenantId(),
            dd.getDataSegment()
        );
    }
    
    /**
     * Converts a DataDomainInfo to DataDomain.
     * Note: ownerId will be set to "system" as DataDomainInfo does not contain it.
     * 
     * @param info the DataDomainInfo to convert
     * @return the corresponding DataDomain, or null if info is null
     */
    public static DataDomain fromInfo(DataDomainInfo info) {
        return fromInfo(info, "system");
    }
    
    /**
     * Converts a DataDomainInfo to DataDomain with a specified ownerId.
     * 
     * @param info    the DataDomainInfo to convert
     * @param ownerId the owner ID to set on the DataDomain
     * @return the corresponding DataDomain, or null if info is null
     */
    public static DataDomain fromInfo(DataDomainInfo info, String ownerId) {
        if (info == null) {
            return null;
        }
        DataDomain dd = new DataDomain();
        dd.setOrgRefName(info.orgRefName());
        dd.setAccountNum(info.accountNum());
        dd.setTenantId(info.tenantId());
        dd.setDataSegment(info.dataSegment());
        dd.setOwnerId(ownerId != null ? ownerId : "system");
        return dd;
    }
    
    /**
     * Creates a fallback DataDomainInfo from a tenantId/realmId for backward compatibility.
     * This should only be used when no proper DataDomain is available.
     * 
     * @param tenantId the tenant/realm ID
     * @return a DataDomainInfo with default org/account values
     */
    public static DataDomainInfo createFallbackInfo(String tenantId) {
        return new DataDomainInfo("ontology", "0000000000", tenantId, 0);
    }
    
    /**
     * Creates a fallback DataDomain from a tenantId/realmId for backward compatibility.
     * This should only be used when no proper DataDomain is available.
     * 
     * @param tenantId the tenant/realm ID
     * @return a DataDomain with default org/account values
     */
    public static DataDomain createFallbackDomain(String tenantId) {
        DataDomain dd = new DataDomain();
        dd.setOrgRefName("ontology");
        dd.setAccountNum("0000000000");
        dd.setTenantId(tenantId);
        dd.setOwnerId("system");
        dd.setDataSegment(0);
        return dd;
    }
}

