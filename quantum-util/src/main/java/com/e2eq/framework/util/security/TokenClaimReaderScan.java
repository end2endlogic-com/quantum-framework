package com.e2eq.framework.util.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guard that keeps new code from reading role, realm or tenancy claims from a token.
 *
 * <p>Identity-only tokens (docs/design/IDENTITY_ONLY_TOKENS_DESIGN.md) carry {@code iss},
 * {@code sub}, {@code userId}, {@code iat}, {@code exp}, {@code jti} and {@code token_type}.
 * Roles, realm, tenant and application context come from {@code PrincipalContextResolver}.
 * Step 4 of the rollout stops minting {@link #REMOVED_CLAIMS}; every reader of them breaks then.
 *
 * <p>The scan is plain source scanning of claim names over {@code .java} and {@code .kt} main
 * sources (test sources and generated output are skipped). In a file that names a JWT API
 * ({@code JsonWebToken}, {@code getClaim(} ...) or decodes one by hand ({@code getUrlDecoder}) it counts exact string literals of a removed
 * claim, {@code getGroups()}, {@code getAudience()} and {@code Claims.groups|aud|azp}, plus
 * {@code getClaim(<computed name>)} as the pseudo-claim {@code dynamic}. Counts per
 * (file, claim) must not exceed the allowlist, so the readers that exist today stay green and a
 * new one fails the build. The same rules and allowlist format are used by
 * {@code check_token_claim_readers.py} for Python and TypeScript.
 *
 * <p>Allowlist lines: {@code <path relative to root> | <claim> | <max count> | <class> | <TODO>}.
 * Classes: {@code resolver}, {@code issuer}, {@code remote-gated}, {@code must-move},
 * {@code not-a-claim}, {@code unclassified}. Applications run it from a test:
 *
 * <pre>{@code
 * TokenClaimReaderScan.assertNoNewReaders(Path.of("."), Path.of("security/token-claim-readers.allowlist"));
 * }</pre>
 */
public final class TokenClaimReaderScan {

    /** Claims that identity-only tokens no longer carry. */
    public static final List<String> REMOVED_CLAIMS = List.of(
            "groups", "roles", "realm", "realmRegEx", "tenant", "tenantId",
            "orgRefName", "accountNum", "aud", "azp");

    /** Allowlist-only pseudo-claim for {@code getClaim(<computed name>)}. */
    public static final String DYNAMIC = "dynamic";

    public static final Set<String> CLASSES = Set.of(
            "resolver", "issuer", "remote-gated", "must-move", "not-a-claim", "unclassified");

    private static final Set<String> SKIP_DIRS = Set.of(
            "node_modules", "venv", "dist", "build", "target", "coverage", "__pycache__",
            "test", "tests", "__tests__", "testing", "generated");
    private static final Pattern TEST_FILE = Pattern.compile("(Test|Tests|IT)\\.(java|kt)$");
    private static final List<String> TOKEN_MARKERS = List.of(
            "JsonWebToken", "JwtClaims", "JWTClaimsSet", "DecodedJWT", "io.jsonwebtoken",
            "getClaim(", "org.eclipse.microprofile.jwt", "Jwt.claims", "Jwt.upn", "Jwt.subject",
            "getUrlDecoder");
    private static final String NAMES = String.join("|", REMOVED_CLAIMS);
    private static final Pattern LITERAL =
            Pattern.compile("(?<![\\w$.])([\"'])(" + NAMES + ")\\1");
    private static final Pattern GET_GROUPS = Pattern.compile("\\bgetGroups\\s*\\(\\s*\\)");
    private static final Pattern GET_AUDIENCE = Pattern.compile("\\bgetAudience\\s*\\(\\s*\\)");
    private static final Pattern CLAIMS_ENUM = Pattern.compile("\\bClaims\\.(groups|aud|azp)\\b");
    private static final Pattern DYNAMIC_CLAIM =
            Pattern.compile("\\bgetClaim\\s*\\(\\s*(?![\"'\\s]|Claims\\.)");
    private static final Pattern COMMENT_LINE = Pattern.compile("^\\s*(//|#|\\*|/\\*)");

    private TokenClaimReaderScan() {
    }

    /** One (file, claim) read count with its line numbers. */
    public record Reader(String path, String claim, List<Integer> lines) {
        public int count() {
            return lines.size();
        }
    }

    /** One allowlist line. */
    public record Entry(String path, String claim, int count, String classification, String note) {
    }

    /** Claim to line numbers of reads in one JVM source text; empty when it names no JWT API. */
    public static Map<String, List<Integer>> scanText(String text) {
        Map<String, List<Integer>> hits = new TreeMap<>();
        if (TOKEN_MARKERS.stream().noneMatch(text::contains)) {
            return hits;
        }
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (COMMENT_LINE.matcher(line).find()) {
                continue;
            }
            int lineno = i + 1;
            Matcher m = LITERAL.matcher(line);
            while (m.find()) {
                hits.computeIfAbsent(m.group(2), k -> new ArrayList<>()).add(lineno);
            }
            count(hits, GET_GROUPS.matcher(line), "groups", lineno);
            count(hits, GET_AUDIENCE.matcher(line), "aud", lineno);
            Matcher e = CLAIMS_ENUM.matcher(line);
            while (e.find()) {
                hits.computeIfAbsent(e.group(1), k -> new ArrayList<>()).add(lineno);
            }
            count(hits, DYNAMIC_CLAIM.matcher(line), DYNAMIC, lineno);
        }
        return hits;
    }

    private static void count(Map<String, List<Integer>> hits, Matcher m, String claim, int lineno) {
        while (m.find()) {
            hits.computeIfAbsent(claim, k -> new ArrayList<>()).add(lineno);
        }
    }

    /** Every claim reader under {@code root}, in path order. */
    public static List<Reader> scan(Path root) {
        Path base = root.toAbsolutePath().normalize();
        List<Reader> readers = new ArrayList<>();
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(base) && (SKIP_DIRS.contains(name) || name.startsWith("."))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = file.getFileName().toString();
                    if (!(name.endsWith(".java") || name.endsWith(".kt")) || TEST_FILE.matcher(name).find()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String text;
                    try {
                        text = Files.readString(file, StandardCharsets.UTF_8);
                    } catch (MalformedInputException notUtf8) {
                        return FileVisitResult.CONTINUE;
                    }
                    String rel = base.relativize(file).toString().replace('\\', '/');
                    scanText(text).forEach((claim, lines) -> readers.add(new Reader(rel, claim, List.copyOf(lines))));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        readers.sort((a, b) -> a.path().equals(b.path())
                ? a.claim().compareTo(b.claim()) : a.path().compareTo(b.path()));
        return readers;
    }

    /** Parses an allowlist; a malformed line fails loudly. A missing file is an empty allowlist. */
    public static Map<String, Entry> loadAllowlist(Path allowlist) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        if (!Files.exists(allowlist)) {
            return entries;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(allowlist, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split(" \\| ", 5);
            String where = allowlist + ":" + (i + 1);
            if (parts.length != 5 || !parts[2].strip().matches("\\d+")
                    || !CLASSES.contains(parts[3].strip())) {
                throw new IllegalArgumentException(where + ": malformed allowlist entry: " + line);
            }
            String claim = parts[1].strip();
            if (!REMOVED_CLAIMS.contains(claim) && !DYNAMIC.equals(claim)) {
                throw new IllegalArgumentException(where + ": unknown claim " + claim);
            }
            if (parts[4].isBlank()) {
                throw new IllegalArgumentException(where + ": every entry needs a TODO or reason");
            }
            Entry entry = new Entry(parts[0].strip(), claim, Integer.parseInt(parts[2].strip()),
                    parts[3].strip(), parts[4].strip());
            entries.put(entry.path() + "|" + claim, entry);
        }
        return entries;
    }

    /** Readers whose count exceeds the allowlist, rendered as failure messages. */
    public static List<String> newReaders(Path root, Path allowlist) {
        Map<String, Entry> allow = loadAllowlist(allowlist);
        List<String> failures = new ArrayList<>();
        for (Reader r : scan(root)) {
            Entry entry = allow.get(r.path() + "|" + r.claim());
            int limit = entry == null ? 0 : entry.count();
            if (r.count() > limit) {
                failures.add(r.path() + ": reads token claim '" + r.claim() + "' " + r.count()
                        + "x (allowed " + limit + ") at lines " + r.lines()
                        + " -- never read role/realm/tenant/aud/azp claims from a token;"
                        + " resolve through PrincipalContextResolver");
            }
        }
        return failures;
    }

    /** Throws {@link AssertionError} listing every new reader. */
    public static void assertNoNewReaders(Path root, Path allowlist) {
        List<String> failures = newReaders(root, allowlist);
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " new token claim reader(s); see "
                    + "IDENTITY_ONLY_TOKENS_DESIGN.md:\n" + String.join("\n", failures));
        }
    }
}
