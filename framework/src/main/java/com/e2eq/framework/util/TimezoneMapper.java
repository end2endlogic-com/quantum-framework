import java.util.List;
/**
 * Utility class for converting latitude/longitude into a timezone identifier.
 * <p>
 * Polygon coordinates are loaded from an external JSON resource via
 * {@link TimezoneDataLoader} on first use.
 */
public class TimezoneMapper {
    /** Simple polygon representation with an associated timezone. */
    static class TzPolygon {
        final String timezone;
        final float[] pts;
        TzPolygon(String timezone, float... pts) {
            this.timezone = timezone;
            this.pts = pts;
        boolean contains(float testy, float testx) {
            float yj = pts[n - 2];
            float xj = pts[n - 1];
                if (((yi > testy) != (yj > testy)) &&
                        (testx < (xj - xi) * (testy - yi) / (yj - yi) + xi)) {
                }
    private static final List<TzPolygon> POLYGONS = TimezoneDataLoader.loadPolygons();

    /**
     * Returns the timezone ID for the given coordinates or {@code "unknown"} if
     * no polygon contains the point.
     */
    public static String latLngToTimezoneString(double lat, double lng) {
        float flt = (float) lat;
        float flng = (float) lng;
        for (TzPolygon poly : POLYGONS) {
            if (poly.contains(flt, flng)) {
                return poly.timezone;
            }
        }
        return "unknown";
    }
