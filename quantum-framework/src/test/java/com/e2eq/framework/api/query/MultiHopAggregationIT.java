package com.e2eq.framework.api.query;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.security.runtime.RuleContext;
import dev.morphia.Datastore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "test@system.com", roles = {"admin", "user"})
public class MultiHopAggregationIT extends BaseRepoTest {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    CredentialRepo credRepo;

    @Inject
    RuleContext ruleContext;

    private String realm;

    @BeforeEach
    public void setUp() {
        realm = testUtils.getTestRealm();
        morphiaDataStoreWrapper.getDataStore(realm);

        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            java.util.Optional<CredentialUserIdPassword> credop = credRepo.findByUserId(
                    testUtils.getTestUserId(), testUtils.getSystemRealm());
            if (credop.isEmpty()) {
                CredentialUserIdPassword cred = new CredentialUserIdPassword();
                cred.setUserId(testUtils.getTestUserId());
                cred.setSubject(testUtils.getTestUserId());
                cred.setRoles(new String[]{"admin", "user"});
                DataDomain dd = testUtils.getTestDataDomain();
                cred.setDataDomain(dd);
                cred.setDomainContext(new DomainContext(dd, testUtils.getTestRealm()));
                cred.setRealmRegEx("*");
                credRepo.save(testUtils.getSystemRealm(), cred);
            }
        }
    }

    @Test
    public void multiHop_traversal_hydrates_nested_references() {
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        DataDomain dd = testUtils.getTestDataDomain();

        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            // Seed Supplier
            MultiHopSupplier supplier = new MultiHopSupplier();
            supplier.setSupplierName("Acme Global Supplies");
            supplier.setWholesaleCost(42.50);
            supplier.setTaxId("TAX-998877");
            supplier.setRefName("SUP-001");
            supplier.setDataDomain(dd);
            supplier = ds.save(supplier);

            // Seed Order referencing Supplier
            MultiHopOrder order = new MultiHopOrder();
            order.setOrderNumber("ORD-2026-001");
            order.setRefName("ORD-2026-001");
            order.setDataDomain(dd);
            EntityReference supRef = new EntityReference();
            supRef.setEntityId(supplier.getId());
            supRef.setEntityRefName(supplier.getRefName());
            supRef.setEntityDisplayName(supplier.getSupplierName());
            supRef.setRealm(dd.getTenantId());
            supRef.setEntityType(MultiHopSupplier.class.getName());
            order.setSupplierRef(supRef);
            order = ds.save(order);

            // Seed Customer referencing Order
            MultiHopCustomer customer = new MultiHopCustomer();
            customer.setCustomerName("Cust-InDomain");
            customer.setRefName("CUST-001");
            customer.setDataDomain(dd);
            EntityReference ordRef = new EntityReference();
            ordRef.setEntityId(order.getId());
            ordRef.setEntityRefName(order.getRefName());
            ordRef.setEntityDisplayName(order.getOrderNumber());
            ordRef.setRealm(dd.getTenantId());
            ordRef.setEntityType(MultiHopOrder.class.getName());
            customer.setOrderRef(ordRef);
            ds.save(customer);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("rootType", MultiHopCustomer.class.getName());
        body.put("query", "expand(orderRef) && expand(orderRef.supplierRef) && customerName:Cust-InDomain");
        body.put("realm", realm);

        io.restassured.response.Response resp = given()
                .header("X-Realm", realm)
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/query/find");

        resp.then()
                .statusCode(200)
                .body("rows", notNullValue())
                .body("rows.size()", greaterThan(0))
                .body("rows[0].customerName", equalTo("Cust-InDomain"))
                .body("rows[0].orderRef", notNullValue())
                .body("rows[0].orderRef.orderNumber", equalTo("ORD-2026-001"))
                .body("rows[0].orderRef.supplierRef", notNullValue())
                .body("rows[0].orderRef.supplierRef.supplierName", equalTo("Acme Global Supplies"))
                .body("rows[0].orderRef.supplierRef.wholesaleCost", equalTo(42.5f));
    }

    @Test
    public void multiHop_prunes_cross_tenant_references_at_each_hop() {
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        DataDomain ddInDomain = testUtils.getTestDataDomain();
        DataDomain ddForeign = new DataDomain("foreignOrg", "8888888888", "foreign-tenant-xyz", 0, "foreignOwner");

        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            // Seed Supplier in FOREIGN tenant
            MultiHopSupplier foreignSupplier = new MultiHopSupplier();
            foreignSupplier.setSupplierName("Foreign Rogue Supplier");
            foreignSupplier.setWholesaleCost(999.0);
            foreignSupplier.setTaxId("FOREIGN-TAX");
            foreignSupplier.setRefName("SUP-FOREIGN");
            foreignSupplier.setDataDomain(ddForeign);
            foreignSupplier = ds.save(foreignSupplier);

            // Seed Order in local domain referencing the FOREIGN supplier
            MultiHopOrder order = new MultiHopOrder();
            order.setOrderNumber("ORD-LEAK-PROBE");
            order.setRefName("ORD-LEAK-PROBE");
            order.setDataDomain(ddInDomain);
            EntityReference supRef = new EntityReference();
            supRef.setEntityId(foreignSupplier.getId());
            supRef.setEntityRefName(foreignSupplier.getRefName());
            supRef.setEntityDisplayName(foreignSupplier.getSupplierName());
            supRef.setRealm(ddForeign.getTenantId());
            supRef.setEntityType(MultiHopSupplier.class.getName());
            order.setSupplierRef(supRef);
            order = ds.save(order);

            // Seed Customer in local domain referencing the Order
            MultiHopCustomer customer = new MultiHopCustomer();
            customer.setCustomerName("Cust-ForeignSupplierProbe");
            customer.setRefName("CUST-FOREIGN-PROBE");
            customer.setDataDomain(ddInDomain);
            EntityReference ordRef = new EntityReference();
            ordRef.setEntityId(order.getId());
            ordRef.setEntityRefName(order.getRefName());
            ordRef.setEntityDisplayName(order.getOrderNumber());
            ordRef.setRealm(ddInDomain.getTenantId());
            ordRef.setEntityType(MultiHopOrder.class.getName());
            customer.setOrderRef(ordRef);
            ds.save(customer);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("rootType", MultiHopCustomer.class.getName());
        body.put("query", "expand(orderRef) && expand(orderRef.supplierRef) && customerName:Cust-ForeignSupplierProbe");
        body.put("realm", realm);

        io.restassured.response.Response resp = given()
                .header("X-Realm", realm)
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/query/find");

        resp.then()
                .statusCode(200)
                .body("rows", notNullValue())
                .body("rows.size()", greaterThan(0))
                .body("rows[0].customerName", equalTo("Cust-ForeignSupplierProbe"))
                .body("rows[0].orderRef", notNullValue())
                .body("rows[0].orderRef.orderNumber", equalTo("ORD-LEAK-PROBE"))
                // Foreign supplier MUST be pruned (evaluated to null) due to tenant boundary isolation
                .body("rows[0].orderRef.supplierRef", nullValue());
    }

    @Test
    public void multiHop_redacts_sensitive_fields_at_target_hop() {
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        DataDomain dd = testUtils.getTestDataDomain();

        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            MultiHopSupplier supplier = new MultiHopSupplier();
            supplier.setSupplierName("Redacted Target Supplies");
            supplier.setWholesaleCost(75.25);
            supplier.setTaxId("CONFIDENTIAL-TAX-99");
            supplier.setRefName("SUP-REDACT");
            supplier.setDataDomain(dd);
            supplier = ds.save(supplier);

            MultiHopOrder order = new MultiHopOrder();
            order.setOrderNumber("ORD-REDACT-001");
            order.setRefName("ORD-REDACT-001");
            order.setDataDomain(dd);
            EntityReference supRef = new EntityReference();
            supRef.setEntityId(supplier.getId());
            supRef.setEntityRefName(supplier.getRefName());
            supRef.setEntityDisplayName(supplier.getSupplierName());
            supRef.setRealm(dd.getTenantId());
            supRef.setEntityType(MultiHopSupplier.class.getName());
            order.setSupplierRef(supRef);
            order = ds.save(order);

            MultiHopCustomer customer = new MultiHopCustomer();
            customer.setCustomerName("Cust-RedactionTest");
            customer.setRefName("CUST-REDACT-001");
            customer.setDataDomain(dd);
            EntityReference ordRef = new EntityReference();
            ordRef.setEntityId(order.getId());
            ordRef.setEntityRefName(order.getRefName());
            ordRef.setEntityDisplayName(order.getOrderNumber());
            ordRef.setRealm(dd.getTenantId());
            ordRef.setEntityType(MultiHopOrder.class.getName());
            customer.setOrderRef(ordRef);
            ds.save(customer);
        }

        // Install security rule redacting wholesaleCost and taxId on target hop
        SecurityURIHeader header = new SecurityURIHeader.Builder()
                .withIdentity("admin").withArea("PROCUREMENT").withFunctionalDomain("SUPPLIERS").withAction("find").build();
        SecurityURIBody uriBody = new SecurityURIBody.Builder()
                .withOrgRefName("*").withAccountNumber("*").withRealm("*")
                .withTenantId("*").withOwnerId("*").withDataSegment("*").build();
        Rule redactRule = new Rule.Builder()
                .withName("multihop-redact-supplier-" + System.currentTimeMillis())
                .withSecurityURI(new SecurityURI(header, uriBody))
                .withEffect(RuleEffect.ALLOW)
                .withExcludedFields(List.of("wholesaleCost", "taxId"))
                .withPriority(-50)
                .withFinalRule(false)
                .build();
        ruleContext.addRule(header, redactRule);
        ruleContext.clearCacheForRealm(realm);
        RuleContext.clearRequestCache();

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("rootType", MultiHopCustomer.class.getName());
            body.put("query", "expand(orderRef) && expand(orderRef.supplierRef) && customerName:Cust-RedactionTest");
            body.put("realm", realm);

            io.restassured.response.Response resp = given()
                    .header("X-Realm", realm)
                    .contentType(ContentType.JSON)
                    .body(body)
                    .when()
                    .post("/api/query/find");

            resp.then()
                    .statusCode(200)
                    .body("rows", notNullValue())
                    .body("rows.size()", greaterThan(0))
                    .body("rows[0].orderRef.supplierRef", notNullValue())
                    .body("rows[0].orderRef.supplierRef.supplierName", equalTo("Redacted Target Supplies"))
                    // Sensitive fields MUST be redacted / absent from the returned document
                    .body("rows[0].orderRef.supplierRef.wholesaleCost", nullValue())
                    .body("rows[0].orderRef.supplierRef.taxId", nullValue());
        } finally {
            ruleContext.clear();
            ruleContext.ensureDefaultRules();
        }
    }
}
