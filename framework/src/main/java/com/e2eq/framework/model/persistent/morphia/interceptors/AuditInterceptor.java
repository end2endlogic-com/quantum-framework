package com.e2eq.framework.model.persistent.morphia.interceptors;

import com.e2eq.framework.model.persistent.base.AuditInfo;
import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.SecurityContext;
import dev.morphia.Datastore;
import dev.morphia.EntityListener;
import dev.morphia.annotations.PrePersist;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.Document;

import java.lang.annotation.Annotation;
import java.util.Date;

@ApplicationScoped
public class AuditInterceptor implements EntityListener<Object> {
    @Override
    @PrePersist
    public void prePersist(Object ent, Document document, Datastore datastore) {
        BaseModel bm = null;
        if (ent instanceof UnversionedBaseModel) {
            bm = (BaseModel) ent;

            // Moved to the ValidationInterceptor to set the DataDomain.
           /* if (SecurityContext.getPrincipalContext().isPresent()) {
                if (bm.getDataDomain() == null) {
                    DataDomain dd = SecurityContext.getPrincipalDataDomain().get();

                    dd.setOrgRefName(SecurityContext.getPrincipalContext().get().getDataDomain().getOrgRefName());
                    dd.setAccountNum(SecurityContext.getPrincipalContext().get().getDataDomain().getAccountNum());
                    dd.setTenantId(SecurityContext.getPrincipalContext().get().getDataDomain().getTenantId());
                    dd.setOwnerId(SecurityContext.getPrincipalContext().get().getUserId());
                    dd.setDataSegment(SecurityContext.getPrincipalContext().get().getDataDomain().getDataSegment());
                }
            } */
            if (bm.getAuditInfo() == null || bm.getAuditInfo().getCreationTs() == null) {
                AuditInfo auditInfo = new AuditInfo();
                auditInfo.setCreationTs(new Date());
                auditInfo.setCreationIdentity(SecurityContext.getPrincipalContext().isPresent() ? SecurityContext.getPrincipalContext().get().getUserId() : "ANONYMOUS");
                bm.setAuditInfo(auditInfo);
            } else {
                bm.getAuditInfo().setLastUpdateTs(new Date());
                bm.getAuditInfo().setLastUpdateIdentity(SecurityContext.getPrincipalContext().isPresent() ? SecurityContext.getPrincipalContext().get().getUserId() : "ANONYMOUS");
            }


        }

    }

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> type) {
        return false;
    }
}
