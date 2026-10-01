package de.joinside.evmap_service.sync.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Greedy grouping of records by position, for sources that list EVSEs or operator sites rather than
 * places (Switzerland's DIEMO, Spain's MITERD).
 * <p>
 * The ingestion matches a record to a known station within 30 m and then <em>replaces</em> that
 * station's charge points. Two stations of one source closer than that would each replace the other's
 * inventory, leaving only the last one's charge points. A source that cannot give one record per place
 * therefore groups its records at a radius above the ingestion's, so that the stations it emits are
 * never within 30 m of each other. ADR 0012 has the measurements.
 * <p>
 * The order is the caller's and is what makes the result reproducible: the first record of a cluster is
 * its anchor, and a record joins the <em>nearest</em> anchor within the radius, not the first one found.
 */
public final class PositionClusters {
    /** Cell edge for the neighbour lookup, in degrees (≈ 111 m north-south): larger than any radius allowed. */
    private static final double GRID_DEGREES = 0.001;
    private static final double METRES_PER_DEGREE = 111_320;
    /** Above this the one-cell neighbourhood of the grid no longer covers the radius, even at 60° N. */
    private static final double MAX_RADIUS_METRES = 50;

    private PositionClusters() {
    }

    /**
     * @param anchor  the first record of the cluster; the cluster's position is its position
     * @param members every record of the cluster in the order given, the anchor first
     */
    public record Cluster<T>(T anchor, List<T> members) {
    }

    /**
     * @param order the order records are taken in; the first of each cluster becomes its anchor
     * @return the clusters, in the order their anchors appear
     */
    public static <T> List<Cluster<T>> of(Collection<T> records, ToDoubleFunction<T> latitude,
                                          ToDoubleFunction<T> longitude, Comparator<T> order, double radiusMetres) {
        if (radiusMetres <= 0 || radiusMetres > MAX_RADIUS_METRES)
            throw new IllegalArgumentException("The cluster radius must be 0 to " + MAX_RADIUS_METRES + " m: " + radiusMetres);

        List<T> ordered = new ArrayList<>(records);
        ordered.sort(order);

        List<Cluster<T>> clusters = new ArrayList<>();
        Map<Long, List<Cluster<T>>> grid = new HashMap<>();

        for (T record : ordered) {
            double recordLatitude = latitude.applyAsDouble(record);
            double recordLongitude = longitude.applyAsDouble(record);
            Cluster<T> cluster = nearest(grid, latitude, longitude, recordLatitude, recordLongitude, radiusMetres);
            if (cluster == null) {
                cluster = new Cluster<>(record, new ArrayList<>());
                clusters.add(cluster);
                grid.computeIfAbsent(key(cellIndex(recordLatitude), cellIndex(recordLongitude)), k -> new ArrayList<>())
                        .add(cluster);
            }
            cluster.members().add(record);
        }
        return clusters;
    }

    private static <T> Cluster<T> nearest(Map<Long, List<Cluster<T>>> grid, ToDoubleFunction<T> latitude,
                                          ToDoubleFunction<T> longitude, double recordLatitude,
                                          double recordLongitude, double radiusMetres) {
        long row = cellIndex(recordLatitude);
        long column = cellIndex(recordLongitude);
        Cluster<T> best = null;
        double bestDistance = radiusMetres;
        for (long dRow = -1; dRow <= 1; dRow++) {
            for (long dColumn = -1; dColumn <= 1; dColumn++) {
                for (Cluster<T> cluster : grid.getOrDefault(key(row + dRow, column + dColumn), List.of())) {
                    double distance = metres(latitude.applyAsDouble(cluster.anchor()),
                            longitude.applyAsDouble(cluster.anchor()), recordLatitude, recordLongitude);
                    if (distance <= bestDistance) {
                        best = cluster;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private static long cellIndex(double degrees) {
        return (long) Math.floor(degrees / GRID_DEGREES);
    }

    private static long key(long row, long column) {
        return row * 1_000_003L + column;
    }

    /** Equirectangular distance; ample at tens of metres, and it avoids trigonometry per pair. */
    static double metres(double latitudeA, double longitudeA, double latitudeB, double longitudeB) {
        double dNorth = (latitudeB - latitudeA) * METRES_PER_DEGREE;
        double dEast = (longitudeB - longitudeA) * METRES_PER_DEGREE
                * Math.cos(Math.toRadians((latitudeA + latitudeB) / 2));
        return Math.hypot(dNorth, dEast);
    }
}
