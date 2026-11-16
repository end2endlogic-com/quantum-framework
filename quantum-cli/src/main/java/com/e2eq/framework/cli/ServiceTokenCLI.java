package com.e2eq.framework.cli;

import com.e2eq.framework.cli.generated.model.LoginRequest;
import com.e2eq.framework.cli.generated.model.LoginResponse;
import com.e2eq.framework.cli.generated.model.ServiceTokenRequest;
import com.e2eq.framework.cli.generated.model.ServiceTokenResponse;
import com.e2eq.framework.cli.generated.api.AuthEndpoint;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;

@TopCommand
@Command(name = "generate-token", mixinStandardHelpOptions = true,
         description = "Issues an identity-only service token (Auth contract 4.11.0). Run it as the realm admin.")
public class ServiceTokenCLI implements java.util.concurrent.Callable<Integer> {

    @Option(names = {"-u", "--user"}, description = "User ID", required = true)
    String userId;

    @Option(names = {"-p", "--password"}, description = "Password", required = true, interactive = true)
    String password;

    @Option(names = {"-k", "--kind"}, description = "application (runs as itself) or delegating (may act for "
            + "identities of its realm). The token carries no roles: grant the service's userId what it needs.",
            defaultValue = "application")
    String kind;

    @Option(names = {"-d", "--description"}, description = "Description for the service token")
    String description;

    @Option(names = {"--realm"}, description = "Realm to issue in (sent as X-Realm; the caller must administer it)")
    String realm;

    @Option(names = {"--url"}, description = "Base URL of the Quantum API", defaultValue = "http://localhost:8080")
    String baseUrl;

    AuthEndpoint createClient() throws java.net.URISyntaxException {
        return RestClientBuilder.newBuilder().baseUri(new URI(baseUrl)).build(AuthEndpoint.class);
    }

    @Override
    public Integer call() {
        try {
            AuthEndpoint client = createClient();
            LoginRequest request = new LoginRequest();
            request.setUserId(userId);
            request.setPassword(password);
            LoginResponse login = client.login(request);
            if (login == null || login.getAccessToken() == null || login.getAccessToken().isBlank()) {
                System.err.println("Authentication failed: no access token received.");
                return 1;
            }
            ServiceTokenRequest issue = new ServiceTokenRequest();
            issue.setKind(kind);
            if (description != null) {
                issue.setDescription(description);
            }
            ServiceTokenResponse result = client.mintServiceToken("Bearer " + login.getAccessToken(), realm, issue);
            if (result == null || result.getToken() == null || result.getToken().isBlank()) {
                System.err.println("Service token response did not contain a token.");
                return 1;
            }
            // The token grants nothing until this identity is granted access (Access page or a binding).
            System.err.println("Service userId to grant: " + result.getUserId());
            System.out.println(result.getToken());
            return 0;
        } catch (Exception failure) {
            // Provider exception bodies may contain credentials; never dump them to the terminal.
            System.err.println("Authentication or service-token request failed.");
            return 1;
        }
    }
}
