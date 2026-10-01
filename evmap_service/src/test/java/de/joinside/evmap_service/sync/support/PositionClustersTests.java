package de.joinside.evmap_service.sync.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PositionClustersTests {
    private record Spot(String id, double latitude, double longitude) {
    }

    private static final Comparator<Spot> SOUTH_TO_NORTH = Comparator.comparingDouble(Spot::latitude)
            .thenComparingDouble(Spot::longitude).thenComparing(Spot::id);

    private static List<PositionClusters.Cluster<Spot>> cluster(double radius, Spot... spots) {
        return PositionClusters.of(List.of(spots), Spot::latitude, Spot::longitude, SOUTH_TO_NORTH, radius);
    }

    @Test
    @DisplayName("groups records within the radius under the first of them, whatever order they are given in")
    void groupsAroundTheFirst() {
        // 0.0001° of latitude is about 11 m.
        var clusters = cluster(35, new Spot("c", 40.0003, -3.0), new Spot("a", 40.0, -3.0),
                new Spot("b", 40.0001, -3.0), new Spot("far", 40.1, -3.0));

        assertThat(clusters).hasSize(2);
        assertThat(clusters.get(0).anchor().id()).isEqualTo("a");
        assertThat(clusters.get(0).members()).extracting(Spot::id).containsExactly("a", "b", "c");
        assertThat(clusters.get(1).members()).extracting(Spot::id).containsExactly("far");
    }

    @Test
    @DisplayName("joins a record to the nearest anchor, not the first one in range")
    void joinsTheNearestAnchor() {
        // Two anchors 38 m apart at one latitude, both before "m" in south-to-north order; "m" is 31 m
        // from "a" and 21 m from "b", so both are in range and only the nearest may take it.
        var clusters = cluster(35, new Spot("a", 40.0, -3.0), new Spot("b", 40.0, -2.99955),
                new Spot("m", 40.00015, -2.9997));

        assertThat(clusters).hasSize(2);
        assertThat(clusters.get(1).members()).extracting(Spot::id).containsExactly("b", "m");
        assertThat(clusters.get(0).members()).extracting(Spot::id).containsExactly("a");
    }

    @Test
    @DisplayName("does not join records beyond the radius, and measures east-west distance by latitude")
    void respectsTheRadius() {
        // 0.0005° of longitude is 55 m at the equator but only about 38 m at 47° N.
        assertThat(cluster(35, new Spot("a", 0.0, 0.0), new Spot("b", 0.0, 0.0005))).hasSize(2);
        assertThat(cluster(40, new Spot("a", 47.0, 8.0), new Spot("b", 47.0, 8.0005))).hasSize(1);
    }

    @Test
    @DisplayName("finds neighbours across a grid cell boundary")
    void crossesCellBoundaries() {
        assertThat(cluster(35, new Spot("a", 40.00099, -3.0), new Spot("b", 40.00101, -3.0))).hasSize(1);
    }

    @Test
    @DisplayName("returns nothing for nothing, and refuses a radius the grid cannot cover")
    void edges() {
        assertThat(cluster(35)).isEmpty();
        assertThatThrownBy(() -> cluster(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cluster(80)).isInstanceOf(IllegalArgumentException.class);
    }
}
