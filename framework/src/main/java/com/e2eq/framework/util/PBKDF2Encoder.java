package com.e2eq.framework.util;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import jakarta.enterprise.context.RequestScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Utility class that encodes passwords using the
 * {@code PBKDF2WithHmacSHA512} algorithm.  The encoder
 * is configured via MicroProfile configuration properties
 * for the salt, iteration count and key length.
 *
 * @author ard333
 */
@RequestScoped
public class PBKDF2Encoder {

	@ConfigProperty(name = "com.ard333.quarkusjwt.password.secret")  private String secret;
	@ConfigProperty(name = "com.ard333.quarkusjwt.password.iteration")  private Integer iteration;
	@ConfigProperty(name = "com.ard333.quarkusjwt.password.keylength")  private Integer keylength;

	/**
	 * Encode a string with PBKDF2 with salt
	 * More info (https://www.owasp.org/index.php/Hashing_Java) 404 :(
	 * @param cs password
	 * @return encoded password
	 */
	public String encode(CharSequence cs) {

		try {
			byte[] result = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
											.generateSecret(new PBEKeySpec(cs.toString().toCharArray(), secret.getBytes(), iteration, keylength))
											.getEncoded();
			return Base64.getEncoder().encodeToString(result);
		} catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
			throw new RuntimeException(ex);
		}
	}
}
