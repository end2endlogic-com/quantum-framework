package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.base.CodeList;
import com.e2eq.framework.model.persistent.morphia.CodeListRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.util.TestUtils;
import dev.morphia.Datastore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;

@QuarkusTest
public class TestCodes {
    @Inject
    CodeListRepo codeListRepo;

    @Inject
    MorphiaDataStore dataStore;

    @Inject
    RuleContext ruleContext;


    public CodeList getOrCreateCodeList(String codeListName) {

        Optional<CodeList> codeList = codeListRepo.findByRefName(codeListName);
        if (codeList.isPresent()) {
            return codeList.get();
        } else {
            CodeList newCodeList = new CodeList();
            newCodeList.setRefName(codeListName);
            codeListRepo.save(newCodeList);
            return newCodeList;
        }
    }

    @Test
    public void testAddCodes() {
        Datastore ds = dataStore.getDefaultSystemDataStore();
        String[] roles = {"user"};
        PrincipalContext pContext = TestUtils.getPrincipalContext(TestUtils.systemUserId, roles);
        ResourceContext rContext = TestUtils.getResourceContext(TestUtils.area, "userProfile", "save");
        TestUtils.initRules(ruleContext, "security", "userProfile", TestUtils.systemUserId);
        // Create a test user
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            CodeList codeList = getOrCreateCodeList("STATE_TWOLETTER_CODES");
            if (codeList.getValues() == null || codeList.getValues().isEmpty()) {
                codeList.setValueType("STRING");
                codeList.setValues(List.of("GA", "NC", "NY","NJ","FL"));
                codeList = codeListRepo.save(codeList);
                System.out.println(codeList);
            }
        }
    }
}
