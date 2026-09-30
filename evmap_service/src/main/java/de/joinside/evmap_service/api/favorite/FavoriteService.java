package de.joinside.evmap_service.api.favorite;

import de.joinside.evmap_service.api.station.StationController.StationNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Favorites of one account (ADR 0021). Bounded per account so the table cannot grow without limit;
 * logs carry ids and counts only.
 */
@Service
class FavoriteService {
    private static final Logger log = LoggerFactory.getLogger(FavoriteService.class);

    /** Most favorites one account may hold. Far above what a person curates by hand. */
    static final int MAX_FAVORITES = 500;
    /** Most ids one merge request may carry: a device cannot hold more than the account may keep anyway. */
    static final int MAX_MERGE_IDS = 1_000;

    private final FavoriteRepository repository;

    FavoriteService(FavoriteRepository repository) {
        this.repository = repository;
    }

    List<FavoriteController.FavoriteStation> list(UUID account) {
        return repository.list(account);
    }

    @Transactional
    void add(UUID account, UUID station) {
        if (repository.isFavorite(account, station)) return;
        if (!repository.stationExists(station)) throw new StationNotFoundException(station);
        if (repository.count(account) >= MAX_FAVORITES) throw new IllegalArgumentException("At most " + MAX_FAVORITES + " favorites");
        repository.add(account, station);
        log.info("Account {} favorited station {}", account, station);
    }

    void remove(UUID account, UUID station) {
        repository.remove(account, station);
        log.info("Account {} removed favorite {}", account, station);
    }

    @Transactional
    List<FavoriteController.FavoriteStation> merge(UUID account, List<UUID> stationIds) {
        if (stationIds == null) throw new IllegalArgumentException("stationIds is required");
        if (stationIds.size() > MAX_MERGE_IDS) throw new IllegalArgumentException("At most " + MAX_MERGE_IDS + " ids per merge");
        Set<UUID> requested = new LinkedHashSet<>(stationIds);
        List<UUID> addable = repository.addable(account, requested);
        long room = Math.max(0, MAX_FAVORITES - repository.count(account));
        List<UUID> toAdd = addable.stream().limit(room).toList();
        toAdd.forEach(station -> repository.add(account, station));
        log.info("Account {} merged {} device favorites: {} added, {} unknown or already kept, {} over the limit", account,
                requested.size(), toAdd.size(), requested.size() - addable.size(), addable.size() - toAdd.size());
        return repository.list(account);
    }
}
