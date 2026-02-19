package com.e2eq.framework.model.auth.provider.jwtToken;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

import io.smallrye.jwt.build.Jwt;
import io.smallrye.jwt.build.JwtClaimsBuilder;

import jakarta.validation.ValidationException;


/**
 * Utility class for token generation and validation.
 *
 * Key locations are configurable via {@link #configure(String, String)}.
 * Supported path prefixes:
 * <ul>
 *   <li>{@code classpath:} — load from the classpath (default)</li>
 *   <li>{@code file:} — load from the filesystem</li>
 *   <li>No prefix — treated as a classpath resource name (backward compatible)</li>
 * </ul>
 *
 * @author mingardia
 */
public class TokenUtils {

        public static final String REFRESH_SCOPE = "refreshToken";
        public static final String AUTH_SCOPE = "authToken";
        public static final String AUDIENCE = "b2bi-api-client";
        public static final int REFRESH_ADDITIONAL_DURATION_SECONDS= 10;

        private static final String DEFAULT_PRIVATE_KEY_LOCATION = "privateKey.pem";
        private static final String DEFAULT_PUBLIC_KEY_LOCATION = "publicKey.pem";

        private static volatile String privateKeyLocation = DEFAULT_PRIVATE_KEY_LOCATION;
        private static volatile String publicKeyLocation = DEFAULT_PUBLIC_KEY_LOCATION;

        private static volatile PrivateKey cachedPrivateKey;
        private static volatile PublicKey cachedPublicKey;
        private static final Object PRIVATE_KEY_LOCK = new Object();
        private static final Object PUBLIC_KEY_LOCK = new Object();

        /**
         * Configure the key file locations. Call this at startup (e.g., from a CDI {@code @Startup} bean)
         * to override the default classpath locations.
         *
         * @param privateKeyLoc path to the private key (e.g., "file:/opt/keys/private.pem" or "classpath:myKey.pem")
         * @param publicKeyLoc  path to the public key (e.g., "file:/opt/keys/public.pem" or "classpath:myKey.pub")
         */
        public static void configure(String privateKeyLoc, String publicKeyLoc) {
                synchronized (PRIVATE_KEY_LOCK) {
                        if (privateKeyLoc != null && !privateKeyLoc.isBlank()) {
                                if (!privateKeyLoc.equals(privateKeyLocation)) {
                                        cachedPrivateKey = null; // invalidate cache when location changes
                                }
                                privateKeyLocation = privateKeyLoc;
                        }
                }
                synchronized (PUBLIC_KEY_LOCK) {
                        if (publicKeyLoc != null && !publicKeyLoc.isBlank()) {
                                if (!publicKeyLoc.equals(publicKeyLocation)) {
                                        cachedPublicKey = null; // invalidate cache when location changes
                                }
                                publicKeyLocation = publicKeyLoc;
                        }
                }
        }

        /**
         * Returns the currently configured private key location.
         */
        public static String getPrivateKeyLocation() {
                return privateKeyLocation;
        }

        /**
         * Returns the currently configured public key location.
         */
        public static String getPublicKeyLocation() {
                return publicKeyLocation;
        }


	public static String generateUserToken ( String subject,
											 Set<String> groups,
											 long expiresAt,
											 String issuer) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {

		Objects.requireNonNull(subject, "subject cannot be null");
		Objects.requireNonNull(issuer, "Issuer cannot be null");

		if (expiresAt <= REFRESH_ADDITIONAL_DURATION_SECONDS) {
			throw new ValidationException("Duration must be greater than" + REFRESH_ADDITIONAL_DURATION_SECONDS + " seconds");
		}

                PrivateKey privateKey = cachedPrivateKey != null ? cachedPrivateKey : readPrivateKey(privateKeyLocation);

		JwtClaimsBuilder claimsBuilder = Jwt.claims();
		long currentTimeInSecs = currentTimeInSecs();


		claimsBuilder.issuer(issuer);
		claimsBuilder.subject(subject);
		claimsBuilder.issuedAt(currentTimeInSecs);
		claimsBuilder.audience(AUDIENCE);
		claimsBuilder.expiresAt(expiresAt);
		claimsBuilder.groups(groups);
		claimsBuilder.claim("scope", AUTH_SCOPE);

		return claimsBuilder.jws().keyId(privateKeyLocation).sign(privateKey);
	}

	public static String generateRefreshToken(String subject,  long durationInSeconds, String issuer) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {

                PrivateKey privateKey = cachedPrivateKey != null ? cachedPrivateKey : readPrivateKey(privateKeyLocation);
		JwtClaimsBuilder claimsBuilder = Jwt.claims();
		long currentTimeInSecs = currentTimeInSecs();
		claimsBuilder.issuer(issuer);
		claimsBuilder.subject(subject);
		claimsBuilder.issuedAt(currentTimeInSecs);
		claimsBuilder.audience("b2bi-api-client-refresh");
		claimsBuilder.expiresAt(currentTimeInSecs + durationInSeconds + REFRESH_ADDITIONAL_DURATION_SECONDS);
		claimsBuilder.claim("scope", REFRESH_SCOPE);
		return claimsBuilder.jws().keyId(privateKeyLocation).sign(privateKey);
	}

        public static PrivateKey readPrivateKey(final String pemResName) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
                if (cachedPrivateKey == null) {
                        synchronized (PRIVATE_KEY_LOCK) {
                                if (cachedPrivateKey == null) {
                                        byte[] keyBytes = readKeyBytes(pemResName);
                                        cachedPrivateKey = decodePrivateKey(new String(keyBytes, StandardCharsets.UTF_8));
                                }
                        }
                }
                return cachedPrivateKey;
        }

        public static PublicKey readPublicKey(String pemResName) throws Exception {
                if (cachedPublicKey == null) {
                        synchronized (PUBLIC_KEY_LOCK) {
                                if (cachedPublicKey == null) {
                                        byte[] keyBytes = readKeyBytes(pemResName);
                                        cachedPublicKey = decodePublicKey(new String(keyBytes, StandardCharsets.UTF_8));
                                }
                        }
                }
                return cachedPublicKey;
        }

        /**
         * Reads key bytes from either a filesystem path (prefixed with "file:") or a classpath resource.
         * Paths prefixed with "classpath:" have that prefix stripped and are loaded from the classpath.
         * Paths with no recognized prefix are loaded from the classpath (backward compatible).
         */
        static byte[] readKeyBytes(String location) throws IOException {
                if (location.startsWith("file:")) {
                        String filePath = location.substring("file:".length());
                        try (InputStream is = new FileInputStream(filePath)) {
                                return readAllBytes(is, location);
                        }
                } else {
                        // Strip optional "classpath:" prefix
                        String resourceName = location.startsWith("classpath:")
                                ? location.substring("classpath:".length())
                                : location;
                        ClassLoader loader = Thread.currentThread().getContextClassLoader();
                        try (InputStream is = loader.getResourceAsStream(resourceName)) {
                                if (is == null) {
                                        throw new IOException("Could not find key resource: " + location);
                                }
                                return readAllBytes(is, location);
                        }
                }
        }

        private static byte[] readAllBytes(InputStream is, String location) throws IOException {
                byte[] tmp = new byte[4096];
                int length = is.read(tmp);
                if (length <= 0) {
                        throw new IOException("Key file is empty: " + location);
                }
                return java.util.Arrays.copyOf(tmp, length);
        }

	public static PublicKey decodePublicKey(String pemEncoded) throws Exception {
		pemEncoded = removeBeginEnd(pemEncoded);
		byte[] encodedBytes = Base64.getDecoder().decode(pemEncoded);

		X509EncodedKeySpec spec = new X509EncodedKeySpec(encodedBytes);
		KeyFactory kf = KeyFactory.getInstance("RSA");
		return kf.generatePublic(spec);
	}

	public static PrivateKey decodePrivateKey(final String pemEncoded) throws NoSuchAlgorithmException, InvalidKeySpecException {
		byte[] encodedBytes = toEncodedBytes(pemEncoded);

		PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encodedBytes);
		KeyFactory kf = KeyFactory.getInstance("RSA");
		return kf.generatePrivate(keySpec);
	}

   public static byte[] toEncodedBytes(final String pemEncoded) {
		final String normalizedPem = removeBeginEnd(pemEncoded);
		return Base64.getDecoder().decode(normalizedPem);
	}

	public static String removeBeginEnd(String pem) {
		pem = pem.replaceAll("-----BEGIN (.*)-----", "");
		pem = pem.replaceAll("-----END (.*)----", "");
		pem = pem.replaceAll("\r\n", "");
		pem = pem.replaceAll("\n", "");
		return pem.trim();
	}

	public static long expiresAt(long durationInSeconds) {
		return currentTimeInSecs() + durationInSeconds  + REFRESH_ADDITIONAL_DURATION_SECONDS;
	}

	public static int currentTimeInSecs() {
		long currentTimeMS = System.currentTimeMillis();
		return (int) (currentTimeMS / 1000);
	}

}
