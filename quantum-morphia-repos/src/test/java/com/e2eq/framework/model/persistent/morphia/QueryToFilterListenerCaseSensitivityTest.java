package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.RegexFilter;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class QueryToFilterListenerCaseSensitivityTest {

    public static class DummyModel extends UnversionedBaseModel {
        @Override
        public String bmFunctionalArea() { return "test-area"; }

        @Override
        public String bmFunctionalDomain() { return "test-domain"; }
    }

    @Test
    void wildcardFiltersAreCaseInsensitiveByDefault() {
        Filter filter = MorphiaUtils.convertToFilter("displayName:*route*", DummyModel.class);

        RegexFilter regexFilter = assertInstanceOf(RegexFilter.class, filter);
        assertEquals(".*route.*", regexFilter.pattern().pattern());
        assertTrue((regexFilter.pattern().flags() & Pattern.CASE_INSENSITIVE) != 0);

        Filter explicitFilter = MorphiaUtils.convertToFilter("displayName:*route*~ci", DummyModel.class);
        RegexFilter explicitRegexFilter = assertInstanceOf(RegexFilter.class, explicitFilter);
        assertTrue((explicitRegexFilter.pattern().flags() & Pattern.CASE_INSENSITIVE) != 0);
    }

    @Test
    void wildcardFiltersCanOptIntoCaseSensitiveMatching() {
        Filter filter = MorphiaUtils.convertToFilter("displayName:*route*~cs", DummyModel.class);

        RegexFilter regexFilter = assertInstanceOf(RegexFilter.class, filter);
        assertEquals(".*route.*", regexFilter.pattern().pattern());
        assertFalse((regexFilter.pattern().flags() & Pattern.CASE_INSENSITIVE) != 0);
    }

    @Test
    void stringEqualityIsCaseInsensitiveByDefault() {
        Filter filter = MorphiaUtils.convertToFilter("displayName:Route", DummyModel.class);

        RegexFilter regexFilter = assertInstanceOf(RegexFilter.class, filter);
        assertEquals("^Route$", regexFilter.pattern().pattern());
        assertTrue((regexFilter.pattern().flags() & Pattern.CASE_INSENSITIVE) != 0);
    }

    @Test
    void stringEqualityCanOptIntoCaseSensitiveMatching() {
        Filter filter = MorphiaUtils.convertToFilter("displayName:Route~cs", DummyModel.class);

        assertEquals("displayName", filter.getField());
        assertEquals("Route", filter.getValue());
    }
}
