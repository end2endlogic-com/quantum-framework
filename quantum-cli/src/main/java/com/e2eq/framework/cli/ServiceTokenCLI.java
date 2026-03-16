package com.e2eq.framework.cli;

import com.e2eq.framework.rest.models.AuthRequest;
import com.e2eq.framework.rest.models.AuthResponse;
import com.e2eq.framework.rest.requests.ServiceTokenRequest;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@TopCommand
@Command(name = "generate-token", mixinStandardHelpOptions = true,
         description = "Generates a Service Token for the Quantum Framework.")
public class ServiceTokenCLI implements Runnable {

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

    @Option(names = {"--url"}, description = "Base URL of the Quantum API", defaultValue = "http://localhost:8080")
    String baseUrl;

    @Override
    public void run() {
        try {
            // 1. Build REST Client
            AuthClient authClient = RestClientBuilder.newBuilder()
                    .baseUri(new URI(baseUrl))
                    .build(AuthClient.class);

            // 2. Login
            System.out.println("Authenticating user: " + userId);
            AuthRequest authRequest = new AuthRequest(userId, password);
            AuthResponse authResponse = authClient.login(authRequest);

            if (authResponse == null || authResponse.getAccess_token() == null) {
                System.err.println("Failed to authenticate. No access token received.");
                System.exit(1);
            }

            String bearerToken = "Bearer " + authResponse.getAccess_token();

            // 2. Generate Service Token
            System.out.println("Generating service token...");
            
            // Handle -1 as non-expiring (100 years in seconds is ~3 billion, but the API might handle null)
            // Based on ServiceTokenRequest JavaDoc: null for non-expiring (100-year token)
            Long apiExpiration = (expirationSeconds == -1) ? null : expirationSeconds;

            ServiceTokenRequest tokenRequest = new ServiceTokenRequest(roles, apiExpiration, description);
            Map<String, Object> result = authClient.generateServiceToken(bearerToken, tokenRequest);

            if (result != null && result.containsKey("accessToken")) {
                System.out.println("\nService Token Generated Successfully:");
                System.out.println("------------------------------------");
                System.out.println(result.get("accessToken"));
                System.out.println("------------------------------------");
                System.out.println("Subject: " + result.get("subject"));
                System.out.println("Issuer: " + result.get("issuer"));
            } else {
                System.err.println("Failed to generate service token.");
                if (result != null && result.containsKey("error")) {
                    System.err.println("Error: " + result.get("error"));
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("Cause: " + e.getCause().getMessage());
            }
            System.exit(1);
        }
    }
}
