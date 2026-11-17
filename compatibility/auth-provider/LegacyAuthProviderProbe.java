import com.e2eq.framework.model.auth.AuthProvider;
import com.e2eq.framework.model.auth.RoleAssignment;
import io.quarkus.security.identity.SecurityIdentity;
import java.util.List;
import java.util.Set;

/** Compiled against the released API, then run unchanged against the candidate. */
public final class LegacyAuthProviderProbe implements AuthProvider {
    public SecurityIdentity validateAccessToken(String token) { return null; }
    public String getName() { return "legacy-fixture"; }
    public LoginResponse login(String user, String password) {
        return new LoginResponse(false, new LoginNegativeResponse(
                user, 401, 401, "Denied", "Unauthorized", "fixture", "realm"));
    }
    public LoginResponse refreshTokens(String token) { return login("fixture", ""); }

    public static void main(String[] args) throws Exception {
        AuthProvider provider = new LegacyAuthProviderProbe();
        require(provider.getIssuer() == null, "legacy issuer default");
        require(!provider.login("alice", "unused").authenticated(), "two-argument login");
        require(!provider.login("alice", "unused", "app").authenticated(), "application overload");
        require(!provider.login("alice", "unused", "app", null).authenticated(), "realm overload");
        try {
            provider.login("alice", "unused", "app", "other-realm");
            throw new AssertionError("legacy provider silently accepted a scoped realm");
        } catch (UnsupportedOperationException expected) { }
        var simple = new LoginPositiveResponse("alice", null, Set.of("user"), "a", "r", 100L, null, "realm");
        var provenance = new LoginPositiveResponse("alice", null, Set.of("user"),
                List.<RoleAssignment>of(), "a", "r", 100L, null, "realm");
        var scoped = new LoginPositiveResponse("alice", null, Set.of("user"),
                List.<RoleAssignment>of(), "a", "r", 100L, null, "realm", Set.of("app"), "app");
        require(simple.roleAssignments().size() == 1, "legacy role provenance");
        require(provenance.audiences() == null, "absent legacy audiences");
        require("app".equals(scoped.activeApplication()), "scoped constructor");
        require(new LoginResponse(true, scoped).positiveResponse() == scoped, "positive constructor");
        require(!provider.refreshTokens("unused").authenticated(), "refresh signature");
        System.out.println("PASS: released provider binary and login constructors execute on candidate");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
