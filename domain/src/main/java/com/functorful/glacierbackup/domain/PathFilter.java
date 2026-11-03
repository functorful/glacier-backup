package com.functorful.glacierbackup.domain;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

public final class PathFilter {

    private static final String GLOB_PREFIX = "glob:";

    private final List<PathMatcher> includeMatchers;
    private final List<PathMatcher> excludeMatchers;

    private PathFilter(List<PathMatcher> includeMatchers, List<PathMatcher> excludeMatchers) {
        this.includeMatchers = includeMatchers;
        this.excludeMatchers = excludeMatchers;
    }

    public static PathFilter of(List<String> includeGlobs, List<String> excludeGlobs) {
        return new PathFilter(compile(includeGlobs), compile(excludeGlobs));
    }

    public static PathFilter acceptAll() {
        return new PathFilter(List.of(), List.of());
    }

    public boolean matches(RelativePath path) {
        Path candidate = Path.of(path.value());
        if (excludeMatchers.stream().anyMatch(matcher -> matcher.matches(candidate))) {
            return false;
        }
        return includeMatchers.isEmpty() || includeMatchers.stream().anyMatch(matcher -> matcher.matches(candidate));
    }

    private static List<PathMatcher> compile(List<String> globs) {
        return globs.stream()
                .map(glob -> FileSystems.getDefault().getPathMatcher(GLOB_PREFIX + glob))
                .toList();
    }
}
