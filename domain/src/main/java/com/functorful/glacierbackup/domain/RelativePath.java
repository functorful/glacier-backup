package com.functorful.glacierbackup.domain;

public record RelativePath(String value) implements Comparable<RelativePath> {

    public static RelativePath of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRelativePathException(raw, "blank");
        }
        String normalized = raw.replace('\\', '/');
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("/")) {
            throw new InvalidRelativePathException(raw, "absolute");
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                throw new InvalidRelativePathException(raw, "parent traversal");
            }
        }
        return new RelativePath(normalized);
    }

    @Override
    public int compareTo(RelativePath other) {
        return value.compareTo(other.value);
    }
}
