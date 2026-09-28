package de.joinside.evmap_service.availability;

/**
 * A latitude/longitude rectangle a provider is asked about.
 * <p>
 * Providers are queried by area rather than by identifier because that is what the national access
 * points offer: MobiData BW filters locations by bounding box or radius, and none of them accepts a
 * list of EVSE-IDs. The station detail path uses a small box around one station, and the map path the
 * viewport itself, so both go through one provider method. France's consolidation offers no area query
 * at all and ignores the box; see {@link AvailabilityProvider#fetch(GeoBounds)}.
 */
public record GeoBounds(double latMin, double lonMin, double latMax, double lonMax) {

    public GeoBounds {
        if (latMin > latMax || lonMin > lonMax)
            throw new IllegalArgumentException("Bounds are inverted: " + latMin + "," + lonMin + " to " + latMax + "," + lonMax);
    }

    /**
     * A box of roughly {@code radiusMeters} around a point.
     * <p>
     * The longitude degree shrinks with latitude, so it is scaled by {@code cos(lat)}; without that a
     * box around a northern station would be far wider than intended and pull in neighbouring sites.
     * Near the poles the cosine collapses and the box would grow without bound, which is capped
     * rather than special-cased — there are no public charge points at 89° and a wide box there costs
     * one over-large query, not a wrong answer, because matching is on identifiers.
     */
    public static GeoBounds around(double latitude, double longitude, double radiusMeters) {
        double latDelta = radiusMeters / 111_320.0;
        double shrink = Math.max(Math.cos(Math.toRadians(latitude)), 0.01);
        double lonDelta = radiusMeters / (111_320.0 * shrink);
        return new GeoBounds(latitude - latDelta, longitude - lonDelta,
                latitude + latDelta, longitude + lonDelta);
    }

    /** Rough diagonal extent, used to reject viewports too large to answer usefully. */
    public double heightDegrees() {
        return latMax - latMin;
    }

    public double widthDegrees() {
        return lonMax - lonMin;
    }
}
