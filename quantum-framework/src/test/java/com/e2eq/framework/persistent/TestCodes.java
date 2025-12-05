package com.e2eq.framework.persistent;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.base.CodeList;
import com.e2eq.framework.model.persistent.morphia.CodeListRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.securityrules.SecuritySession;
import com.e2eq.framework.util.TestUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;

@QuarkusTest
public class TestCodes extends BaseRepoTest {
    @Inject
    CodeListRepo codeListRepo;

    @Inject
    MorphiaDataStoreWrapper dataStore;

    @Inject
    RuleContext ruleContext;

    @Inject
    TestUtils testUtils;


    public CodeList getOrCreateCodeList(String codeListName) {

        Optional<CodeList> codeList = codeListRepo.findByRefName(codeListName);
        if (codeList.isPresent()) {
            return codeList.get();
        } else {
            CodeList newCodeList = CodeList.builder()
                    .category("TEST")
                    .refName(codeListName)
                    .key(codeListName)
                    .build();
            codeListRepo.save(newCodeList);
            return newCodeList;
        }
    }

    @Test
    public void testAddCodes() throws ReferentialIntegrityViolationException {


        // Create a test user
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            CodeList codeList = getOrCreateCodeList("STATE_TWOLETTER_CODES");
            if (codeList.getValues() == null || codeList.getValues().isEmpty()) {
                codeList.setValueType("STRING");
                codeList.setValues(List.of("GA", "NC", "NY","NJ","FL"));
                codeList = codeListRepo.save(codeList);
                System.out.println(codeList);
            }
            codeListRepo.delete(codeList);
        }
    }
}
