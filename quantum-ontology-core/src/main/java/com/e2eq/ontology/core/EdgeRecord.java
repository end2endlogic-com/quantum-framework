package com.e2eq.ontology.core;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * A transport-neutral representation of an ontology edge used by the EdgeStore abstraction.
 * Contains DataDomainInfo scoping to ensure proper isolation across organizations, accounts,
 * tenants, and data segments.
 */
public class EdgeRecord {
    private DataDomainInfo dataDomainInfo;
    private String src;
    private String srcType;
    private String p;
    private String dst;
    private String dstType;
    private boolean inferred; // legacy flag used by existing materializer
    private boolean derived;  // new flag for implied/derived edges
    private Map<String, Object> prov;
    private List<Support> support; // provenance support: list of rules and path edge ids
    private Date ts;

    public EdgeRecord() { }

    /**
     * Constructor for explicit or inferred edges with DataDomainInfo scoping.
     */
    public EdgeRecord(DataDomainInfo dataDomainInfo, String srcType, String src, String p, String dstType, String dst, boolean inferred, Map<String, Object> prov, Date ts) {
        this.dataDomainInfo = dataDomainInfo;
        this.srcType = srcType;
        this.src = src;
        this.p = p;
        this.dstType = dstType;
        this.dst = dst;
        this.inferred = inferred;
        this.derived = inferred; // keep in sync by default for backward compatibility
        this.prov = prov;
        this.ts = ts;
    }

    /**
     * Constructor for derived edges with support provenance and DataDomainInfo scoping.
     */
    public EdgeRecord(DataDomainInfo dataDomainInfo, String srcType, String src, String p, String dstType, String dst, boolean derived, Map<String, Object> prov, List<Support> support, Date ts) {
        this.dataDomainInfo = dataDomainInfo;
        this.srcType = srcType;
        this.src = src;
        this.p = p;
        this.dstType = dstType;
        this.dst = dst;
        this.derived = derived;
        this.inferred = derived; // map derived to inferred for legacy code paths
        this.prov = prov;
        this.support = support;
        this.ts = ts;
    }

    public DataDomainInfo getDataDomainInfo() { return dataDomainInfo; }
    public void setDataDomainInfo(DataDomainInfo dataDomainInfo) { this.dataDomainInfo = dataDomainInfo; }
    
    /**
     * Convenience getter for tenantId from the DataDomainInfo.
     * @return tenantId if dataDomainInfo is set, null otherwise
     */
    public String getTenantId() { 
        return dataDomainInfo != null ? dataDomainInfo.tenantId() : null; 
    }
    
    public String getSrc() { return src; }
    public void setSrc(String src) { this.src = src; }
    public String getSrcType() { return srcType; }
    public void setSrcType(String srcType) { this.srcType = srcType; }
    public String getP() { return p; }
    public void setP(String p) { this.p = p; }
    public String getDst() { return dst; }
    public void setDst(String dst) { this.dst = dst; }
    public String getDstType() { return dstType; }
    public void setDstType(String dstType) { this.dstType = dstType; }
    public boolean isInferred() { return inferred; }
    public void setInferred(boolean inferred) { this.inferred = inferred; }
    public boolean isDerived() { return derived; }
    public void setDerived(boolean derived) { this.derived = derived; }
    public Map<String, Object> getProv() { return prov; }
    public void setProv(Map<String, Object> prov) { this.prov = prov; }
    public List<Support> getSupport() { return support; }
    public void setSupport(List<Support> support) { this.support = support; }
    public Date getTs() { return ts; }
    public void setTs(Date ts) { this.ts = ts; }

    public static class Support {
        private String ruleId;
        private List<String> pathEdgeIds;

        public Support() {}
        public Support(String ruleId, List<String> pathEdgeIds) {
            this.ruleId = ruleId;
            this.pathEdgeIds = pathEdgeIds;
        }
        public String getRuleId() { return ruleId; }
        public void setRuleId(String ruleId) { this.ruleId = ruleId; }
        public List<String> getPathEdgeIds() { return pathEdgeIds; }
        public void setPathEdgeIds(List<String> pathEdgeIds) { this.pathEdgeIds = pathEdgeIds; }
    }
}
