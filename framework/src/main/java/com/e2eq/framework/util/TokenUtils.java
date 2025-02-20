package com.e2eq.framework.util;

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

import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import io.smallrye.jwt.build.Jwt;
import io.smallrye.jwt.build.JwtClaimsBuilder;

import jakarta.validation.Valid;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.NotFoundException;


/**
 *
 * @author mingardia
 */
public class TokenUtils {

	public static final String REFRESH_SCOPE = "refreshToken";
	public static final String AUTH_SCOPE = "authToken";
	public static final String AUDIENCE = "b2bi-api-client";
	public static final int REFRESH_ADDITIONAL_DURATION_SECONDS= 10;

	// add builder class for the generateUserToken method.


	public static String generateUserToken ( String userId,
											 Set<String> groups,
											 long durationInSeconds,
											 String issuer) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {

		Objects.requireNonNull(userId, "UserId cannot be null");
		Objects.requireNonNull(issuer, "Issuer cannot be null");

		if (durationInSeconds <= REFRESH_ADDITIONAL_DURATION_SECONDS) {
			throw new ValidationException("Duration must be greater than" + REFRESH_ADDITIONAL_DURATION_SECONDS + " seconds");
		}

		String privateKeyLocation = "privateKey.pem";
		PrivateKey privateKey = readPrivateKey(privateKeyLocation);
		
		JwtClaimsBuilder claimsBuilder = Jwt.claims();
		long currentTimeInSecs = currentTimeInSecs();


		claimsBuilder.issuer(issuer);
		claimsBuilder.subject(userId);
		claimsBuilder.issuedAt(currentTimeInSecs);
		claimsBuilder.audience(AUDIENCE);
		claimsBuilder.expiresAt(currentTimeInSecs + durationInSeconds);
		claimsBuilder.groups(groups);
		claimsBuilder.claim("userName", userId);
		claimsBuilder.claim("scope", AUTH_SCOPE);

		/*Map<String, String> area2Realm = new HashMap<>();
		area2Realm.put("security", "system-com");
		area2Realm.put("signup", "system-com"); */


		return claimsBuilder.jws().keyId(privateKeyLocation).sign(privateKey);
	}

	public static String generateRefreshToken(String username,  long durationInSeconds, String issuer) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {



		String privateKeyLocation = "privateKey.pem";
		PrivateKey privateKey = readPrivateKey(privateKeyLocation);
		JwtClaimsBuilder claimsBuilder = Jwt.claims();
		long currentTimeInSecs = currentTimeInSecs();
		claimsBuilder.issuer(issuer);
		claimsBuilder.subject(username);
		claimsBuilder.issuedAt(currentTimeInSecs);
		claimsBuilder.audience("b2bi-api-client-refresh");
		claimsBuilder.expiresAt(currentTimeInSecs + durationInSeconds + REFRESH_ADDITIONAL_DURATION_SECONDS);
		claimsBuilder.claim("username", username );
		/* claimsBuilder.claim("tenantId", credentialUserIdPassword.getTenantId());
		claimsBuilder.claim("defaultRealm", credentialUserIdPassword.getDefaultRealm() );

		claimsBuilder.claim("orgRefName", credentialUserIdPassword.getOrgRefName());
		claimsBuilder.claim("accountId", credentialUserIdPassword.getAccountId());

		Map<String, String> area2Realm = new HashMap<>();
		area2Realm.put("security", "system-com");
		area2Realm.put("signup", "system-com");
		if (credentialUserIdPassword.getArea2RealmOverrides() != null &&
				!credentialUserIdPassword.getArea2RealmOverrides().isEmpty()  ) {
			area2Realm.putAll(credentialUserIdPassword.getArea2RealmOverrides());
		}
		claimsBuilder.claim("realmOverrides", area2Realm); */
		claimsBuilder.claim("scope", REFRESH_SCOPE);
		return claimsBuilder.jws().keyId(privateKeyLocation).sign(privateKey);
	}

	public static PrivateKey readPrivateKey(final String pemResName) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		try (InputStream contentIS = loader.getResourceAsStream(pemResName)) {
			if (contentIS == null ) {
				throw new IOException("Could not find Private Key with ResourceName:" + pemResName);
			}
			byte[] tmp = new byte[4096];
			int length = contentIS.read(tmp);
			if (length == 0) {
				throw new IOException("Could not find private key");
			}
			return decodePrivateKey(new String(tmp, 0, length, StandardCharsets.UTF_8));
		}
	}

	public static PublicKey readPublicKey(String pemResName) throws Exception {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		try (InputStream contentIS = loader.getResourceAsStream(pemResName)) {
			if (contentIS == null ) {
				throw new Exception("Could not find Public Key with ResourceName:" + pemResName);
			}
			byte[] tmp = new byte[4096];
			assert contentIS != null;
			int length = contentIS.read(tmp);
			return decodePublicKey(new String(tmp, 0, length));
		}
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

	public static int currentTimeInSecs() {
		long currentTimeMS = System.currentTimeMillis();
		return (int) (currentTimeMS / 1000);
	}

}
