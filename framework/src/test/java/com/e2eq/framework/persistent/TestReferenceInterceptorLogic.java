package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.ReferenceEntry;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import com.e2eq.framework.model.persistent.morphia.RepoUtils;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.test.TestChildListModel;
import com.e2eq.framework.test.TestChildModel;
import com.e2eq.framework.test.TestParentModel;
import com.e2eq.framework.util.TestUtils;
import dev.morphia.MorphiaDatastore;
import dev.morphia.mapping.Mapper;
import dev.morphia.mapping.codec.pojo.EntityModel;
import dev.morphia.mapping.codec.pojo.PropertyModel;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

@QuarkusTest
public class TestReferenceInterceptorLogic {


    @Inject
    protected MorphiaDataStore dataStore;

    @Inject
    protected TestChildRepo childRepo;

    @Inject
    protected TestChildListRepo childListRepo;

    @Inject
    protected TestParentRepo parentRepo;

    @Inject
    RuleContext ruleContext;

    TestParentModel createParent(String refName) {
        TestParentModel parent = new TestParentModel();
        parent.setRefName(refName);
        parent.setDataDomain(TestUtils.dataDomain);
        parent.setAuditInfo(TestUtils.createAuditInfo());
        parent = parentRepo.save(parent);

        return parent;
    }

    TestParentModel getOrCreateParent() {
        Optional<TestParentModel> oparent = parentRepo.findByRefName("Test1");
        if (oparent.isPresent()) {
            return oparent.get();
        } else
            return createParent("Test1");
    }

    List<TestParentModel> getOrCreateParentList() {
        List<TestParentModel> parents = parentRepo.getAllList();
        if (parents.size() < 2) {
            parents.add(createParent("Test1"));
            parents.add(createParent("Test2"));
        }
        return parents;
    }

    TestChildModel getOrCreateChild(TestParentModel parent) {
        Optional<TestChildModel> ochild = childRepo.findByRefName("Test1");
        if (ochild.isPresent()) {
            return ochild.get();
        }

        TestChildModel child = new TestChildModel();
        child.setRefName("Test1");
        child.setDataDomain(TestUtils.dataDomain);
        child.setAuditInfo(TestUtils.createAuditInfo());
        child.setParent(parent);
        child = childRepo.save(child);

        return child;
    }

    TestChildListModel getOrCreateChild(String refName, List<TestParentModel> parents) {
        if (parents.isEmpty() || parents.size() < 2) {
            throw new IllegalStateException("At least two parents are required for creating a child");
        }
        Optional<TestChildListModel> ochild = childListRepo.findByRefName(refName);
        if (ochild.isPresent()) {
            return ochild.get();
        }

        TestChildListModel child = new TestChildListModel();
        child.setRefName(refName);
        child.setDataDomain(TestUtils.dataDomain);
        child.setAuditInfo(TestUtils.createAuditInfo());
        child.setParents(parents);
        child = childListRepo.save(child);

        return child;
    }


    @Test
    public void testInterceptor() {
        TestUtils.initRules(ruleContext, "security","userProfile", TestUtils.userId);
        String[] roles = {"user"};
        PrincipalContext pContext = TestUtils.getPrincipalContext(TestUtils.userId, roles);
        ResourceContext rContext = TestUtils.getResourceContext(TestUtils.area, "userProfile", "save");

        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {

            TestParentModel parent = getOrCreateParent();
            TestChildModel child = getOrCreateChild(parent);

            Assertions.assertTrue(parent.getReferences().contains(new ReferenceEntry(child.getId(), TestChildModel.class.getTypeName())));

            try {
                parentRepo.delete(parent);
                Assertions.assertTrue(false); // should throw because there is a referencing child
            } catch (IllegalStateException e) {
                // expected
            }

            try (MorphiaSession s = dataStore.getDefaultSystemDataStore().startSession()) {
                s.startTransaction();
                s.delete(child);
                s.delete(parent);
                s.commitTransaction();
            } catch (Throwable t) {
                Assertions.assertTrue(false);
            }
        }
    }


    @Test
    public void testBasicsOfListReferences() {
        TestUtils.initRules(ruleContext, "security","userProfile", TestUtils.userId);
        String[] roles = {"user"};
        PrincipalContext pContext = TestUtils.getPrincipalContext(TestUtils.userId, roles);
        ResourceContext rContext = TestUtils.getResourceContext(TestUtils.area, "userProfile", "save");
        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {
            childListRepo.getAllList();
        }
    }

    @Test
    public void testInterceptorWithCollection() {

        TestUtils.initRules(ruleContext, "security","userProfile", TestUtils.userId);
        String[] roles = {"user"};
        PrincipalContext pContext = TestUtils.getPrincipalContext(TestUtils.userId, roles);
        ResourceContext rContext = TestUtils.getResourceContext(TestUtils.area, "userProfile", "save");

        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {
            List<TestParentModel> parents = getOrCreateParentList();
            // create a child with a list of parents
            TestChildListModel child = getOrCreateChild("Test2", parents);

            for (TestParentModel p : parents) {
                Assertions.assertTrue(p.getReferences().contains(new ReferenceEntry(child.getId(), TestChildListModel.class.getTypeName())));
            }
            childListRepo.delete(child);
            for (TestParentModel p : parents) {
                Assertions.assertFalse(p.getReferences().contains(new ReferenceEntry(child.getId(), TestChildListModel.class.getTypeName())));
                parentRepo.delete(p);
            }

        }
    }



}
