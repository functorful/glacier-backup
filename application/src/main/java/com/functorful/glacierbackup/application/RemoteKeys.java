package com.functorful.glacierbackup.application;

import com.functorful.glacierbackup.domain.RelativePath;

public final class RemoteKeys {

    private static final String SEPARATOR = "/";
    private static final String EMPTY = "";

    private RemoteKeys() {}

    public static String keyFor(String prefix, RelativePath path) {
        String normalizedPrefix = normalize(prefix);
        return normalizedPrefix.isEmpty() ? path.value() : normalizedPrefix + SEPARATOR + path.value();
    }

    public static RelativePath pathFor(String prefix, String key) {
        String normalizedPrefix = normalize(prefix);
        if (normalizedPrefix.isEmpty()) {
            return RelativePath.of(key);
        }
        String prefixWithSeparator = normalizedPrefix + SEPARATOR;
        if (!key.startsWith(prefixWithSeparator)) {
            throw new IllegalArgumentException("Key \"%s\" does not start with prefix \"%s\"".formatted(key, prefix));
        }
        return RelativePath.of(key.substring(prefixWithSeparator.length()));
    }

    /** The prefix to send to a remote listing call: normalized with a trailing separator, so directory semantics apply. */
    public static String requestPrefix(String prefix) {
        String normalizedPrefix = normalize(prefix);
        return normalizedPrefix.isEmpty() ? EMPTY : normalizedPrefix + SEPARATOR;
    }

    /** Whether a key is truly under the given prefix, as a directory, rejecting sibling prefixes and bare same-named objects. */
    public static boolean belongsToPrefix(String key, String prefix) {
        String directoryPrefix = requestPrefix(prefix);
        return directoryPrefix.isEmpty() || key.startsWith(directoryPrefix);
    }

    private static String normalize(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return EMPTY;
        }
        return prefix.endsWith(SEPARATOR) ? prefix.substring(0, prefix.length() - 1) : prefix;
    }
}
