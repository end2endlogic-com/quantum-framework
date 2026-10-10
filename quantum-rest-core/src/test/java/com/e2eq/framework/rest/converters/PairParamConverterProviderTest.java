package com.e2eq.framework.rest.converters;

import jakarta.ws.rs.BadRequestException;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PairParamConverterProviderTest {

    private static Pair<String, Object>[] parse(String value) {
        return PairParamConverterProvider.PairArrayParamConverter.parse(value);
    }

    @Test
    void parses_bracketed_quoted_and_unquoted_entries() {
        Pair<String, Object>[] pairs = parse("[status:'ACTIVE',description:'a, b',count:3]");
        assertEquals(3, pairs.length);
        assertEquals(Pair.of("status", "ACTIVE"), pairs[0]);
        assertEquals(Pair.of("description", "a, b"), pairs[1]);
        assertEquals(Pair.of("count", "3"), pairs[2]);
    }

    @Test
    void keeps_previously_accepted_forms() {
        assertEquals(Pair.of("url", "http://x:8080/p"), parse("url:http://x:8080/p")[0],
                "unquoted values may contain colons");
        Pair<String, Object>[] spaced = parse("a:1, b:2");
        assertEquals(Pair.of("a", "1"), spaced[0]);
        assertEquals(Pair.of("b", "2"), spaced[1]);
        assertEquals(Pair.of("empty", ""), parse("empty:''")[0]);
        assertEquals(0, parse("").length);
        assertEquals(0, parse("[]").length);
    }

    @Test
    void accepts_the_list_string_form_the_runtime_passes() {
        // A single pairs=[...] query parameter reaches the converter wrapped once more.
        Pair<String, Object>[] single = parse("[[description:'updated, with comma']]");
        assertEquals(1, single.length);
        assertEquals(Pair.of("description", "updated, with comma"), single[0]);
        // Repeated unbracketed pairs= parameters arrive as a comma-space joined list.
        Pair<String, Object>[] repeated = parse("[a:1, b:'x, y']");
        assertEquals(Pair.of("a", "1"), repeated[0]);
        assertEquals(Pair.of("b", "x, y"), repeated[1]);
        // A quoted bracket does not confuse the enclosure check.
        assertEquals(Pair.of("note", "]["), parse("[[note:']['" + "]]")[0]);
    }

    @Test
    void rejects_separately_bracketed_repeated_parameters_instead_of_corrupting_values() {
        // The previous parser stored a:"1]" and b:"2]" here.
        assertThrows(BadRequestException.class, () -> parse("[[a:1], [b:2]]"));
    }

    @Test
    void rejects_nested_path_instead_of_truncating_it() {
        // The previous regex parser turned this into tenantId:forged.
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> parse("dataDomain.tenantId:forged"));
        assertTrue(ex.getMessage().contains("dataDomain.tenantId:forged"), ex.getMessage());
        assertTrue(ex.getMessage().contains("nested paths are not supported"), ex.getMessage());
    }

    @Test
    void rejects_malformed_entries_instead_of_skipping_them() {
        for (String bad : new String[]{"noColon", ":value", "a:1,,b:2", "a:1,", "a:", "a:'open",
                "a:'x'y", "a-b:1", "a:1,b.c:2"}) {
            assertThrows(BadRequestException.class, () -> parse(bad), bad);
        }
    }
}
