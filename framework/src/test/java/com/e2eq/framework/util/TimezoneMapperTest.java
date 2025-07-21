package com.e2eq.framework.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests for {@link TimezoneMapper}. */
public class TimezoneMapperTest {

    @Test
    public void testMapping() {
        // Point inside the America/New_York polygon
        String ny = TimezoneMapper.latLngToTimezoneString(42.0, -75.0);
        assertEquals("America/New_York", ny);

        // Point inside the Europe/London polygon
        String london = TimezoneMapper.latLngToTimezoneString(52.0, -1.0);
        assertEquals("Europe/London", london);

        // Outside all polygons
        String unknown = TimezoneMapper.latLngToTimezoneString(0.0, 0.0);
        assertEquals("unknown", unknown);
    }
}
