package com.functorful.glacierbackup.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SnapshotTest {

    private static final String PATH_A = "a/file.txt";
    private static final String PATH_B = "b/file.txt";
    private static final String PATH_C = "c/file.txt";
    private static final String MISSING_PATH = "missing/file.txt";
    private static final long SIZE_BYTES = 42L;
    private static final Instant MODIFIED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final StorageClass STORAGE_CLASS = StorageClass.STANDARD;

    @Nested
    class LocalSnapshotBehavior {

        @Test
        void rejectsDuplicatePaths() {
            LocalFile first = localFile(PATH_A);
            LocalFile duplicate = localFile(PATH_A);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> LocalSnapshot.of(List.of(first, duplicate)));
        }

        @Test
        void findReturnsPresentEntry() {
            LocalFile file = localFile(PATH_A);
            LocalSnapshot snapshot = LocalSnapshot.of(List.of(file));

            assertThat(snapshot.find(RelativePath.of(PATH_A)))
                    .as("snapshot must contain the file it was built from")
                    .contains(file);
        }

        @Test
        void findReturnsEmptyForMissingPath() {
            LocalSnapshot snapshot = LocalSnapshot.of(List.of(localFile(PATH_A)));

            assertThat(snapshot.find(RelativePath.of(MISSING_PATH)))
                    .as("snapshot must not contain a file that was never added")
                    .isEmpty();
        }

        @Test
        void allReturnsEntriesSortedLexicographicallyByPath() {
            LocalFile fileC = localFile(PATH_C);
            LocalFile fileA = localFile(PATH_A);
            LocalFile fileB = localFile(PATH_B);
            LocalSnapshot snapshot = LocalSnapshot.of(List.of(fileC, fileA, fileB));

            assertThat(snapshot.all()).as("all() must be sorted lexicographically by path").containsExactly(fileA, fileB, fileC);
        }

        @Test
        void sizeReflectsNumberOfEntries() {
            LocalSnapshot snapshot = LocalSnapshot.of(List.of(localFile(PATH_A), localFile(PATH_B)));

            assertThat(snapshot.size()).as("size() must count distinct entries").isEqualTo(2);
        }
    }

    @Nested
    class RemoteSnapshotBehavior {

        @Test
        void rejectsDuplicatePaths() {
            RemoteObject first = remoteObject(PATH_A);
            RemoteObject duplicate = remoteObject(PATH_A);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> RemoteSnapshot.of(List.of(first, duplicate)));
        }

        @Test
        void findReturnsPresentEntry() {
            RemoteObject object = remoteObject(PATH_A);
            RemoteSnapshot snapshot = RemoteSnapshot.of(List.of(object));

            assertThat(snapshot.find(RelativePath.of(PATH_A)))
                    .as("snapshot must contain the object it was built from")
                    .contains(object);
        }

        @Test
        void findReturnsEmptyForMissingPath() {
            RemoteSnapshot snapshot = RemoteSnapshot.of(List.of(remoteObject(PATH_A)));

            assertThat(snapshot.find(RelativePath.of(MISSING_PATH)))
                    .as("snapshot must not contain an object that was never added")
                    .isEmpty();
        }

        @Test
        void allReturnsEntriesSortedLexicographicallyByPath() {
            RemoteObject objectC = remoteObject(PATH_C);
            RemoteObject objectA = remoteObject(PATH_A);
            RemoteObject objectB = remoteObject(PATH_B);
            RemoteSnapshot snapshot = RemoteSnapshot.of(List.of(objectC, objectA, objectB));

            assertThat(snapshot.all())
                    .as("all() must be sorted lexicographically by path")
                    .containsExactly(objectA, objectB, objectC);
        }

        @Test
        void sizeReflectsNumberOfEntries() {
            RemoteSnapshot snapshot = RemoteSnapshot.of(List.of(remoteObject(PATH_A), remoteObject(PATH_B)));

            assertThat(snapshot.size()).as("size() must count distinct entries").isEqualTo(2);
        }
    }

    private static LocalFile localFile(String path) {
        return new LocalFile(RelativePath.of(path), SIZE_BYTES, MODIFIED_AT);
    }

    private static RemoteObject remoteObject(String path) {
        return new RemoteObject(RelativePath.of(path), SIZE_BYTES, MODIFIED_AT, STORAGE_CLASS, RestoreState.notStarted());
    }
}
