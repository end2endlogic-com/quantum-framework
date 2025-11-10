package com.e2eq.ontology.core;

import java.util.Date;
import java.util.Map;

/**
 * A transport-neutral representation of an ontology edge used by the EdgeStore abstraction.
 * This DTO intentionally avoids persistence-module classes to keep the core materializer unit-testable.
 */
public class EdgeRecord {
    private String tenantId;
    private String src;
    private String srcType;
    private String p;
    private String dst;
    private String dstType;
    private boolean inferred;
    private Map<String, Object> prov;
    private Date ts;

    public EdgeRecord() { }

    public EdgeRecord(String tenantId, String srcType, String src, String p, String dstType, String dst, boolean inferred, Map<String, Object> prov, Date ts) {
        this.tenantId = tenantId;
        this.srcType = srcType;
        this.src = src;
        this.p = p;
        this.dstType = dstType;
        this.dst = dst;
        this.inferred = inferred;
        this.prov = prov;
        this.ts = ts;
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
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
    public Map<String, Object> getProv() { return prov; }
    public void setProv(Map<String, Object> prov) { this.prov = prov; }
    public Date getTs() { return ts; }
    public void setTs(Date ts) { this.ts = ts; }
}
