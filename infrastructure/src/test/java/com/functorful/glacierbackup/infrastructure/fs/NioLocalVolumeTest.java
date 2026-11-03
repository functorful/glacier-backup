package com.functorful.glacierbackup.infrastructure.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.functorful.glacierbackup.application.port.LocalScanResult;
import com.functorful.glacierbackup.application.port.Log;
import com.functorful.glacierbackup.domain.LocalFile;
import com.functorful.glacierbackup.domain.PathFilter;
import com.functorful.glacierbackup.domain.RelativePath;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class NioLocalVolumeTest {

    private static final String ROOT_FILE_NAME = "a.bin";
    private static final String NESTED_DIR_NAME = "sub";
    private static final String NESTED_FILE_NAME = "b.bin";
    private static final String NESTED_RELATIVE_PATH = "sub/b.bin";
    private static final String TMP_FILE_NAME = "x.tmp";
    private static final String NESTED_TMP_RELATIVE_PATH = "sub/x.tmp";
    private static final String TMP_EXCLUDE_GLOB = "**/*.tmp";
    private static final String UNMAPPABLE_FILE_NAME = "Caf�.mov";
    private static final int ROOT_FILE_SIZE_BYTES = 3;
    private static final int NESTED_FILE_SIZE_BYTES = 5;
    private static final int DIR_COUNT = 50;
    private static final int FILES_PER_DIR = 100;
    private static final int TICK_TEST_THREADS = 2;
    private static final long TICK_TEST_THRESHOLD = 10;
    private static final int CUSTOM_SCAN_THREADS = 5;
    private static final int BOUND_TEST_THREADS = 2;
    private static final long EVERY_FILE_TICK = 1;
    private static final int BOUND_TEST_DIR_COUNT = 20;
    private static final int BOUND_TEST_FILES_PER_DIR = 5;
    private static final String BACKSLASH_COLLISION_DIR_NAME = "x";
    private static final String BACKSLASH_COLLISION_NESTED_FILE_NAME = "y";
    private static final String BACKSLASH_COLLISION_FILE_NAME = "x\\y";
    private static final String BACKSLASH_COLLISION_RELATIVE_PATH = "x/y";
    private static final int BACKSLASH_COLLISION_REAL_FILE_SIZE_BYTES = 7;
    private static final int BACKSLASH_COLLISION_LOSER_FILE_SIZE_BYTES = 9;
    private static final String QUAD_COLLISION_RELATIVE_PATH = "a/b/c";
    private static final String QUAD_COLLISION_DIR_A_NAME = "a";
    private static final String QUAD_COLLISION_DIR_B_NAME = "b";
    private static final String QUAD_COLLISION_LEAF_FILE_NAME = "c";
    private static final String QUAD_COLLISION_B_BACKSLASH_C_NAME = "b\\c";
    private static final String QUAD_COLLISION_A_BACKSLASH_B_DIR_NAME = "a\\b";
    private static final String QUAD_COLLISION_A_BACKSLASH_B_BACKSLASH_C_NAME = "a\\b\\c";
    private static final int QUAD_COLLISION_REAL_FILE_SIZE_BYTES = 11;
    private static final String ABSOLUTE_INVALID_NAME = "\\x";
    private static final String PARENT_TRAVERSAL_INVALID_NAME = "a\\..\\b";
    private static final String INVALID_NAME_SUB_DIR = "sub-invalid";
    private static final String INVALID_NAME_SIBLING_FILE_NAME = "sibling.bin";
    private static final String INVALID_NAME_NESTED_SIBLING_RELATIVE_PATH = "sub-invalid/sibling.bin";
    private static final int INVALID_NAME_SIBLING_FILE_SIZE_BYTES = 4;

    private final NioLocalVolume localVolume = new NioLocalVolume(Log.noOp());

    @Test
    void scanFindsNestedFilesWithCorrectSizesAndNormalizedPaths(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve(ROOT_FILE_NAME), new byte[ROOT_FILE_SIZE_BYTES]);
        Path subDir = Files.createDirectory(tempDir.resolve(NESTED_DIR_NAME));
        Files.write(subDir.resolve(NESTED_FILE_NAME), new byte[NESTED_FILE_SIZE_BYTES]);

        LocalScanResult result = localVolume.scan(tempDir, PathFilter.acceptAll());

        assertThat(result.snapshot().size()).as("snapshot must contain both files").isEqualTo(2);
        Optional<LocalFile> rootFile = result.snapshot().find(RelativePath.of(ROOT_FILE_NAME));
        assertThat(rootFile).as("root file must be present").isPresent();
        assertThat(rootFile.get().sizeBytes()).as("root file size must match bytes written").isEqualTo(ROOT_FILE_SIZE_BYTES);
        Optional<LocalFile> nestedFile = result.snapshot().find(RelativePath.of(NESTED_RELATIVE_PATH));
        assertThat(nestedFile).as("nested file must be present with normalized path").isPresent();
        assertThat(nestedFile.get().sizeBytes()).as("nested file size must match bytes written").isEqualTo(NESTED_FILE_SIZE_BYTES);
    }

    @Test
    void scanAppliesFilterDuringWalk(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve(ROOT_FILE_NAME), new byte[ROOT_FILE_SIZE_BYTES]);
        Path subDir = Files.createDirectory(tempDir.resolve(NESTED_DIR_NAME));
        Files.write(subDir.resolve(TMP_FILE_NAME), new byte[ROOT_FILE_SIZE_BYTES]);
        PathFilter filter = PathFilter.of(List.of(), List.of(TMP_EXCLUDE_GLOB));

        LocalScanResult result = localVolume.scan(tempDir, filter);

        assertThat(result.snapshot().find(RelativePath.of(NESTED_TMP_RELATIVE_PATH))).as("excluded file must be dropped").isEmpty();
        assertThat(result.snapshot().find(RelativePath.of(ROOT_FILE_NAME))).as("non-excluded file must remain").isPresent();
    }

    @Test
    void scanOfEmptyDirectoryReturnsEmptySnapshot(@TempDir Path tempDir) {
        LocalScanResult result = localVolume.scan(tempDir, PathFilter.acceptAll());

        assertThat(result.snapshot().size()).as("empty directory must yield an empty snapshot").isZero();
    }

    @Test
    void scanIncludesFileWithUnmappableNameUnderSanitizedPathWithSourceOverride(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve(ROOT_FILE_NAME), new byte[ROOT_FILE_SIZE_BYTES]);
        Path unmappableFile = Files.write(tempDir.resolve(UNMAPPABLE_FILE_NAME), new byte[NESTED_FILE_SIZE_BYTES]);

        LocalScanResult result = localVolume.scan(tempDir, PathFilter.acceptAll());

        RelativePath sanitizedPath = RelativePath.of(UNMAPPABLE_FILE_NAME);
        assertThat(result.snapshot().size()).as("both the mappable and sanitized files must be scanned").isEqualTo(2);
        Optional<LocalFile> sanitizedFile = result.snapshot().find(sanitizedPath);
        assertThat(sanitizedFile).as("file with unmappable name must be present under its sanitized path").isPresent();
        assertThat(sanitizedFile.get().sizeBytes()).as("sanitized file size must match bytes written").isEqualTo(NESTED_FILE_SIZE_BYTES);
        assertThat(result.sourceOverrides())
                .as("source override must point at the real on-disk file")
                .containsEntry(sanitizedPath, unmappableFile);
    }

    @Test
    void scanAccumulatorSkipsRecordWhoseSanitizedPathCollidesWithAnAlreadySeenOne() {
        NioLocalVolume.ScanAccumulator accumulator = new NioLocalVolume.ScanAccumulator(Log.noOp());
        RelativePath collidingPath = RelativePath.of(UNMAPPABLE_FILE_NAME);
        Path firstSource = Path.of("first-source");
        Path secondSource = Path.of("second-source");

        accumulator.record(collidingPath, firstSource, true, ROOT_FILE_SIZE_BYTES, Instant.EPOCH);
        accumulator.record(collidingPath, secondSource, true, NESTED_FILE_SIZE_BYTES, Instant.EPOCH);

        LocalScanResult result = accumulator.toResult(0);
        assertThat(result.snapshot().size()).as("the colliding second record must be skipped").isEqualTo(1);
        assertThat(result.snapshot().find(collidingPath))
                .as("the first record must be kept")
                .hasValueSatisfying(file -> assertThat(file.sizeBytes()).isEqualTo(ROOT_FILE_SIZE_BYTES));
        assertThat(result.sourceOverrides())
                .as("the override must still point at the first record's source")
                .containsEntry(collidingPath, firstSource);
    }

    @Test
    void scanAccumulatorSkipsSanitizedRecordThatCollidesWithAnAlreadySeenNormalPath() {
        NioLocalVolume.ScanAccumulator accumulator = new NioLocalVolume.ScanAccumulator(Log.noOp());
        RelativePath collidingPath = RelativePath.of(ROOT_FILE_NAME);
        Path normalSource = Path.of("normal-source");
        Path sanitizedSource = Path.of("sanitized-source");

        accumulator.record(collidingPath, normalSource, false, ROOT_FILE_SIZE_BYTES, Instant.EPOCH);
        accumulator.record(collidingPath, sanitizedSource, true, NESTED_FILE_SIZE_BYTES, Instant.EPOCH);

        LocalScanResult result = accumulator.toResult(0);
        assertThat(result.snapshot().size()).as("the colliding sanitized record must be skipped").isEqualTo(1);
        assertThat(result.sourceOverrides())
                .as("no override must be recorded since the surviving entry is a normal file")
                .isEmpty();
    }

    @Test
    void scanOfMissingRootThrows(@TempDir Path tempDir) {
        Path missingRoot = tempDir.resolve("does-not-exist");

        assertThatExceptionOfType(LocalVolumeException.class)
                .isThrownBy(() -> localVolume.scan(missingRoot, PathFilter.acceptAll()));
    }

    @Test
    void scanLargeNestedTreeFindsEveryFileExactlyOnce(@TempDir Path tempDir) throws IOException {
        Map<RelativePath, Integer> expectedSizes = new HashMap<>();
        for (int dir = 0; dir < DIR_COUNT; dir++) {
            Path subDir = Files.createDirectories(tempDir.resolve("dir-" + dir).resolve("nested"));
            for (int file = 0; file < FILES_PER_DIR; file++) {
                int size = (dir + file) % 7 + 1;
                Files.write(subDir.resolve("file-" + file + ".bin"), new byte[size]);
                expectedSizes.put(RelativePath.of("dir-" + dir + "/nested/file-" + file + ".bin"), size);
            }
        }

        LocalScanResult result = localVolume.scan(tempDir, PathFilter.acceptAll());

        assertThat(result.snapshot().size())
                .as("snapshot must contain every created file exactly once")
                .isEqualTo(DIR_COUNT * FILES_PER_DIR);
        assertThat(result.snapshot().all())
                .allSatisfy(file -> assertThat(file.sizeBytes())
                        .as("size of " + file.path().value())
                        .isEqualTo(expectedSizes.get(file.path()).longValue()));
        assertThat(result.sourceOverrides()).as("no unmappable names were created").isEmpty();
    }

    @Test
    void scanUsesWalkerAttributesForSizeAndModifiedTime(@TempDir Path tempDir) throws IOException {
        Path file = Files.write(tempDir.resolve(ROOT_FILE_NAME), new byte[ROOT_FILE_SIZE_BYTES]);
        Instant modifiedAt = Instant.parse("2026-01-02T03:04:05Z");
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt));

        LocalScanResult result = localVolume.scan(tempDir, PathFilter.acceptAll());

        Optional<LocalFile> scanned = result.snapshot().find(RelativePath.of(ROOT_FILE_NAME));
        assertThat(scanned).as("file must be present").isPresent();
        assertThat(scanned.get().sizeBytes()).as("size must come from the walk attributes").isEqualTo(ROOT_FILE_SIZE_BYTES);
        assertThat(scanned.get().modifiedAt())
                .as("modification time must come from the walk attributes")
                .isEqualTo(modifiedAt);
    }

    @Test
    void mergeBatchesResolvesSanitizedCollisionsBySourcePathOrderRegardlessOfBatchOrder() {
        RelativePath collidingPath = RelativePath.of(UNMAPPABLE_FILE_NAME);
        NioLocalVolume.ScanEntry first = new NioLocalVolume.ScanEntry(
                collidingPath, Path.of("a-first-source"), true, ROOT_FILE_SIZE_BYTES, Instant.EPOCH);
        NioLocalVolume.ScanEntry second = new NioLocalVolume.ScanEntry(
                collidingPath, Path.of("b-second-source"), true, NESTED_FILE_SIZE_BYTES, Instant.EPOCH);
        NioLocalVolume.DirBatch firstBatch = new NioLocalVolume.DirBatch(List.of(first), List.of(), false);
        NioLocalVolume.DirBatch secondBatch = new NioLocalVolume.DirBatch(List.of(second), List.of(), false);
        RecordingLog forwardLog = new RecordingLog();
        RecordingLog reversedLog = new RecordingLog();

        LocalScanResult forward = NioLocalVolume.mergeBatches(new ArrayDeque<>(List.of(firstBatch, secondBatch)), forwardLog);
        LocalScanResult reversed = NioLocalVolume.mergeBatches(new ArrayDeque<>(List.of(secondBatch, firstBatch)), reversedLog);

        for (LocalScanResult result : List.of(forward, reversed)) {
            assertThat(result.snapshot().find(collidingPath))
                    .as("smallest source path must win regardless of batch order")
                    .hasValueSatisfying(file -> assertThat(file.sizeBytes()).isEqualTo(ROOT_FILE_SIZE_BYTES));
            assertThat(result.sourceOverrides()).containsEntry(collidingPath, Path.of("a-first-source"));
        }
        assertThat(forwardLog.warnings)
                .as("warning sequence must be identical regardless of batch order")
                .isEqualTo(reversedLog.warnings);
    }

    @Test
    void walkerDoesNotRetainSourcePathForMappableEntries(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve(ROOT_FILE_NAME), new byte[ROOT_FILE_SIZE_BYTES]);
        Path subDir = Files.createDirectory(tempDir.resolve(NESTED_DIR_NAME));
        Files.write(subDir.resolve(NESTED_FILE_NAME), new byte[NESTED_FILE_SIZE_BYTES]);
        Files.write(tempDir.resolve(UNMAPPABLE_FILE_NAME), new byte[NESTED_FILE_SIZE_BYTES]);

        NioLocalVolume.ParallelWalker walker =
                new NioLocalVolume.ParallelWalker(
                        tempDir, PathFilter.acceptAll(), Log.noOp(), TICK_TEST_THREADS, NioLocalVolume.SCAN_PROGRESS_TICK, true);
        List<NioLocalVolume.ScanEntry> allEntries = new ArrayList<>();
        for (NioLocalVolume.DirBatch batch : walker.walk()) {
            allEntries.addAll(batch.entries());
        }

        assertThat(allEntries)
                .as("every mappable entry must not retain an absolute source path")
                .filteredOn(entry -> !entry.unmappable())
                .allSatisfy(entry -> assertThat(entry.source())
                        .as("mappable entry source for " + entry.relativePath().value())
                        .isNull());
        assertThat(allEntries)
                .as("the unmappable entry must still retain its real source path")
                .filteredOn(NioLocalVolume.ScanEntry::unmappable)
                .hasSize(1)
                .allSatisfy(entry -> assertThat(entry.source())
                        .as("unmappable entry source must be present")
                        .isNotNull());
    }

    @Test
    void mergeBatchesEmitsWarningsInterleavedByPathAcrossMultipleIndependentCollisions() {
        RelativePath firstCollidingPath = RelativePath.of("A-" + UNMAPPABLE_FILE_NAME);
        RelativePath secondCollidingPath = RelativePath.of("Z-" + UNMAPPABLE_FILE_NAME);
        NioLocalVolume.ScanEntry firstWinner = new NioLocalVolume.ScanEntry(
                firstCollidingPath, Path.of("a1"), true, ROOT_FILE_SIZE_BYTES, Instant.EPOCH);
        NioLocalVolume.ScanEntry firstLoser = new NioLocalVolume.ScanEntry(
                firstCollidingPath, Path.of("a2"), true, NESTED_FILE_SIZE_BYTES, Instant.EPOCH);
        NioLocalVolume.ScanEntry secondWinner = new NioLocalVolume.ScanEntry(
                secondCollidingPath, Path.of("z1"), true, ROOT_FILE_SIZE_BYTES, Instant.EPOCH);
        NioLocalVolume.ScanEntry secondLoser = new NioLocalVolume.ScanEntry(
                secondCollidingPath, Path.of("z2"), true, NESTED_FILE_SIZE_BYTES, Instant.EPOCH);
        NioLocalVolume.DirBatch batch = new NioLocalVolume.DirBatch(
                List.of(secondLoser, firstLoser, secondWinner, firstWinner), List.of(), false);
        RecordingLog recordingLog = new RecordingLog();

        NioLocalVolume.mergeBatches(new ArrayDeque<>(List.of(batch)), recordingLog);

        assertThat(recordingLog.warnings)
                .as("warnings must be grouped by relative path in sorted order, sanitized before skip within a path")
                .containsExactly(
                        NioLocalVolume.SANITIZED_WARNING_PREFIX + firstCollidingPath.value(),
                        NioLocalVolume.SKIP_WARNING_PREFIX + Path.of("a2"),
                        NioLocalVolume.SANITIZED_WARNING_PREFIX + secondCollidingPath.value(),
                        NioLocalVolume.SKIP_WARNING_PREFIX + Path.of("z2"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void scanResolvesBackslashNameCollisionWithRealNestedPathWithoutNullWarning(@TempDir Path tempDir) throws IOException {
        Path nestedDir = Files.createDirectory(tempDir.resolve(BACKSLASH_COLLISION_DIR_NAME));
        Files.write(
                nestedDir.resolve(BACKSLASH_COLLISION_NESTED_FILE_NAME),
                new byte[BACKSLASH_COLLISION_REAL_FILE_SIZE_BYTES]);
        Path backslashFile =
                Files.write(tempDir.resolve(BACKSLASH_COLLISION_FILE_NAME), new byte[BACKSLASH_COLLISION_LOSER_FILE_SIZE_BYTES]);
        RecordingLog recordingLog = new RecordingLog();
        NioLocalVolume volume = new NioLocalVolume(recordingLog);

        LocalScanResult result = volume.scan(tempDir, PathFilter.acceptAll());

        RelativePath collidingPath = RelativePath.of(BACKSLASH_COLLISION_RELATIVE_PATH);
        assertThat(result.snapshot().size())
                .as("only the winner of the backslash/real-directory collision must survive")
                .isEqualTo(1);
        assertThat(result.snapshot().find(collidingPath))
                .as("the real nested directory file must win over the backslash-named file")
                .hasValueSatisfying(file -> assertThat(file.sizeBytes()).isEqualTo(BACKSLASH_COLLISION_REAL_FILE_SIZE_BYTES));
        assertThat(result.sourceOverrides()).as("the winning mappable file needs no override").isEmpty();
        assertThat(recordingLog.warnings)
                .as("the losing backslash-named file must be logged by its real path, never null")
                .containsExactly(NioLocalVolume.SKIP_WARNING_PREFIX + backslashFile);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void scanResolvesFourWayBackslashCollisionWithoutThrowing(@TempDir Path tempDir) throws IOException {
        Path dirA = Files.createDirectory(tempDir.resolve(QUAD_COLLISION_DIR_A_NAME));
        Path dirAB = Files.createDirectory(dirA.resolve(QUAD_COLLISION_DIR_B_NAME));
        Files.write(dirAB.resolve(QUAD_COLLISION_LEAF_FILE_NAME), new byte[QUAD_COLLISION_REAL_FILE_SIZE_BYTES]);
        Path bBackslashCInA = Files.write(dirA.resolve(QUAD_COLLISION_B_BACKSLASH_C_NAME), new byte[1]);
        Path dirABackslashB = Files.createDirectory(tempDir.resolve(QUAD_COLLISION_A_BACKSLASH_B_DIR_NAME));
        Path cInDirABackslashB = Files.write(dirABackslashB.resolve(QUAD_COLLISION_LEAF_FILE_NAME), new byte[1]);
        Path aBackslashBBackslashC =
                Files.write(tempDir.resolve(QUAD_COLLISION_A_BACKSLASH_B_BACKSLASH_C_NAME), new byte[1]);
        RecordingLog recordingLog = new RecordingLog();
        NioLocalVolume volume = new NioLocalVolume(recordingLog);

        LocalScanResult result = volume.scan(tempDir, PathFilter.acceptAll());

        RelativePath collidingPath = RelativePath.of(QUAD_COLLISION_RELATIVE_PATH);
        assertThat(result.snapshot().size())
                .as("only the winner of the four-way collision must survive")
                .isEqualTo(1);
        assertThat(result.snapshot().find(collidingPath))
                .as("the real three-level directory file must win over all backslash-named contenders")
                .hasValueSatisfying(file -> assertThat(file.sizeBytes()).isEqualTo(QUAD_COLLISION_REAL_FILE_SIZE_BYTES));
        assertThat(result.sourceOverrides()).as("the winning mappable file needs no override").isEmpty();
        assertThat(recordingLog.warnings)
                .as("every losing backslash-named contender must be logged by its real path, never null, and the scan must not throw")
                .containsExactlyInAnyOrder(
                        NioLocalVolume.SKIP_WARNING_PREFIX + bBackslashCInA,
                        NioLocalVolume.SKIP_WARNING_PREFIX + cInDirABackslashB,
                        NioLocalVolume.SKIP_WARNING_PREFIX + aBackslashBBackslashC);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void scanSkipsFilesWithInvalidRelativePathNamesAndKeepsScanningSiblings(@TempDir Path tempDir) throws IOException {
        Path rootAbsoluteInvalid;
        Path nestedParentTraversalInvalid;
        try {
            rootAbsoluteInvalid = Files.write(tempDir.resolve(ABSOLUTE_INVALID_NAME), new byte[ROOT_FILE_SIZE_BYTES]);
            Path invalidNameSubDir = Files.createDirectory(tempDir.resolve(INVALID_NAME_SUB_DIR));
            nestedParentTraversalInvalid =
                    Files.write(invalidNameSubDir.resolve(PARENT_TRAVERSAL_INVALID_NAME), new byte[NESTED_FILE_SIZE_BYTES]);
            Files.write(invalidNameSubDir.resolve(INVALID_NAME_SIBLING_FILE_NAME), new byte[INVALID_NAME_SIBLING_FILE_SIZE_BYTES]);
            Files.write(tempDir.resolve(ROOT_FILE_NAME), new byte[ROOT_FILE_SIZE_BYTES]);
        } catch (IOException e) {
            assumeTrue(false, "filesystem cannot create backslash-containing file names: " + e);
            return;
        }
        RecordingLog recordingLog = new RecordingLog();
        NioLocalVolume volume = new NioLocalVolume(recordingLog);

        LocalScanResult result = volume.scan(tempDir, PathFilter.acceptAll());

        assertThat(result.snapshot().size())
                .as("only the two invalid-named files must be excluded from the snapshot")
                .isEqualTo(2);
        assertThat(result.snapshot().find(RelativePath.of(ROOT_FILE_NAME)))
                .as("the root sibling file must still be scanned")
                .isPresent();
        assertThat(result.snapshot().find(RelativePath.of(INVALID_NAME_NESTED_SIBLING_RELATIVE_PATH)))
                .as("the nested sibling file must still be scanned")
                .isPresent();
        assertThat(recordingLog.warnings)
                .as("both invalid-named files must be logged by their real absolute path")
                .containsExactlyInAnyOrder(
                        NioLocalVolume.SKIP_WARNING_PREFIX + rootAbsoluteInvalid,
                        NioLocalVolume.SKIP_WARNING_PREFIX + nestedParentTraversalInvalid);
    }

    @Test
    void scanEmitsProgressTickAtEachThresholdMultipleExactlyOnce(@TempDir Path tempDir) throws IOException {
        RecordingLog recordingLog = new RecordingLog();
        NioLocalVolume tickingVolume = new NioLocalVolume(recordingLog, TICK_TEST_THREADS, TICK_TEST_THRESHOLD);
        for (int dir = 0; dir < 5; dir++) {
            Path subDir = Files.createDirectory(tempDir.resolve("dir-" + dir));
            for (int file = 0; file < 5; file++) {
                Files.write(subDir.resolve("file-" + file), new byte[1]);
            }
        }

        tickingVolume.scan(tempDir, PathFilter.acceptAll());

        String firstTick = NioLocalVolume.SCAN_PROGRESS_MESSAGE_PREFIX + TICK_TEST_THRESHOLD
                + NioLocalVolume.SCAN_PROGRESS_MESSAGE_SUFFIX;
        String secondTick = NioLocalVolume.SCAN_PROGRESS_MESSAGE_PREFIX + (2 * TICK_TEST_THRESHOLD)
                + NioLocalVolume.SCAN_PROGRESS_MESSAGE_SUFFIX;
        assertThat(recordingLog.debugs).containsExactlyInAnyOrder(firstTick, secondTick);
    }

    @Test
    void defaultConstructorUsesDefaultScanThreads() {
        assertThat(new NioLocalVolume(Log.noOp()).threadCount())
                .as("the single-argument constructor must use the built-in scan thread default")
                .isEqualTo(NioLocalVolume.DEFAULT_SCAN_THREADS);
    }

    @Test
    void scanThreadsConstructorUsesGivenThreadCount() {
        assertThat(new NioLocalVolume(Log.noOp(), CUSTOM_SCAN_THREADS).threadCount())
                .as("the scan-threads constructor must use the given thread count")
                .isEqualTo(CUSTOM_SCAN_THREADS);
    }

    @Test
    void scanRunsOnAtMostTheConfiguredNumberOfWorkerThreads(@TempDir Path tempDir) throws IOException {
        ThreadRecordingLog threadLog = new ThreadRecordingLog();
        NioLocalVolume boundedVolume = new NioLocalVolume(threadLog, BOUND_TEST_THREADS, EVERY_FILE_TICK);
        for (int dir = 0; dir < BOUND_TEST_DIR_COUNT; dir++) {
            Path subDir = Files.createDirectory(tempDir.resolve("dir-" + dir));
            for (int file = 0; file < BOUND_TEST_FILES_PER_DIR; file++) {
                Files.write(subDir.resolve("file-" + file), new byte[1]);
            }
        }

        boundedVolume.scan(tempDir, PathFilter.acceptAll());

        assertThat(threadLog.tickThreadNames)
                .as("every per-file tick must run on a bounded pool of named scan workers")
                .isNotEmpty()
                .allSatisfy(name -> assertThat(name).startsWith(NioLocalVolume.SCAN_THREAD_NAME_PREFIX))
                .hasSizeLessThanOrEqualTo(BOUND_TEST_THREADS);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void scanWithAbsoluteStatFallbackProducesSameResultAsRelativeStats(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve(ROOT_FILE_NAME), new byte[ROOT_FILE_SIZE_BYTES]);
        Path subDir = Files.createDirectory(tempDir.resolve(NESTED_DIR_NAME));
        Files.write(subDir.resolve(NESTED_FILE_NAME), new byte[NESTED_FILE_SIZE_BYTES]);
        Files.write(tempDir.resolve(UNMAPPABLE_FILE_NAME), new byte[NESTED_FILE_SIZE_BYTES]);
        Files.createSymbolicLink(tempDir.resolve("linked-file.bin"), tempDir.resolve(ROOT_FILE_NAME));
        NioLocalVolume fallbackVolume =
                new NioLocalVolume(Log.noOp(), TICK_TEST_THREADS, NioLocalVolume.SCAN_PROGRESS_TICK, false);

        LocalScanResult relativeStats = localVolume.scan(tempDir, PathFilter.acceptAll());
        LocalScanResult absoluteStats = fallbackVolume.scan(tempDir, PathFilter.acceptAll());

        assertThat(absoluteStats.snapshot().all())
                .as("both stat strategies must produce identical entries, sizes, and timestamps")
                .isEqualTo(relativeStats.snapshot().all());
        assertThat(absoluteStats.sourceOverrides())
                .as("both stat strategies must produce identical source overrides")
                .isEqualTo(relativeStats.sourceOverrides());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void scanAbortsWhenDirectoryIsUnreadable(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve(ROOT_FILE_NAME), new byte[ROOT_FILE_SIZE_BYTES]);
        Path lockedDir = Files.createDirectory(tempDir.resolve("locked"));
        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(lockedDir);
        Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("---------"));
        assumeTrue(!Files.isReadable(lockedDir), "cannot revoke read permission (running as root?)");
        try {
            assertThatExceptionOfType(LocalVolumeException.class)
                    .isThrownBy(() -> localVolume.scan(tempDir, PathFilter.acceptAll()));
        } finally {
            Files.setPosixFilePermissions(lockedDir, originalPermissions);
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void scanDoesNotDescendIntoSymlinkedDirectoryAndSkipsSymlinkedFile(@TempDir Path tempDir) throws IOException {
        Path realFile = Files.write(tempDir.resolve(ROOT_FILE_NAME), new byte[ROOT_FILE_SIZE_BYTES]);
        Path outsideDir = Files.createDirectory(tempDir.resolveSibling("outside-" + tempDir.getFileName()));
        Files.write(outsideDir.resolve("inside-link-target.bin"), new byte[NESTED_FILE_SIZE_BYTES]);
        Files.createSymbolicLink(tempDir.resolve("linked-dir"), outsideDir);
        Files.createSymbolicLink(tempDir.resolve("linked-file.bin"), realFile);

        LocalScanResult result = localVolume.scan(tempDir, PathFilter.acceptAll());

        assertThat(result.snapshot().size())
                .as("only the real regular file must be scanned; symlinks are skipped, not followed")
                .isEqualTo(1);
        assertThat(result.snapshot().find(RelativePath.of(ROOT_FILE_NAME)))
                .as("the real file must be present")
                .isPresent();
    }

    @Test
    void readAttributesOrSkipWarnsAndReturnsEmptyForVanishedEntry(@TempDir Path tempDir) throws IOException {
        Path vanishedEntry = Files.write(tempDir.resolve(ROOT_FILE_NAME), new byte[ROOT_FILE_SIZE_BYTES]);
        Files.delete(vanishedEntry);
        RecordingLog recordingLog = new RecordingLog();
        NioLocalVolume.ParallelWalker walker =
                new NioLocalVolume.ParallelWalker(
                        tempDir, PathFilter.acceptAll(), recordingLog, TICK_TEST_THREADS, NioLocalVolume.SCAN_PROGRESS_TICK, true);

        Optional<BasicFileAttributes> attributes = walker.readAttributesOrSkip(null, vanishedEntry);

        assertThat(attributes).as("a vanished entry must yield no attributes").isEmpty();
        assertThat(recordingLog.warnings)
                .as("the vanished entry must be logged with the vanished-entry warning")
                .containsExactly(NioLocalVolume.VANISHED_ENTRY_WARNING_PREFIX + vanishedEntry);
    }

    @Test
    void processDirectoryOnVanishedNonRootDirectoryWarnsAndStillEnqueuesABatch(@TempDir Path tempDir) throws IOException {
        Path vanishedDir = tempDir.resolve("does-not-exist-sub");
        RecordingLog recordingLog = new RecordingLog();
        NioLocalVolume.ParallelWalker walker =
                new NioLocalVolume.ParallelWalker(
                        tempDir, PathFilter.acceptAll(), recordingLog, TICK_TEST_THREADS, NioLocalVolume.SCAN_PROGRESS_TICK, true);

        walker.processDirectory(vanishedDir);

        assertThat(recordingLog.warnings)
                .as("a vanished non-root directory must be logged with the vanished-directory warning, not abort")
                .containsExactly(NioLocalVolume.VANISHED_DIRECTORY_WARNING_PREFIX + vanishedDir);
        assertThat(walker.batches())
                .as("a batch must still be enqueued for the vanished directory so the walk can complete")
                .hasSize(1);
    }

    @Test
    void processDirectoryOnVanishedNonRootDirectoryKeepsEntriesAlreadyCollectedBeforeTheVanish(@TempDir Path tempDir)
            throws IOException {
        Path vanishedDir = tempDir.resolve("does-not-exist-sub-partial");
        RelativePath preCollectedPath = RelativePath.of(ROOT_FILE_NAME);
        NioLocalVolume.ScanEntry preCollectedEntry =
                new NioLocalVolume.ScanEntry(preCollectedPath, null, false, ROOT_FILE_SIZE_BYTES, Instant.EPOCH);
        Path preCollectedSkip = tempDir.resolve(UNMAPPABLE_FILE_NAME);
        List<NioLocalVolume.ScanEntry> preCollectedEntries = new ArrayList<>(List.of(preCollectedEntry));
        List<Path> preCollectedSkips = new ArrayList<>(List.of(preCollectedSkip));
        NioLocalVolume.ParallelWalker walker =
                new NioLocalVolume.ParallelWalker(
                        tempDir, PathFilter.acceptAll(), Log.noOp(), TICK_TEST_THREADS, NioLocalVolume.SCAN_PROGRESS_TICK, true);

        // Simulates path (c): entries already collected from a partially-iterated
        // stream before a DirectoryIteratorException/NoSuchFileException interrupts
        // it. Pre-seeding the lists exercises the exact same catch-and-preserve code
        // that a real mid-iteration vanish would run, without racing a real delete.
        walker.processDirectory(vanishedDir, preCollectedEntries, preCollectedSkips);

        NioLocalVolume.DirBatch batch = walker.batches().poll();
        assertThat(batch).as("a batch must be enqueued even though the directory vanished").isNotNull();
        assertThat(batch.entries())
                .as("entries collected before the vanish must be kept in the batch, not discarded")
                .containsExactly(preCollectedEntry);
        assertThat(batch.unmappableSkips())
                .as("skips collected before the vanish must be kept in the batch, not discarded")
                .containsExactly(preCollectedSkip);
    }

    @Test
    void processDirectoryOnVanishedNonRootDirectoryMarksTheBatchAsAVanishedDirectory(@TempDir Path tempDir) throws IOException {
        Path vanishedDir = tempDir.resolve("does-not-exist-sub-marker");
        NioLocalVolume.ParallelWalker walker = new NioLocalVolume.ParallelWalker(
                tempDir, PathFilter.acceptAll(), Log.noOp(), TICK_TEST_THREADS, NioLocalVolume.SCAN_PROGRESS_TICK, true);

        walker.processDirectory(vanishedDir);

        NioLocalVolume.DirBatch batch = walker.batches().poll();
        assertThat(batch).as("a batch must be enqueued for the vanished directory").isNotNull();
        assertThat(batch.vanishedDirectory())
                .as("a vanished non-root directory's batch must be marked as vanished so the count can be aggregated")
                .isTrue();
    }

    @Test
    void processDirectoryOnVanishedNonRootDirectoryViaPartialIterationMarksTheBatchAsAVanishedDirectory(@TempDir Path tempDir)
            throws IOException {
        Path vanishedDir = tempDir.resolve("does-not-exist-sub-partial-marker");
        NioLocalVolume.ParallelWalker walker = new NioLocalVolume.ParallelWalker(
                tempDir, PathFilter.acceptAll(), Log.noOp(), TICK_TEST_THREADS, NioLocalVolume.SCAN_PROGRESS_TICK, true);

        // Simulates the DirectoryIteratorException-unwrap path (path c): the shared
        // catch block in processDirectory must mark the batch vanished regardless of
        // whether the NoSuchFileException came from opening the stream or from
        // mid-iteration.
        walker.processDirectory(vanishedDir, new ArrayList<>(), new ArrayList<>());

        NioLocalVolume.DirBatch batch = walker.batches().poll();
        assertThat(batch).as("a batch must be enqueued for the vanished directory").isNotNull();
        assertThat(batch.vanishedDirectory())
                .as("the vanished-directory catch must mark the batch regardless of which code path threw")
                .isTrue();
    }

    @Test
    void processDirectoryOnAnExistingDirectoryDoesNotMarkTheBatchAsVanished(@TempDir Path tempDir) throws IOException {
        NioLocalVolume.ParallelWalker walker = new NioLocalVolume.ParallelWalker(
                tempDir, PathFilter.acceptAll(), Log.noOp(), TICK_TEST_THREADS, NioLocalVolume.SCAN_PROGRESS_TICK, true);

        walker.processDirectory(tempDir);

        NioLocalVolume.DirBatch batch = walker.batches().poll();
        assertThat(batch).as("a batch must be enqueued for the existing directory").isNotNull();
        assertThat(batch.vanishedDirectory()).as("an existing directory's batch must not be marked as vanished").isFalse();
    }

    @Test
    void mergeBatchesSumsVanishedDirectoryCountAcrossBatches() {
        NioLocalVolume.DirBatch vanishedBatch = new NioLocalVolume.DirBatch(List.of(), List.of(), true);
        NioLocalVolume.DirBatch anotherVanishedBatch = new NioLocalVolume.DirBatch(List.of(), List.of(), true);
        NioLocalVolume.DirBatch normalBatch = new NioLocalVolume.DirBatch(List.of(), List.of(), false);
        RecordingLog recordingLog = new RecordingLog();

        LocalScanResult result = NioLocalVolume.mergeBatches(
                new ArrayDeque<>(List.of(vanishedBatch, normalBatch, anotherVanishedBatch)), recordingLog);

        assertThat(result.vanishedDirectoryCount())
                .as("the result must count every batch marked as a vanished directory")
                .isEqualTo(2);
    }

    @Test
    void scanWithNoVanishedDirectoriesReportsZeroVanishedDirectoryCount(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve(ROOT_FILE_NAME), new byte[ROOT_FILE_SIZE_BYTES]);
        NioLocalVolume localVolume = new NioLocalVolume(Log.noOp(), TICK_TEST_THREADS);

        LocalScanResult result = localVolume.scan(tempDir, PathFilter.acceptAll());

        assertThat(result.vanishedDirectoryCount()).as("a scan with no vanished directories must report zero").isEqualTo(0);
    }

    @Test
    void visitEntryReturnsTrueAndSubmitsNothingWhenTheEntryVanishesBeforeItsTypeCanBeDetermined(@TempDir Path tempDir)
            throws IOException {
        Path vanishedEntry = Files.write(tempDir.resolve(ROOT_FILE_NAME), new byte[ROOT_FILE_SIZE_BYTES]);
        Files.delete(vanishedEntry);
        NioLocalVolume.ParallelWalker walker = new NioLocalVolume.ParallelWalker(
                tempDir, PathFilter.acceptAll(), Log.noOp(), TICK_TEST_THREADS, NioLocalVolume.SCAN_PROGRESS_TICK, true);
        List<NioLocalVolume.ScanEntry> entries = new ArrayList<>();
        List<Path> skips = new ArrayList<>();

        // Reproduces the readdir->stat race F1: the entry was listed but its stat
        // failed with NoSuchFileException, so its type (file vs. directory) could
        // never be determined. The entry might have been a directory whose still-real
        // subtree would silently vanish from the snapshot, so this must be treated as
        // poison for delete planning, unlike an entry that stats successfully as a
        // regular file and later vanishes (an observed point deletion).
        boolean unknownTypeVanish = walker.visitEntry(null, vanishedEntry, entries, skips);

        assertThat(unknownTypeVanish)
                .as("an entry whose type could not be determined must be treated as poison")
                .isTrue();
        assertThat(entries).as("nothing must be recorded for an entry of unknown type").isEmpty();
        assertThat(skips).as("nothing must be recorded for an entry of unknown type").isEmpty();
        assertThat(walker.batches())
                .as("visiting a single entry must not itself submit any directory to walk")
                .isEmpty();
    }

    @Test
    void visitEntryReturnsFalseForAnEntryWhoseTypeIsSuccessfullyDetermined(@TempDir Path tempDir) throws IOException {
        Path realFile = Files.write(tempDir.resolve(ROOT_FILE_NAME), new byte[ROOT_FILE_SIZE_BYTES]);
        NioLocalVolume.ParallelWalker walker = new NioLocalVolume.ParallelWalker(
                tempDir, PathFilter.acceptAll(), Log.noOp(), TICK_TEST_THREADS, NioLocalVolume.SCAN_PROGRESS_TICK, true);
        List<NioLocalVolume.ScanEntry> entries = new ArrayList<>();
        List<Path> skips = new ArrayList<>();

        boolean unknownTypeVanish = walker.visitEntry(null, realFile, entries, skips);

        assertThat(unknownTypeVanish).as("an entry whose type was determined must not be treated as poison").isFalse();
        assertThat(entries).as("the successfully-stat'd regular file must still be recorded").hasSize(1);
    }

    @Test
    void walkEntriesReturnsTrueWhenAnyListedEntryVanishesBeforeItsTypeCanBeDetermined(@TempDir Path tempDir) throws IOException {
        Path vanishedEntry = Files.write(tempDir.resolve(ROOT_FILE_NAME), new byte[ROOT_FILE_SIZE_BYTES]);
        Files.delete(vanishedEntry);
        Path realFile = Files.write(tempDir.resolve(NESTED_FILE_NAME), new byte[NESTED_FILE_SIZE_BYTES]);
        NioLocalVolume.ParallelWalker walker = new NioLocalVolume.ParallelWalker(
                tempDir, PathFilter.acceptAll(), Log.noOp(), TICK_TEST_THREADS, NioLocalVolume.SCAN_PROGRESS_TICK, true);
        List<NioLocalVolume.ScanEntry> entries = new ArrayList<>();
        List<Path> skips = new ArrayList<>();

        boolean unknownTypeVanish = walker.walkEntries(List.of(realFile, vanishedEntry), null, entries, skips);

        assertThat(unknownTypeVanish)
                .as("one poisoned entry among several listed entries must still poison the whole batch")
                .isTrue();
        assertThat(entries).as("the real file must still be recorded despite the sibling vanish").hasSize(1);
    }

    @Test
    void walkEntriesReturnsFalseWhenNoListedEntryVanishes(@TempDir Path tempDir) throws IOException {
        Path realFile = Files.write(tempDir.resolve(ROOT_FILE_NAME), new byte[ROOT_FILE_SIZE_BYTES]);
        NioLocalVolume.ParallelWalker walker = new NioLocalVolume.ParallelWalker(
                tempDir, PathFilter.acceptAll(), Log.noOp(), TICK_TEST_THREADS, NioLocalVolume.SCAN_PROGRESS_TICK, true);
        List<NioLocalVolume.ScanEntry> entries = new ArrayList<>();
        List<Path> skips = new ArrayList<>();

        boolean unknownTypeVanish = walker.walkEntries(List.of(realFile), null, entries, skips);

        assertThat(unknownTypeVanish).as("a directory with no vanished entries must not be poisoned").isFalse();
    }

    @Test
    void walkThrowsLocalVolumeExceptionWhenRootVanishesMidWalk(@TempDir Path tempDir) throws IOException {
        Path vanishingRoot = tempDir.resolve("vanishing-root");
        Files.createDirectory(vanishingRoot);
        Files.delete(vanishingRoot);
        NioLocalVolume.ParallelWalker walker = new NioLocalVolume.ParallelWalker(
                vanishingRoot, PathFilter.acceptAll(), Log.noOp(), TICK_TEST_THREADS, NioLocalVolume.SCAN_PROGRESS_TICK, true);

        assertThatExceptionOfType(LocalVolumeException.class)
                .as("the scan root vanishing must still abort loudly")
                .isThrownBy(walker::walk);
    }

    private static final class ThreadRecordingLog implements Log {

        private final Set<String> tickThreadNames = ConcurrentHashMap.newKeySet();

        @Override
        public void info(String message) {}

        @Override
        public void warn(String message) {}

        @Override
        public void error(String message) {}

        @Override
        public void debug(String message) {
            tickThreadNames.add(Thread.currentThread().getName());
        }
    }

    private static final class RecordingLog implements Log {

        private final List<String> warnings = new ArrayList<>();
        private final List<String> debugs = new ArrayList<>();

        @Override
        public void info(String message) {}

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        @Override
        public void error(String message) {}

        @Override
        public void debug(String message) {
            debugs.add(message);
        }
    }
}
