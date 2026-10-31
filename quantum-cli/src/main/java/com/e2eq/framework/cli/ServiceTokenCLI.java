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
import java.util.HashSet;
import java.util.Set;

@TopCommand
@Command(name = "generate-token", mixinStandardHelpOptions = true,
         description = "Generates a Service Token for the Quantum Framework.")
public class ServiceTokenCLI implements java.util.concurrent.Callable<Integer> {

    @Option(names = {"-u", "--user"}, description = "User ID", required = true)
    String userId;

    @Option(names = {"-p", "--password"}, description = "Password", required = true, interactive = true)
    String password;

    @Option(names = {"-r", "--roles"}, description = "Comma-separated list of roles", split = ",")
    Set<String> roles = new HashSet<>();

    @Option(names = {"-e", "--expires"}, description = "Expiration in seconds (-1 for no expiration)", defaultValue = "3600")
    long expirationSeconds;

    @Option(names = {"-d", "--description"}, description = "Description for the service token")
    String description;

    @Option(names = {"--realm"}, description = "Realm to stamp into the generated service token")
    String realm;

    @Option(names = {"--audience", "--audiences"}, description = "Comma-separated application audiences", split = ",")
    Set<String> audiences = new HashSet<>();

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
            ServiceTokenRequest mint = new ServiceTokenRequest();
            mint.setRoles(new java.util.ArrayList<>(roles));
            mint.setExpirationSeconds(expirationSeconds == -1 ? null : expirationSeconds);
            mint.setDescription(description);
            mint.setRealm(realm);
            mint.setAudiences(new java.util.ArrayList<>(audiences));
            ServiceTokenResponse result = client.mintServiceToken("Bearer " + login.getAccessToken(), mint);
            if (result == null || result.getToken() == null || result.getToken().isBlank()) {
                System.err.println("Service token response did not contain a token.");
                return 1;
            }
            System.out.println(result.getToken());
            return 0;
        } catch (Exception failure) {
            // Provider exception bodies may contain credentials; never dump them to the terminal.
            System.err.println("Authentication or service-token request failed.");
            return 1;
        }
    }
}
