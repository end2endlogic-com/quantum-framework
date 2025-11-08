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

    @Test
    public void testDecideAllowExactMatch() throws Exception {
        String lib = loadClientJs();
        try (Context ctx = newJsContext()) {
            ctx.eval("js", lib);

            // Construct a minimal snapshot with a single global scope and an ALLOW rule
            String js = "" +
                    "var snapshot = {\n" +
                    "  enabled: true,\n" +
                    "  scopes: {\n" +
                    "    'org=*|acct=*|tenant=*|seg=*|owner=*': {\n" +
                    "      requiresServer: false,\n" +
                    "      matrix: {\n" +
                    "        security: { userProfile: { view: { effect: 'ALLOW', priority: 0, finalRule: true, rule: 'test' } } }\n" +
                    "      }\n" +
                    "    }\n" +
                    "  },\n" +
                    "  requestedScope: 'org=*|acct=*|tenant=*|seg=*|owner=*',\n" +
                    "  requestedFallback: ['org=*|acct=*|tenant=*|seg=*|owner=*']\n" +
                    "};\n" +
                    "ACLClient.decide(snapshot, null, 'security', 'userProfile', 'view');";

            Value result = ctx.eval("js", js);
            String decision = result.asString();
            assertEquals("ALLOW", decision, "Expected ALLOW from client library decide()");
        }
    }

    @Test
    public void testDecideDenyWhenNoMatch() throws Exception {
        String lib = loadClientJs();
        try (Context ctx = newJsContext()) {
            ctx.eval("js", lib);

            // Snapshot with ALLOW only for a different action; lookup should DENY
            String js = "" +
                    "var snapshot = {\n" +
                    "  enabled: true,\n" +
                    "  scopes: {\n" +
                    "    'org=*|acct=*|tenant=*|seg=*|owner=*': {\n" +
                    "      requiresServer: false,\n" +
                    "      matrix: {\n" +
                    "        security: { userProfile: { update: { effect: 'ALLOW', priority: 0, finalRule: true, rule: 'test' } } }\n" +
                    "      }\n" +
                    "    }\n" +
                    "  },\n" +
                    "  requestedScope: 'org=*|acct=*|tenant=*|seg=*|owner=*',\n" +
                    "  requestedFallback: ['org=*|acct=*|tenant=*|seg=*|owner=*']\n" +
                    "};\n" +
                    "ACLClient.decide(snapshot, null, 'security', 'userProfile', 'view');";

            Value result = ctx.eval("js", js);
            String decision = result.asString();
            assertEquals("DENY", decision, "Expected DENY when no matching outcome exists");
        }
    }

    @Test
    public void testScopeKeyAndFallbackChain() throws Exception {
        String lib = loadClientJs();
        try (Context ctx = newJsContext()) {
            ctx.eval("js", lib);
            String js = "" +
                    "var dd = { orgRefName: 'acme', accountNumber: 'A1', tenantId: 't-1', dataSegment: 0, ownerId: 'u1' };\n" +
                    "var key = ACLClient.scopeKeyFromDataDomain(dd);\n" +
                    "var chain = ACLClient.buildFallbackChain(key);\n" +
                    "({ key: key, chain: chain });";
            Value obj = ctx.eval("js", js);
            assertEquals("org=acme|acct=A1|tenant=t-1|seg=0|owner=u1", obj.getMember("key").asString());
            Value arr = obj.getMember("chain");
            assertTrue(arr.hasArrayElements());
            // First fallback should drop owner
            assertEquals("org=acme|acct=A1|tenant=t-1|seg=0|owner=*", arr.getArrayElement(0).asString());
            // Last should be global
            long lastIdx = arr.getArraySize() - 1;
            assertEquals("org=*|acct=*|tenant=*|seg=*|owner=*", arr.getArrayElement(lastIdx).asString());
        }
    }
}
