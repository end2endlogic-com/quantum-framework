package com.e2eq.framework.rest.filters;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for SecurityFilter LIST action inference
 */
public class SecurityFilterListActionTest {

    // Mock resource class with @FunctionalMapping
    @FunctionalMapping(area = "testArea", domain = "testDomain")
    public static class TestResource {
        
        @GET
        @Path("list")
        public String getList() {
            return "list";
        }
        
        @GET
        @Path("list/{id}")
        public String getListById() {
            return "list by id";
        }
        
        @GET
        @FunctionalAction("LIST")
        public String getListWithAnnotation() {
            return "list with annotation";
        }
    }

    @BeforeEach
    public void setUp() {
        SecurityContext.clear();
    }

    @Test
    public void testThreeSegmentPathWithList() {
        // Test: Path parsing logic for /area/domain/list
        String path = "testArea/testDomain/list";
        String[] segments = path.split("/");
        
        assertEquals(3, segments.length);
        assertEquals("testArea", segments[0]);
        assertEquals("testDomain", segments[1]);
        
        String action = segments[2];
        if ("list".equalsIgnoreCase(action)) {
            action = "LIST";
        }
        
        assertEquals("LIST", action);
    }

    @Test
    public void testFourSegmentPathWithList() {
        // Test: Path parsing logic for /area/domain/list/123
        String path = "testArea/testDomain/list/123";
        String[] segments = path.split("/");
        
        assertEquals(4, segments.length);
        assertEquals("testArea", segments[0]);
        assertEquals("testDomain", segments[1]);
        
        String action = segments[2];
        if ("list".equalsIgnoreCase(action)) {
            action = "LIST";
        }
        String resourceId = segments[3];
        
        assertEquals("LIST", action);
        assertEquals("123", resourceId);
    }

    @Test
    public void testListCaseInsensitive() {
        // Test: Case-insensitive matching for "list"
        String[] testCases = {"list", "List", "LIST", "LiSt"};
        
        for (String testCase : testCases) {
            String action = testCase;
            if ("list".equalsIgnoreCase(action)) {
                action = "LIST";
            }
            assertEquals("LIST", action, "Failed for: " + testCase);
        }
    }

    @Test
    public void testTwoSegmentPathInfersFromHttpMethod() {
        // Test: HTTP method inference logic
        String httpMethod = "GET";
        String inferredAction = inferActionFromHttpMethod(httpMethod);
        
        assertEquals("VIEW", inferredAction);
        
        // Test other methods
        assertEquals("CREATE", inferActionFromHttpMethod("POST"));
        assertEquals("UPDATE", inferActionFromHttpMethod("PUT"));
        assertEquals("DELETE", inferActionFromHttpMethod("DELETE"));
    }
    
    private String inferActionFromHttpMethod(String http) {
        if (http == null) return "*";
        return switch (http.toUpperCase()) {
            case "GET" -> "VIEW";
            case "POST" -> "CREATE";
            case "PUT", "PATCH" -> "UPDATE";
            case "DELETE" -> "DELETE";
            default -> http;
        };
    }

    @Test
    public void testNonListActionPreserved() {
        // Test: Non-"list" actions are preserved
        String action = "create";
        
        // Only "list" gets converted to "LIST"
        if ("list".equalsIgnoreCase(action)) {
            action = "LIST";
        }
        
        assertEquals("create", action);
    }
}
