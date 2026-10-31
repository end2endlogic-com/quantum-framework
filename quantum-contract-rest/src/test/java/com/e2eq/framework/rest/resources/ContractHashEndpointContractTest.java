package com.e2eq.framework.rest.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class ContractHashEndpointContractTest {

    @Test
    void preservesContractHashEndpoint() throws NoSuchMethodException {
        assertEquals("/contract-hash", ContractHashResource.class.getAnnotation(Path.class).value());
        Method get = ContractHashResource.class.getDeclaredMethod("get");
        assertEquals(GET.class, get.getAnnotation(GET.class).annotationType());
    }
}
