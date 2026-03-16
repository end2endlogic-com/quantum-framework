package com.e2eq.framework.tests.util;

import com.e2eq.framework.util.CommonUtils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestCalculateMD5 {
    @Test
    public void testCalculateMD5() {
        String input = "hello world";
        String expected = "5eb63bbbe01eeed093cb22bb8f5acdc3";
        assertEquals(expected, CommonUtils.calculateMD5(input));
    }
}
