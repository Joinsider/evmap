package de.joinside.evmap_service.api.comment;

import de.joinside.evmap_service.support.PostgisDatabase;

import java.util.Set;
import java.util.UUID;

/** Lets tests in other packages read the package-private {@link CommentVisibility} against the real schema. */
public final class CommentVisibilityAccess {
    private CommentVisibilityAccess() {
    }

    public static Set<UUID> hiddenFor(UUID viewer, UUID station) {
        return new CommentVisibility(PostgisDatabase.jdbc()).hiddenFor(viewer, station);
    }
}
