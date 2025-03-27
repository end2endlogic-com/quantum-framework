package com.e2eq.framework.test;

import io.quarkus.test.junit.QuarkusTest;
import java.util.regex.Pattern;

import org.graalvm.nativeimage.Platform;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


public class TestRegEx {
    @Test
    public void testTenantIdPattern() {
    String regex = "^[a-z\\-.]{2,15}[\\\\.][a-z]{2,}$";
    Pattern pattern = Pattern.compile(regex);

    // Test a valid string
    String validString = "abc-def.com";
    Assertions.assertTrue(pattern.matcher(validString).matches(), "The string should match the pattern");

    String validString2 = "aaa.io";
    // Test another valid string
    String anotherValidString = "abc-def-ghi.com";
    Assertions.assertTrue(pattern.matcher(anotherValidString).matches(), "The string should match the pattern");

    // Test an invalid string
    String invalidString = "ab-def";
    Assertions.assertFalse(pattern.matcher(invalidString).matches(), "The string should not match the pattern");
   }
}
