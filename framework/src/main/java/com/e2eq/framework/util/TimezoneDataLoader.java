package com.e2eq.framework.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Utility class to load timezone polygon data from a resource file. */
class TimezoneDataLoader {
    private static List<TimezoneMapper.TzPolygon> cache;

    /**
     * Loads polygon data from classpath JSON and caches the result.
     */
    static synchronized List<TimezoneMapper.TzPolygon> loadPolygons() {
        if (cache != null) {
            return cache;
        }
        try (InputStream in = TimezoneDataLoader.class.getResourceAsStream("/timezones/polygons.json")) {
            ObjectMapper mapper = new ObjectMapper();
            List<PolygonRecord> records = mapper.readValue(in, new TypeReference<List<PolygonRecord>>() {});
            List<TimezoneMapper.TzPolygon> polys = new ArrayList<>();
            for (PolygonRecord rec : records) {
                float[] pts = new float[rec.points.length];
                for (int i = 0; i < rec.points.length; i++) {
                    pts[i] = (float) rec.points[i];
                }
                polys.add(new TimezoneMapper.TzPolygon(rec.timezone, pts));
            }
            cache = polys;
            return cache;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load timezone polygons", e);
        }
    }

    private static class PolygonRecord {
        public String timezone;
        public double[] points;
    }
}
