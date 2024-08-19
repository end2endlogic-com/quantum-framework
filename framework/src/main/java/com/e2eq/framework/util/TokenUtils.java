package com.e2eq.framework.util;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

import io.smallrye.jwt.build.Jwt;
import io.smallrye.jwt.build.JwtClaimsBuilder;

import jakarta.validation.ValidationException;


/**
 *
 * @author ard333
 */
public class TokenUtils {

	public static final String REFRESH_SCOPE = "refreshToken";
	public static final String AUTH_SCOPE = "authToken";
	public static final String AUDIENCE = "b2bi-api-client";

	public static String generateUserToken (String username,
														 String tenantId,
														 String defaultRealm,
														 Map<String, String> realmOverrides,
														 String orgRefName,
														 String accountId,
														 String[] roles,
														 long durationInSeconds,
														 String issuer) throws Exception {

		if ( username == null ) {
			throw new ValidationException("UserName can not be null");
		}
		if (tenantId == null ) {
			throw new ValidationException("tenantId can not be null");
		}
		if (defaultRealm == null) {
			throw new ValidationException("defaultRealm can not be null");
		}
		if (realmOverrides == null) {
			throw new ValidationException("realmOverrides can not be null, but can be empty");
		}

		if (accountId == null) {
			throw new ValidationException("accountId can not be null");
		}

		if (roles == null ) {
			throw new ValidationException("roles can not be null");
		}

		if (issuer == null) {
			throw new ValidationException("Issuer can not be null");
		}
		String privateKeyLocation = "privateKey.pem";
		PrivateKey privateKey = readPrivateKey(privateKeyLocation);
		
		JwtClaimsBuilder claimsBuilder = Jwt.claims();
		long currentTimeInSecs = currentTimeInSecs();
		
		Set<String> groups = new HashSet<>();
		Collections.addAll(groups, roles);

		claimsBuilder.issuer(issuer);
		claimsBuilder.subject(username);
		claimsBuilder.issuedAt(currentTimeInSecs);
		claimsBuilder.audience(AUDIENCE);
		claimsBuilder.expiresAt(currentTimeInSecs + durationInSeconds);
		claimsBuilder.groups(groups);
		claimsBuilder.claim("orgRefName", orgRefName);
		claimsBuilder.claim("tenantId", tenantId);
		claimsBuilder.claim("defaultRealm",defaultRealm );
		claimsBuilder.claim("accountId", accountId);
		claimsBuilder.claim("scope", AUTH_SCOPE);

		Map<String, String> area2Realm = new HashMap<>();
		area2Realm.put("security", "b2bi");
		area2Realm.put("signup", "b2bi");
		claimsBuilder.claim("realmOverrides", area2Realm);


		return claimsBuilder.jws().keyId(privateKeyLocation).sign(privateKey);
	}

	public static String generateRefreshToken(String username, String tenantId, String defaultRealm, Map<String, String> realmOverrides, String orgRefName,
															String accountId, String[] roles,  long durationInSeconds, String issuer) throws Exception {
		Set<String> groups = new HashSet<>(Arrays.asList(roles));


		String privateKeyLocation = "privateKey.pem";
		PrivateKey privateKey = readPrivateKey(privateKeyLocation);
		JwtClaimsBuilder claimsBuilder = Jwt.claims();
		long currentTimeInSecs = currentTimeInSecs();
		claimsBuilder.issuer(issuer);
		claimsBuilder.subject(username);
		claimsBuilder.issuedAt(currentTimeInSecs);
		claimsBuilder.groups(groups);
		claimsBuilder.audience("b2bi-api-client-refresh");
		claimsBuilder.expiresAt(currentTimeInSecs + durationInSeconds);
		claimsBuilder.claim("tenantId", tenantId);
		claimsBuilder.claim("defaultRealm",defaultRealm );
		claimsBuilder.claim("realmOverrides", realmOverrides);
		claimsBuilder.claim("orgRefName", orgRefName);
		claimsBuilder.claim("accountId", accountId);

		


		claimsBuilder.claim("scope", REFRESH_SCOPE);
		return claimsBuilder.jws().keyId(privateKeyLocation).sign(privateKey);
	}

	public static PrivateKey readPrivateKey(final String pemResName) throws Exception {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		try (InputStream contentIS = loader.getResourceAsStream(pemResName)) {
			if (contentIS == null ) {
				throw new Exception("Could not find Private Key with ResourceName:" + pemResName);
			}
			byte[] tmp = new byte[4096];
			int length = contentIS.read(tmp);
			if (length == 0) {
				throw new Exception("Could not find private key");
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

	public static PrivateKey decodePrivateKey(final String pemEncoded) throws Exception {
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
