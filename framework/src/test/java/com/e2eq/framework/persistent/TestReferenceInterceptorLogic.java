package com.e2eq.framework.persistent;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.base.ReferenceEntry;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.test.TestChildListModel;
import com.e2eq.framework.test.TestChildModel;
import com.e2eq.framework.test.TestParentModel;
import com.e2eq.framework.util.TestUtils;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@QuarkusTest
public class TestReferenceInterceptorLogic extends BaseRepoTest{


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
        dataStore.getDataStore(parentRepo.getSecurityContextRealmId()).ensureIndexes(TestParentModel.class);
        TestParentModel parent = new TestParentModel();
        parent.setRefName(refName);
        parent.setDataDomain(testUtils.getTestDataDomain());
        parent.setAuditInfo(testUtils.createAuditInfo());
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
        List<TestParentModel> parents = new ArrayList<>();
        TestParentModel p1 = parentRepo.findByRefName("Test1").orElse(null);
        if (p1 == null) {
            parents.add(createParent("Test1"));
        } else {
            parents.add(p1);
        }

        TestParentModel p2 = parentRepo.findByRefName("Test2").orElse(null);
        if (p2 == null) {
            parents.add(createParent("Test2"));
        } else {
            parents.add(p2);
        }

        return parents;
    }

    TestChildModel getOrCreateChild(TestParentModel parent) {
        Optional<TestChildModel> ochild = childRepo.findByRefName("Test1");
        if (ochild.isPresent()) {
            return ochild.get();
        }
        dataStore.getDataStore(childRepo.getSecurityContextRealmId()).ensureIndexes(TestChildModel.class);
        TestChildModel child = new TestChildModel();
        child.setRefName("Test1");
        child.setDataDomain(testUtils.getTestDataDomain());
        child.setAuditInfo(testUtils.createAuditInfo());
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
        dataStore.getDataStore(childListRepo.getSecurityContextRealmId()).ensureIndexes(TestChildListModel.class);
        TestChildListModel child = new TestChildListModel();
        child.setRefName(refName);
        child.setDataDomain(testUtils.getTestDataDomain());
        child.setAuditInfo(testUtils.createAuditInfo());
        child.setParents(parents);
        child = childListRepo.save(child);

        return child;
    }


    @Test
    public void testInterceptor() {

        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {

            TestParentModel parent = getOrCreateParent();
            TestChildModel child = getOrCreateChild(parent);
            parent = getOrCreateParent();
            Assertions.assertNotNull(parent.getReferences());
            Assertions.assertTrue(parent.getReferences().contains(new ReferenceEntry(child.getId(), TestChildModel.class.getTypeName(),
                    child.getRefName())));

            // delete the parent, the child should be deleted as well)));

            try {
                parentRepo.delete(parent);
                Assertions.assertTrue(false); // should throw because there is a referencing child
            } catch (ReferentialIntegrityViolationException e) {
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

    void clearCollections()   {
        childRepo.getAllList().forEach(c -> {
            try {
                childRepo.delete(c);
            } catch (ReferentialIntegrityViolationException e) {
                throw new RuntimeException(e);
            }
        });
        childListRepo.getAllList().forEach(c -> {
            try {
                childListRepo.delete(c);
            } catch (ReferentialIntegrityViolationException e) {
                throw new RuntimeException(e);
            }
        });
        parentRepo.getAllList().forEach(p -> {
            try {
                parentRepo.delete(p);
            } catch (ReferentialIntegrityViolationException e) {
                throw new RuntimeException(e);
            }
        });

    }

    @Test
    public void testInterceptorWithCollection() throws ReferentialIntegrityViolationException {

        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {
            clearCollections();
            // create two parents and a child with a list of parents

            // create two parents
            List<TestParentModel> parents = getOrCreateParentList();

            // create a child with a list of parents
            TestChildListModel child = getOrCreateChild("Test2", parents);

            // check that the parents are referenced in the child list
            for (TestParentModel p : parents) {
                Assertions.assertTrue(p.getReferences().contains(new ReferenceEntry(child.getId(),
                        TestChildListModel.class.getTypeName(), child.getRefName())));
            }

            // delete the child, the parents references should be deleted as well and the child list should be updated
            childListRepo.delete(child);

            // re-get the parents and check that the result has occurred.
            parents = getOrCreateParentList();

            // check that the parents are no longer have references from the child list
            for (TestParentModel p : parents) {
                if (p.getReferences() != null && !p.getReferences().isEmpty())
                    Assertions.assertFalse(p.getReferences().contains(new ReferenceEntry(child.getId(), TestChildListModel.class.getTypeName(),
                            child.getRefName())));
                parentRepo.delete(p);
            }

            // check that the child has also been deleted
            Optional<TestChildListModel> ochild = childListRepo.findByRefName("Test2");
            Assertions.assertTrue(ochild.isEmpty());
        }
    }
}
