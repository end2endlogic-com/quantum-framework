package com.e2eq.framework.security.principal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A script that runs decides; a script that cannot run is a typed failure, never a quiet false. */
class ImpersonationScriptEvaluatorTest {

    private final ImpersonationScriptEvaluator evaluator = new ImpersonationScriptEvaluator();

    @Test
    void aScriptThatRunsDecides() {
        assertTrue(evaluator.evaluate("sub-1", "svc", "acme-D1", "true"));
        assertTrue(evaluator.evaluate("sub-1", "svc", "acme-D1", "realm === 'acme-D1'"));
        assertFalse(evaluator.evaluate("sub-1", "svc", "other-D1", "realm === 'acme-D1'"));
        assertFalse(evaluator.evaluate("sub-1", "svc", "acme-D1", "'yes'"));
    }

    @Test
    void aScriptThatCannotRunIsATypedFailure() {
        var unparsable = assertThrows(ImpersonationScriptEvaluator.ScriptEvaluationException.class,
                () -> evaluator.evaluate("sub-1", "svc", "acme-D1", "this is not javascript ("));
        assertTrue(unparsable.getMessage().contains("svc"));
        assertTrue(unparsable.getMessage().contains("acme-D1"));
        assertThrows(ImpersonationScriptEvaluator.ScriptEvaluationException.class,
                () -> evaluator.evaluate("sub-1", "svc", "acme-D1", "throw new Error('boom')"));
        assertThrows(ImpersonationScriptEvaluator.ScriptEvaluationException.class,
                () -> evaluator.evaluate("sub-1", "svc", "acme-D1", "while (true) {}"));
    }
}
