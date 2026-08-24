/**
 * Mechanics shared by source adapters — downloads, CSV headers — with no knowledge of any source.
 * <p>
 * The rule that keeps this package useful rather than a dumping ground: nothing here may depend on a
 * {@code sync.<source>} package, and nothing here may know what a charging station is. It handles
 * bytes and columns; meaning stays in the adapter that owns the source.
 * <p>
 * Things land here once a <em>second</em> adapter genuinely needs them, not in anticipation of one.
 * A helper written for a source that does not exist yet is a guess about a file format nobody has seen.
 */
package de.joinside.evmap_service.sync.support;
