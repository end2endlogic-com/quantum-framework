package com.e2eq.framework.util.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fails the framework build when code reads a claim that identity-only tokens remove
 * (IDENTITY_ONLY_TOKENS_DESIGN.md step 4) beyond what security/token-claim-readers.allowlist
 * permits. The allowlist names the rollout step that removes each existing reader.
 */
class TokenClaimReaderGuardTest {

    private static final Path REACTOR_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path ALLOWLIST = REACTOR_ROOT.resolve("security/token-claim-readers.allowlist");

    @Test
    void noNewTokenClaimReadersInTheFramework() {
        assertTrue(Files.exists(ALLOWLIST), "missing " + ALLOWLIST);
        TokenClaimReaderScan.assertNoNewReaders(REACTOR_ROOT, ALLOWLIST);
    }

    @Test
    void countsLiteralsAccessorsAndComputedClaimNames() {
        Map<String, List<Integer>> hits = TokenClaimReaderScan.scanText(String.join("\n",
                "import org.eclipse.microprofile.jwt.JsonWebToken;",
                "class A {",
                "  Object a(JsonWebToken jwt, String n) {",
                "    // jwt.getClaim(\"realm\") in a comment is not a read",
                "    jwt.getClaim(\"realm\"); jwt.getGroups(); jwt.getAudience();",
                "    jwt.getClaim(n); jwt.getClaim(\"sub\"); log(\"realm is \" + x);",
                "    return claim(jwt, \"tenantId\");",
                "  }",
                "}"));
        assertEquals(List.of(5), hits.get("realm"));
        assertEquals(List.of(5), hits.get("groups"));
        assertEquals(List.of(5), hits.get("aud"));
        assertEquals(List.of(6), hits.get("dynamic"));
        assertEquals(List.of(7), hits.get("tenantId"));
        assertEquals(5, hits.size());
    }

    @Test
    void countsReadsInFilesThatDecodeATokenByHand() {
        Map<String, List<Integer>> hits = TokenClaimReaderScan.scanText(
                "byte[] b = Base64.getUrlDecoder().decode(parts[1]);\n"
                        + "String t = json.get(\"tenantId\").asText();");
        assertEquals(Map.of("tenantId", List.of(2)), hits);
    }

    @Test
    void commentsConcatenationAndLineBreaksDoNotHideReads() {
        String jwt = "import org.eclipse.microprofile.jwt.JsonWebToken;\n";
        assertEquals(Map.of("realm", List.of(2)),
                TokenClaimReaderScan.scanText(jwt + "/* why */ jwt.getClaim(\"realm\");"));
        assertTrue(TokenClaimReaderScan.scanText(jwt + "int x = 1; // jwt.getClaim(\"realm\")").isEmpty());
        assertTrue(TokenClaimReaderScan.scanText(jwt + "String u = \"https://x\"; // \"realm\"").isEmpty());
        assertEquals(Map.of("dynamic", List.of(2)),
                TokenClaimReaderScan.scanText(jwt + "jwt.getClaim(\"re\" + \"alm\");"));
        assertTrue(TokenClaimReaderScan.scanText(jwt + "jwt.getClaim(\n  \"sub\");").isEmpty());
        assertEquals(Map.of("groups", List.of(2)), TokenClaimReaderScan.scanText(jwt + "jwt.getGroups(\n);"));
        assertTrue(TokenClaimReaderScan.scanText(jwt + "jwt.getClaim(Claims.sub);").isEmpty());
        assertEquals(Map.of("realm", List.of(1)),
                TokenClaimReaderScan.scanText("var t = p.token(); t.getClaim (\"realm\");"));
        assertEquals(Map.of("realm", List.of(2)), TokenClaimReaderScan.scanText(
                "import io.quarkus.security.identity.SecurityIdentity;\nObject r = identity.getAttribute(\"realm\");"));
    }

    @Test
    void duplicateAllowlistEntriesFail(@TempDir Path root) throws IOException {
        Path allowlist = root.resolve("allow");
        Files.writeString(allowlist, "a.java | realm | 0 | must-move | TODO\na.java | realm | 9 | must-move | TODO\n");
        assertThrows(IllegalArgumentException.class, () -> TokenClaimReaderScan.loadAllowlist(allowlist));
        assertThrows(IllegalArgumentException.class, () -> TokenClaimReaderScan.scan(root.resolve("absent")));
    }

    @Test
    void ignoresFilesThatNameNoTokenApi() {
        assertTrue(TokenClaimReaderScan.scanText("record R(String realm) { String k = \"realm\"; }").isEmpty());
    }

    @Test
    void failsOnANewReaderAndPassesWithinTheAllowlist(@TempDir Path root) throws IOException {
        Path src = root.resolve("app/src/main/java/A.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "import org.eclipse.microprofile.jwt.JsonWebToken;\n"
                + "class A { Object a(JsonWebToken t) { return t.getClaim(\"azp\"); } }\n");
        Path testSrc = root.resolve("app/src/test/java/ATest.java");
        Files.createDirectories(testSrc.getParent());
        Files.writeString(testSrc, "class ATest { Object a(org.eclipse.microprofile.jwt.JsonWebToken t) {"
                + " return t.getClaim(\"realm\"); } }\n");
        Path allowlist = root.resolve("allow");

        List<String> failures = TokenClaimReaderScan.newReaders(root, allowlist);
        assertEquals(1, failures.size(), failures.toString());
        assertTrue(failures.get(0).startsWith("app/src/main/java/A.java: reads token claim 'azp' 1x (allowed 0)"));

        Files.writeString(allowlist,
                "app/src/main/java/A.java | azp | 1 | must-move | TODO step 4: read the application from config\n");
        assertTrue(TokenClaimReaderScan.newReaders(root, allowlist).isEmpty());
    }
}
