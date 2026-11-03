package com.functorful.glacierbackup.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PathFilterTest {

    private static final String DS_STORE_GLOB = "**/.DS_Store";
    private static final String MEDIA_GLOB = "media/**";
    private static final String EXCLUDED_INCLUDED_PATH = "media/x/.DS_Store";
    private static final String INCLUDED_NON_EXCLUDED_PATH = "media/x/video.mp4";
    private static final String OUTSIDE_INCLUDE_PATH = "docs/readme.md";
    private static final String ANY_PATH = "anything/at/all.bin";
    private static final String WAL_DIR_ROOT_GLOB = "db/wal/**";
    private static final String WAL_DIR_NESTED_GLOB = "**/db/wal/**";
    private static final String DB_WAL_ROOT_GLOB = "*.db-wal";
    private static final String DB_WAL_NESTED_GLOB = "**/*.db-wal";
    private static final String ROOT_WAL_SEGMENT = "db/wal/000000010000001E0000000F";
    private static final String NESTED_WAL_SEGMENT = "app/data/db/wal/000000010000001E0000000F";
    private static final String ROOT_DB_WAL_FILE = "logs.db-wal";
    private static final String NESTED_DB_WAL_FILE = "app/db/journal.db-wal";
    private static final String NESTED_DB_FILE = "app/db/journal.db";

    @Test
    void excludeWinsOverInclude() {
        PathFilter filter = PathFilter.of(List.of(MEDIA_GLOB), List.of(DS_STORE_GLOB));

        assertThat(filter.matches(RelativePath.of(EXCLUDED_INCLUDED_PATH)))
                .as("excluded path must not match even when included")
                .isFalse();
        assertThat(filter.matches(RelativePath.of(INCLUDED_NON_EXCLUDED_PATH)))
                .as("included, non-excluded path must match")
                .isTrue();
        assertThat(filter.matches(RelativePath.of(OUTSIDE_INCLUDE_PATH)))
                .as("path outside include globs must not match")
                .isFalse();
    }

    @Test
    void emptyIncludeListIncludesEverything() {
        PathFilter filter = PathFilter.of(List.of(), List.of(DS_STORE_GLOB));

        assertThat(filter.matches(RelativePath.of(ANY_PATH)))
                .as("empty include list means include-all")
                .isTrue();
    }

    /**
     * Documents why excludes are typically written in a bare AND a
     * {@code **}{@code /}-prefixed form: {@code *} never crosses a directory
     * separator in Java glob syntax, so each form covers only its own depth.
     */
    @Test
    void bareDirectoryGlobExcludesOnlyAtTheSetRoot() {
        PathFilter filter = PathFilter.of(List.of(), List.of(WAL_DIR_ROOT_GLOB));

        assertThat(filter.matches(RelativePath.of(ROOT_WAL_SEGMENT)))
                .as("a bare directory glob must exclude at the set root")
                .isFalse();
        assertThat(filter.matches(RelativePath.of(NESTED_WAL_SEGMENT)))
                .as("a bare directory glob must NOT reach nested directories")
                .isTrue();
    }

    @Test
    void doubleStarPrefixedDirectoryGlobExcludesNestedDirectories() {
        PathFilter filter = PathFilter.of(List.of(), List.of(WAL_DIR_NESTED_GLOB));

        assertThat(filter.matches(RelativePath.of(NESTED_WAL_SEGMENT)))
                .as("a **/-prefixed directory glob must exclude at any depth")
                .isFalse();
    }

    @Test
    void suffixGlobPairExcludesSqliteSidecarsAtAnyDepthButNotTheDatabaseItself() {
        PathFilter filter = PathFilter.of(List.of(), List.of(DB_WAL_ROOT_GLOB, DB_WAL_NESTED_GLOB));

        assertThat(filter.matches(RelativePath.of(ROOT_DB_WAL_FILE)))
                .as("the bare suffix glob must exclude a sidecar at the set root")
                .isFalse();
        assertThat(filter.matches(RelativePath.of(NESTED_DB_WAL_FILE)))
                .as("the **/-prefixed suffix glob must exclude a nested sidecar")
                .isFalse();
        assertThat(filter.matches(RelativePath.of(NESTED_DB_FILE)))
                .as("the .db-wal globs must not exclude the .db file itself")
                .isTrue();
    }

    @Test
    void acceptAllMatchesEverything() {
        PathFilter filter = PathFilter.acceptAll();

        assertThat(filter.matches(RelativePath.of(ANY_PATH)))
                .as("acceptAll must match any path")
                .isTrue();
        assertThat(filter.matches(RelativePath.of(EXCLUDED_INCLUDED_PATH)))
                .as("acceptAll has no excludes, so every path matches")
                .isTrue();
    }
}
