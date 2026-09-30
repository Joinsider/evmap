package de.joinside.evmap_service.api.favorite;

import de.joinside.evmap_service.api.station.StationController.StationNotFoundException;
import de.joinside.evmap_service.support.PostgisDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Favorites against the real schema (ADR 0021). */
class FavoriteTests {
    private FavoriteService favorites;
    private UUID enbw;
    private UUID ionity;
    private UUID me;
    private UUID other;

    @BeforeEach
    void setUp() {
        PostgisDatabase.clearMasterData();
        PostgisDatabase.clearUserData();
        favorites = new FavoriteService(new FavoriteRepository(PostgisDatabase.jdbc()));
        enbw = PostgisDatabase.insertStation("EnBW Stuttgart", "EnBW", "DE", 48.77, 9.18);
        PostgisDatabase.insertConnector(enbw, "TYPE_2", new BigDecimal("22.0"), 2);
        PostgisDatabase.insertConnector(enbw, "CCS", new BigDecimal("150.0"), 1);
        ionity = PostgisDatabase.insertStation("Ionity", "Ionity", "DE", 48.8, 9.2);
        me = account();
        other = account();
    }

    private static UUID account() {
        UUID id = UUID.randomUUID();
        PostgisDatabase.jdbc().sql("INSERT INTO user_data.account (id) VALUES (:id)").param("id", id).update();
        return id;
    }

    private static long count(String table) {
        return PostgisDatabase.jdbc().sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Test
    @DisplayName("a favorite is listed with the station's map fields and its strongest connector")
    void listCarriesTheStation() {
        favorites.add(me, enbw);

        assertThat(favorites.list(me)).singleElement().satisfies(favorite -> {
            assertThat(favorite.id()).isEqualTo(enbw);
            assertThat(favorite.displayName()).isEqualTo("EnBW Stuttgart");
            assertThat(favorite.operatorName()).isEqualTo("EnBW");
            assertThat(favorite.maxPowerKw()).isEqualByComparingTo("150.0");
            assertThat(favorite.favoritedAt()).isNotNull();
        });
        assertThat(favorites.list(other)).isEmpty();
    }

    @Test
    @DisplayName("adding twice keeps one row, removing what is not there is fine, and the newest favorite comes first")
    void idempotentAndNewestFirst() {
        favorites.add(me, enbw);
        favorites.add(me, enbw);
        favorites.add(me, ionity);
        favorites.remove(me, UUID.randomUUID());

        assertThat(count("user_data.favorite_station")).isEqualTo(2);
        assertThat(favorites.list(me)).extracting(FavoriteController.FavoriteStation::id).containsExactly(ionity, enbw);

        favorites.remove(me, enbw);
        favorites.remove(me, enbw);
        assertThat(favorites.list(me)).extracting(FavoriteController.FavoriteStation::id).containsExactly(ionity);
    }

    @Test
    @DisplayName("an unknown station cannot be favorited")
    void unknownStation() {
        assertThatThrownBy(() -> favorites.add(me, UUID.randomUUID())).isInstanceOf(StationNotFoundException.class);
        assertThat(count("user_data.favorite_station")).isZero();
    }

    @Test
    @DisplayName("merging adds the device's stations to the account's and answers with the union; unknown ids are skipped")
    void mergeIsAUnion() {
        favorites.add(me, enbw);

        List<FavoriteController.FavoriteStation> union = favorites.merge(me, List.of(ionity, ionity, UUID.randomUUID()));

        assertThat(union).extracting(FavoriteController.FavoriteStation::id).containsExactlyInAnyOrder(enbw, ionity);
        assertThat(favorites.merge(me, List.of())).hasSize(2);
        assertThat(favorites.list(other)).isEmpty();
    }

    @Test
    @DisplayName("an account holds at most 500 favorites; a merge fills up to the limit, a single add refuses")
    void limit() {
        List<UUID> stations = IntStream.range(0, FavoriteService.MAX_FAVORITES + 5)
                .mapToObj(i -> PostgisDatabase.insertStation("S" + i, "Op", "DE", 48.0 + i * 0.001, 9.0)).toList();

        List<FavoriteController.FavoriteStation> result = favorites.merge(me, stations);

        assertThat(result).hasSize(FavoriteService.MAX_FAVORITES);
        UUID beyond = stations.stream().filter(s -> result.stream().noneMatch(f -> f.id().equals(s))).findFirst().orElseThrow();
        assertThatThrownBy(() -> favorites.add(me, beyond)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a merge needs a list and is bounded")
    void mergeValidation() {
        assertThatThrownBy(() -> favorites.merge(me, null)).isInstanceOf(IllegalArgumentException.class);
        List<UUID> tooMany = IntStream.range(0, FavoriteService.MAX_MERGE_IDS + 1).mapToObj(i -> UUID.randomUUID()).toList();
        assertThatThrownBy(() -> favorites.merge(me, tooMany)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("favorites go with the account and with the station")
    void cascades() {
        favorites.add(me, enbw);
        favorites.add(other, enbw);
        favorites.add(other, ionity);

        PostgisDatabase.jdbc().sql("DELETE FROM user_data.account WHERE id = :id").param("id", me).update();
        assertThat(count("user_data.favorite_station")).isEqualTo(2);

        PostgisDatabase.jdbc().sql("DELETE FROM master.charging_station WHERE id = :id").param("id", enbw).update();
        assertThat(favorites.list(other)).extracting(FavoriteController.FavoriteStation::id).containsExactly(ionity);
    }
}
