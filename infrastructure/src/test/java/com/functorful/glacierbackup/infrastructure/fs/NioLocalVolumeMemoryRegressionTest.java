package com.functorful.glacierbackup.infrastructure.fs;

import static org.assertj.core.api.Assertions.assertThat;

import com.functorful.glacierbackup.application.port.LocalScanResult;
import com.functorful.glacierbackup.application.port.Log;
import com.functorful.glacierbackup.domain.RelativePath;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the per-entry memory footprint of {@link NioLocalVolume#mergeBatches}.
 *
 * <p>Before the fix, every scanned file (mappable and unmappable alike) retained a full
 * absolute {@link Path} in {@code ScanEntry}, and {@code mergeBatches} additionally copied
 * every entry into one global sorted {@code List} before feeding a {@code ScanAccumulator}
 * that held a separate {@code HashSet<RelativePath>} alongside the growing file list —
 * several full-scan-sized transient copies alive at once, on top of the retained-Path cost.
 * That footprint scaled badly enough to get the whole JVM SIGKILLed by the kernel on a
 * real-world scan of ~1,000,000 files, even with a 4g heap cap already in place.
 *
 * <p>This test drives ~1,000,000 synthetic entries — mostly normally-named, with a sprinkle of
 * unmappable ones — through the real {@link NioLocalVolume#mergeBatches} entry point, batched
 * the same way {@code ParallelWalker} produces them (~10,000 entries per {@code DirBatch}, in a
 * queue), and mirrors exactly what the real walker now hands it: a {@code null} source Path for
 * every mappable entry.
 *
 * <p>Runs in a dedicated, heap-capped forked JVM (see the {@code memoryTest} Gradle task,
 * capped at 320m). That cap is non-vacuous: it comfortably and reliably passes against this
 * fixed implementation, but reliably fails ({@code OutOfMemoryError}, well before 320m, per a
 * measured sweep of heap sizes) against the pre-fix implementation fed the equivalent
 * workload (pre-fix {@code ScanEntry} always carries a real source {@code Path}, even for
 * mappable entries, and pre-fix {@code mergeBatches} copies every entry into a second sorted
 * list before deduplicating through a separate {@code HashSet}).
 */
@Tag("memory")
class NioLocalVolumeMemoryRegressionTest {

    private static final int TOTAL_ENTRIES = 1_000_000;
    private static final int ENTRIES_PER_BATCH = 10_000;
    private static final int UNMAPPABLE_EVERY_NTH = 100;
    private static final long SYNTHETIC_SIZE_BYTES = 4_096;
    private static final char REPLACEMENT_CHAR = '�';
    private static final String MAPPABLE_NAME_PREFIX = "dir-";
    private static final String UNMAPPABLE_NAME_PREFIX = "sanitized-" + REPLACEMENT_CHAR;
    private static final String SYNTHETIC_SOURCE_ROOT = "/data/root/";
    private static final int EXPECTED_UNMAPPABLE_COUNT = TOTAL_ENTRIES / UNMAPPABLE_EVERY_NTH;

    @Test
    void mergingOneMillionSyntheticEntriesStaysWithinTheHeapCap() {
        Queue<NioLocalVolume.DirBatch> batches = new ConcurrentLinkedQueue<>();
        int emitted = 0;
        while (emitted < TOTAL_ENTRIES) {
            int batchSize = Math.min(ENTRIES_PER_BATCH, TOTAL_ENTRIES - emitted);
            List<NioLocalVolume.ScanEntry> entries = new ArrayList<>(batchSize);
            for (int i = 0; i < batchSize; i++, emitted++) {
                boolean unmappable = emitted % UNMAPPABLE_EVERY_NTH == 0;
                String relative = (unmappable ? UNMAPPABLE_NAME_PREFIX : MAPPABLE_NAME_PREFIX)
                        + "level-" + (emitted / 1000)
                        + "/subdir-" + (emitted % 1000)
                        + "/file-" + emitted + "-abcdefghijklmnopqrstuvwxyz.bin";
                RelativePath relativePath = RelativePath.of(relative);
                // Mirrors NioLocalVolume.ParallelWalker#visitFile: only unmappable
                // entries retain a real source Path.
                Path source = unmappable ? Path.of(SYNTHETIC_SOURCE_ROOT + relative) : null;
                entries.add(new NioLocalVolume.ScanEntry(
                        relativePath, source, unmappable, SYNTHETIC_SIZE_BYTES, Instant.EPOCH));
            }
            batches.add(new NioLocalVolume.DirBatch(entries, List.of(), false));
        }

        LocalScanResult result = NioLocalVolume.mergeBatches(batches, Log.noOp());

        assertThat(result.snapshot().size()).as("every synthetic entry must survive the merge").isEqualTo(TOTAL_ENTRIES);
        assertThat(result.sourceOverrides())
                .as("only the sprinkled unmappable entries must carry a source override")
                .hasSize(EXPECTED_UNMAPPABLE_COUNT);
    }
}
