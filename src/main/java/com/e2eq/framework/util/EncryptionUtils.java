package com.e2eq.framework.util;
import at.favre.lib.crypto.bcrypt.BCrypt;


public class EncryptionUtils {

    public static String hashPassword (String plainTextPassword) {
        return BCrypt.withDefaults().hashToString(12, plainTextPassword.toCharArray());
    }

    public static boolean checkPassword(String plainTextPassword, String encryptedHash) {
        BCrypt.Result result = BCrypt.verifyer().verify(plainTextPassword.toCharArray(), encryptedHash);
        return result.verified;
    }
}
