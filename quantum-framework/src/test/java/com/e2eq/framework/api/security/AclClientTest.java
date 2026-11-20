package com.e2eq.framework.api.security;

import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class AclClientTest {

    private String loadClientJs() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("META-INF/resources/security/acl-client.js")) {
            assertNotNull(is, "acl-client.js should be on the classpath under META-INF/resources/security/");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return br.lines().collect(Collectors.joining("\n"));
            }
        }
    }

    private Context newJsContext() {
        return Context.newBuilder("js").allowAllAccess(true).build();
    }

    private Value eval(String js) throws Exception {
        Context ctx = newJsContext();
        ctx.eval("js", loadClientJs());
        return ctx.eval("js", SNAPSHOT + js);
    }

    /** The shape the server returns: one scope (requestedScope), lower-case keys, allowed cells only. */
    private static final String SNAPSHOT = "" +
            "var key = 'org=acme|acct=a1|tenant=t-1|seg=0|owner=u1';\n" +
            "var snapshot = { enabled: true, requiresServer: false, requestedScope: key, requestedFallback: [key],\n" +
            "  scopes: {} };\n" +
            "snapshot.scopes[key] = { requiresServer: false, matrix: {\n" +
            "  security: { userprofile: { view: { effect: 'ALLOW', priority: 0, finalRule: true, rule: 'test' } } } } };\n";

    @Test
    public void testDecideAllowsACellWhateverTheCaseAsked() throws Exception {
        assertEquals("ALLOW", eval("ACLClient.decide(snapshot, null, 'security', 'userProfile', 'view');").asString());
        assertEquals("ALLOW", eval("ACLClient.decide(snapshot, null, 'SECURITY', 'USERPROFILE', 'VIEW');").asString());
        assertEquals("test", eval("ACLClient.decideOutcome(snapshot, null, 'security', 'userProfile', 'view').rule;").asString());
    }

    @Test
    public void testDecideDeniesAMissingCell() throws Exception {
        assertEquals("DENY", eval("ACLClient.decide(snapshot, null, 'security', 'userProfile', 'update');").asString());
        assertEquals("DENY", eval("ACLClient.decide(snapshot, null, 'constructor', 'toString', 'valueOf');").asString());
        assertEquals("DENY", eval("ACLClient.decide(null, null, 'security', 'userProfile', 'view');").asString());
    }

    @Test
    public void testOnlyTheSnapshotsOwnDataDomainIsAnswered() throws Exception {
        String same = "{ orgRefName: 'Acme', accountNumber: 'A1', tenantId: 'T-1', dataSegment: 0, ownerId: 'u1' }";
        String other = "{ orgRefName: 'acme', accountNumber: 'A1', tenantId: 't-2', dataSegment: 0, ownerId: 'u1' }";
        assertEquals("ALLOW", eval("ACLClient.decide(snapshot, " + same + ", 'security', 'userProfile', 'view');").asString());
        assertEquals("DENY", eval("ACLClient.decide(snapshot, " + other + ", 'security', 'userProfile', 'view');").asString());
        assertTrue(eval("ACLClient.decideOutcome(snapshot, " + other + ", 'security', 'userProfile', 'view') === null;").asBoolean());
    }

    @Test
    public void testScopeKeyIsLowerCaseAndThereIsNoWidening() throws Exception {
        Value obj = eval("var k = ACLClient.scopeKeyFromDataDomain({ orgRefName: 'Acme', accountNumber: 'A1', tenantId: 't-1', dataSegment: 0 });\n" +
                "({ key: k, chain: ACLClient.buildFallbackChain(k) });");
        assertEquals("org=acme|acct=a1|tenant=t-1|seg=0|owner=*", obj.getMember("key").asString());
        assertEquals(1, obj.getMember("chain").getArraySize());
        assertEquals("org=acme|acct=a1|tenant=t-1|seg=0|owner=*", obj.getMember("chain").getArrayElement(0).asString());
    }
}
