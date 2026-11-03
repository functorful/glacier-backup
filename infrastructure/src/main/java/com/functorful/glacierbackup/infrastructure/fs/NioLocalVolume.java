package com.functorful.glacierbackup.infrastructure.fs;

import com.functorful.glacierbackup.application.port.LocalScanResult;
import com.functorful.glacierbackup.application.port.LocalVolume;
import com.functorful.glacierbackup.application.port.Log;
import com.functorful.glacierbackup.domain.InvalidRelativePathException;
import com.functorful.glacierbackup.domain.LocalFile;
import com.functorful.glacierbackup.domain.LocalSnapshot;
import com.functorful.glacierbackup.domain.PathFilter;
import com.functorful.glacierbackup.domain.RelativePath;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class NioLocalVolume implements LocalVolume {

    static final String SKIP_WARNING_PREFIX = "skipping file with unmappable name: ";
    static final String SANITIZED_WARNING_PREFIX = "backing up file with unmappable name as: ";
    static final String VANISHED_DIRECTORY_WARNING_PREFIX = "skipping vanished directory: ";
    // Not "file": at this point the entry's type could not be determined (its stat
    // failed before isDirectory()/isRegularFile() could be checked), so it may well
    // have been a directory. See ParallelWalker#visitEntry.
    static final String VANISHED_ENTRY_WARNING_PREFIX = "skipping vanished entry: ";
    static final String SCAN_PROGRESS_MESSAGE_PREFIX = "scanned ";
    static final String SCAN_PROGRESS_MESSAGE_SUFFIX = " files";
    static final long SCAN_PROGRESS_TICK = 10_000;
    // The scan is seek-bound blocking metadata I/O, so useful concurrency tracks the
    // disk queue depth of the underlying array, not availableProcessors(): threads
    // spend their time waiting on lstat, and spinning-disk arrays service far more
    // concurrent seeks than a small NAS has cores.
    public static final int DEFAULT_SCAN_THREADS = 32;
    public static final int MIN_SCAN_THREADS = 1;
    public static final int MAX_SCAN_THREADS = 128;
    static final String SCAN_THREAD_NAME_PREFIX = "scan-worker-";
    private static final char REPLACEMENT_CHAR = '�';

    private final Log log;
    private final int threadCount;
    private final long progressTick;
    private final boolean relativeStats;

    public NioLocalVolume(Log log) {
        this(log, DEFAULT_SCAN_THREADS);
    }

    public NioLocalVolume(Log log, int scanThreads) {
        this(log, scanThreads, SCAN_PROGRESS_TICK);
    }

    NioLocalVolume(Log log, int threadCount, long progressTick) {
        this(log, threadCount, progressTick, true);
    }

    NioLocalVolume(Log log, int threadCount, long progressTick, boolean relativeStats) {
        this.log = log;
        this.threadCount = threadCount;
        this.progressTick = progressTick;
        this.relativeStats = relativeStats;
    }

    int threadCount() {
        return threadCount;
    }

    @Override
    public LocalScanResult scan(Path root, PathFilter filter) {
        if (!Files.isDirectory(root)) {
            throw new LocalVolumeException("Root is not an existing directory: " + root);
        }
        Queue<DirBatch> batches = new ParallelWalker(root, filter, log, threadCount, progressTick, relativeStats).walk();
        return mergeBatches(batches, log);
    }

    /**
     * Streaming, deterministic merge of the per-directory batches: batches are drained
     * straight into a {@link ScanAccumulator} as they arrive, with no intermediate
     * global sorted list of every entry. Collisions (which can only occur between
     * unmappable, sanitized-name entries — see {@link ScanAccumulator}) and warning
     * order are resolved deterministically by the accumulator itself, independent of
     * arrival order, so the result does not depend on the concurrent walk's timing.
     *
     * <p>Consumes the queue destructively: each per-directory batch becomes
     * collectable as soon as its entries are folded into the accumulator, instead of
     * every batch being copied into one peak-memory super-list first.
     */
    static LocalScanResult mergeBatches(Queue<DirBatch> batches, Log log) {
        List<Path> skips = new ArrayList<>();
        ScanAccumulator accumulator = new ScanAccumulator(log);
        int vanishedDirectoryCount = 0;
        for (DirBatch batch; (batch = batches.poll()) != null; ) {
            if (batch.vanishedDirectory()) {
                vanishedDirectoryCount++;
            }
            skips.addAll(batch.unmappableSkips());
            for (ScanEntry entry : batch.entries()) {
                accumulator.record(entry.relativePath(), entry.source(), entry.unmappable(), entry.sizeBytes(), entry.modifiedAt());
            }
        }
        skips.sort(Comparator.naturalOrder());
        for (Path skip : skips) {
            log.warn(SKIP_WARNING_PREFIX + skip);
        }
        return accumulator.toResult(vanishedDirectoryCount);
    }

    /**
     * {@code source} is only ever populated for {@code unmappable} entries: a
     * mappable, normally-named file's real path is fully and losslessly recoverable
     * from {@code relativePath} (via {@code root.resolve(relativePath.value())}, as
     * {@code SyncUseCase} already does), so retaining a second absolute {@link Path}
     * per ordinary file would be pure waste across a million-entry scan. Only
     * unmappable entries need their real path carried forward, since their relative
     * path cannot be reversed back into it — this includes not just replacement-char
     * names but also any name whose path segment contains a literal backslash (see
     * {@link ParallelWalker#visitFile}), since {@code root.resolve(...)} would
     * otherwise resolve the wrong (or a nonexistent) file for those.
     */
    record ScanEntry(RelativePath relativePath, Path source, boolean unmappable, long sizeBytes, Instant modifiedAt) {}

    record DirBatch(List<ScanEntry> entries, List<Path> unmappableSkips, boolean vanishedDirectory) {}

    static final class ParallelWalker {

        private final Path root;
        private final PathFilter filter;
        private final Log log;
        private final long progressTick;
        private final boolean relativeStats;
        private final ExecutorService pool;
        private final AtomicInteger pending = new AtomicInteger();
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<Exception> failure = new AtomicReference<>();
        private final ConcurrentLinkedQueue<DirBatch> batches = new ConcurrentLinkedQueue<>();
        private final AtomicLong fileCount = new AtomicLong();

        ParallelWalker(Path root, PathFilter filter, Log log, int threadCount, long progressTick, boolean relativeStats) {
            this.root = root;
            this.filter = filter;
            this.log = log;
            this.progressTick = progressTick;
            this.relativeStats = relativeStats;
            this.pool = Executors.newFixedThreadPool(threadCount, namedDaemonFactory());
        }

        Queue<DirBatch> walk() {
            try {
                submitDirectory(root);
                done.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LocalVolumeException("Failed to scan root: " + root, e);
            } finally {
                pool.shutdownNow();
            }
            Exception cause = failure.get();
            if (cause != null) {
                throw new LocalVolumeException("Failed to scan root: " + root, cause);
            }
            return batches;
        }

        private void submitDirectory(Path dir) {
            // Incremented before submit, and children are submitted while their parent
            // task is still counted, so pending can never hit zero before the walk is done.
            pending.incrementAndGet();
            pool.execute(() -> {
                try {
                    if (failure.get() == null) {
                        processDirectory(dir);
                    }
                } catch (IOException | RuntimeException e) {
                    failure.compareAndSet(null, e instanceof DirectoryIteratorException die ? die.getCause() : e);
                } finally {
                    if (pending.decrementAndGet() == 0) {
                        done.countDown();
                    }
                }
            });
        }

        // Package-private (rather than private) so vanished-directory and
        // vanished-entry handling can be exercised deterministically from tests,
        // without racing a real delete against a real walk.
        void processDirectory(Path dir) throws IOException {
            processDirectory(dir, new ArrayList<>(), new ArrayList<>());
        }

        // Overload taking the entries/skips lists explicitly so a test can pre-seed
        // them (standing in for a partially-iterated stream, path c) and then prove
        // the vanished-directory catch below preserves rather than discards them.
        void processDirectory(Path dir, List<ScanEntry> entries, List<Path> skips) throws IOException {
            boolean vanishedDirectory = false;
            try {
                vanishedDirectory = walkEntries(dir, entries, skips);
            } catch (NoSuchFileException e) {
                // The scan root disappearing must never produce a silently-empty
                // snapshot: only a non-root directory vanishing mid-walk (covers
                // both the open failing outright and DirectoryIteratorException
                // wrapping a NoSuchFileException mid-iteration) is treated as
                // transient churn and skipped; the root vanishing rethrows to
                // abort the whole scan loudly via the walker's failure path.
                if (dir.equals(root)) {
                    throw e;
                }
                log.warn(VANISHED_DIRECTORY_WARNING_PREFIX + dir);
                vanishedDirectory = true;
            }
            batches.add(new DirBatch(entries, skips, vanishedDirectory));
        }

        // Package-private accessor so tests can inspect enqueued batches without
        // driving a full walk().
        Queue<DirBatch> batches() {
            return batches;
        }

        // Returns true when at least one listed entry vanished between readdir and
        // stat, before its type (file vs. directory) could be determined — see
        // visitEntry. The single-arg overload drives a real DirectoryStream; the
        // multi-arg one is package-private so a test can feed it entries directly
        // (including an already-deleted Path) and assert on the return value,
        // without racing a real delete against a real directory listing.
        private boolean walkEntries(Path dir, List<ScanEntry> entries, List<Path> skips) throws IOException {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                // Reading attributes relative to the open directory's descriptor
                // (fstatat-style) skips the kernel's full path resolution that an
                // absolute-path lstat pays for every entry; the fallback covers
                // filesystems whose streams are not SecureDirectoryStreams.
                SecureDirectoryStream<Path> secure =
                        relativeStats && stream instanceof SecureDirectoryStream<Path> secureStream
                                ? secureStream
                                : null;
                try {
                    return walkEntries(stream, secure, entries, skips);
                } catch (DirectoryIteratorException e) {
                    if (e.getCause() instanceof NoSuchFileException nsfe) {
                        // The directory itself vanished mid-iteration; any entries
                        // already collected into `entries`/`skips` above are kept as a
                        // partial batch by the caller.
                        throw nsfe;
                    }
                    throw e;
                }
            }
        }

        boolean walkEntries(Iterable<Path> listedEntries, SecureDirectoryStream<Path> secure, List<ScanEntry> entries, List<Path> skips)
                throws IOException {
            boolean unknownTypeVanish = false;
            for (Path entry : listedEntries) {
                if (failure.get() != null) {
                    return unknownTypeVanish;
                }
                if (visitEntry(secure, entry, entries, skips)) {
                    unknownTypeVanish = true;
                }
            }
            return unknownTypeVanish;
        }

        // Package-private for the same reason as processDirectory: lets a test
        // exercise the vanished-entry path directly against a real deleted path,
        // without racing a delete against the live directory stream. Returns true
        // when the entry vanished before its type could be determined — its stat
        // failed with NoSuchFileException, so it may well have been a directory
        // whose still-real subtree would otherwise go silently missing from the
        // snapshot. Unlike a stat that succeeds and confirms a regular file (an
        // observed point deletion, safe to treat as ordinary sync), this case must
        // be conservatively treated as poison for delete planning: the caller marks
        // the enclosing DirBatch vanished. Over-counting here is safe — the signal
        // only ever suppresses deletes.
        boolean visitEntry(SecureDirectoryStream<Path> secure, Path entry, List<ScanEntry> entries, List<Path> skips) throws IOException {
            Optional<BasicFileAttributes> attributes = readAttributesOrSkip(secure, entry);
            if (attributes.isEmpty()) {
                return true;
            }
            BasicFileAttributes fileAttributes = attributes.get();
            if (fileAttributes.isDirectory()) {
                submitDirectory(entry);
            } else if (fileAttributes.isRegularFile()) {
                // NOFOLLOW attributes make symlinks neither directories nor regular
                // files, matching walkFileTree's default no-follow behavior.
                visitFile(entry, fileAttributes, entries, skips);
            }
            return false;
        }

        // Package-private for the same reason as processDirectory: lets a test
        // exercise the vanished-entry path directly against a real deleted file,
        // without racing a delete against the live directory stream.
        Optional<BasicFileAttributes> readAttributesOrSkip(SecureDirectoryStream<Path> secure, Path entry)
                throws IOException {
            try {
                return Optional.of(readAttributes(secure, entry));
            } catch (NoSuchFileException e) {
                log.warn(VANISHED_ENTRY_WARNING_PREFIX + entry);
                return Optional.empty();
            }
        }

        private static BasicFileAttributes readAttributes(SecureDirectoryStream<Path> secure, Path entry)
                throws IOException {
            if (secure != null) {
                return secure.getFileAttributeView(
                                entry.getFileName(), BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        .readAttributes();
            }
            return Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }

        private void visitFile(Path file, BasicFileAttributes attributes, List<ScanEntry> entries, List<Path> skips) {
            Path relativeFile = root.relativize(file);
            String relative = relativeFile.toString();
            // A name is "unmappable" - its relativePath cannot be losslessly turned
            // back into the real path - in two cases:
            //  (a) a replacement character means the on-disk name is not valid in
            //      the runtime charset. Its String form is still deterministic for a
            //      fixed locale though, so it can serve as a stable sanitized remote
            //      key.
            //  (b) a real path segment contains a literal backslash: RelativePath.of
            //      rewrites '\' to '/' to normalize Windows-style separators, so a
            //      file literally named e.g. "x\y" and an unrelated real path "x/y"
            //      both normalize to the same RelativePath. Checking each segment
            //      individually (rather than the joined string) keeps this a no-op
            //      on Windows, where '\' is the platform separator and cannot appear
            //      inside a single segment's name anyway.
            // Both cases keep their real Path as a source override, since it cannot
            // be re-derived from the resulting relative path.
            // relative.indexOf('\\') is a free short-circuit: relative is already
            // materialized, and it is backslash-free on the vast majority of entries,
            // so the per-segment check below (which allocates a String per segment)
            // only runs for the rare name that actually needs it.
            boolean unmappable = relative.indexOf(REPLACEMENT_CHAR) >= 0
                    || (relative.indexOf('\\') >= 0 && containsBackslashInAnySegment(relativeFile));
            try {
                RelativePath relativePath = RelativePath.of(relative);
                if (filter.matches(relativePath)) {
                    // Only unmappable entries keep their real Path (see ScanEntry); a
                    // mappable entry's path is fully re-derivable from relativePath, so
                    // holding a second absolute Path per normal file across a
                    // million-entry scan would be pure waste.
                    entries.add(new ScanEntry(
                            relativePath,
                            unmappable ? file : null,
                            unmappable,
                            attributes.size(),
                            attributes.lastModifiedTime().toInstant()));
                }
            } catch (InvalidPathException | InvalidRelativePathException e) {
                skips.add(file);
            }
            long count = fileCount.incrementAndGet();
            if (count % progressTick == 0) {
                log.debug(SCAN_PROGRESS_MESSAGE_PREFIX + count + SCAN_PROGRESS_MESSAGE_SUFFIX);
            }
        }

        private static boolean containsBackslashInAnySegment(Path relativeFile) {
            for (int i = 0; i < relativeFile.getNameCount(); i++) {
                if (relativeFile.getName(i).toString().indexOf('\\') >= 0) {
                    return true;
                }
            }
            return false;
        }

        private static ThreadFactory namedDaemonFactory() {
            AtomicInteger index = new AtomicInteger();
            return runnable -> {
                Thread thread = new Thread(runnable, SCAN_THREAD_NAME_PREFIX + index.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            };
        }
    }

    /**
     * Streams scan entries in directly, one at a time, deduplicating by
     * {@link RelativePath} without ever materializing a full sorted list of every
     * entry seen. A single {@code Map<RelativePath, Winner>} both tracks "have we
     * seen this path" and holds the current winner, replacing the previous
     * combination of a separate {@code HashSet<RelativePath>} plus a growing
     * {@code List<LocalFile>}.
     *
     * <p>A genuinely mappable relative path (no replacement character, no path
     * segment containing a literal backslash) is a lossless, unique representation
     * of one filesystem location and can never collide with another entry. A
     * relative path built from a backslash-containing segment is not lossless —
     * {@link RelativePath#of} rewrites {@code '\'} to {@code '/'} to normalize
     * Windows-style separators, so a file literally named e.g. {@code "x\y"} and an
     * unrelated real nested path {@code "x/y"} both produce the same
     * {@code RelativePath}. {@link ParallelWalker#visitFile} treats exactly this
     * case as {@code unmappable} too (alongside replacement-character names), so it
     * always keeps a real source {@link Path} — meaning any possible collision is
     * guaranteed to involve at least one unmappable entry with a real path to name
     * in a warning, never two entries with no path at all. A mappable entry, once
     * recorded, is never displaced.
     *
     * <p>Warnings are not emitted as entries are recorded, since arrival order is no
     * longer sorted; instead they are collected and emitted once, sorted by relative
     * path (and, for multiple losers of the same collision, by source path), when
     * {@link #toResult()} is called.
     */
    static final class ScanAccumulator {

        private final Log log;
        private final Map<RelativePath, Winner> winners = new HashMap<>();
        private final List<Loss> losses = new ArrayList<>();

        ScanAccumulator(Log log) {
            this.log = log;
        }

        void record(RelativePath relativePath, Path source, boolean unmappable, long sizeBytes, Instant modifiedAt) {
            Winner incoming = new Winner(source, unmappable, sizeBytes, modifiedAt);
            Winner current = winners.putIfAbsent(relativePath, incoming);
            if (current == null) {
                return;
            }
            if (incomingDisplaces(current, incoming)) {
                winners.put(relativePath, incoming);
                losses.add(new Loss(relativePath, current.source()));
            } else {
                losses.add(new Loss(relativePath, incoming.source()));
            }
        }

        /**
         * A mappable current winner is never displaced. Otherwise the current winner
         * is unmappable: a mappable challenger always displaces it, and between two
         * unmappable entries the smallest source path wins — deterministic regardless
         * of which arrived first.
         */
        private static boolean incomingDisplaces(Winner current, Winner incoming) {
            if (!current.unmappable()) {
                return false;
            }
            if (!incoming.unmappable()) {
                return true;
            }
            return incoming.source().compareTo(current.source()) < 0;
        }

        LocalScanResult toResult(int vanishedDirectoryCount) {
            List<RelativePath> sortedPaths = new ArrayList<>(winners.keySet());
            sortedPaths.sort(Comparator.naturalOrder());
            Map<RelativePath, List<Path>> lostSourcesByPath = new HashMap<>();
            for (Loss loss : losses) {
                lostSourcesByPath.computeIfAbsent(loss.relativePath(), key -> new ArrayList<>()).add(loss.source());
            }

            List<LocalFile> files = new ArrayList<>(sortedPaths.size());
            Map<RelativePath, Path> sourceOverrides = new HashMap<>();
            for (RelativePath path : sortedPaths) {
                Winner winner = winners.get(path);
                files.add(new LocalFile(path, winner.sizeBytes(), winner.modifiedAt()));
                if (winner.unmappable()) {
                    log.warn(SANITIZED_WARNING_PREFIX + path.value());
                    sourceOverrides.put(path, winner.source());
                }
                List<Path> lostSources = lostSourcesByPath.get(path);
                if (lostSources != null) {
                    lostSources.sort(Comparator.naturalOrder());
                    for (Path lostSource : lostSources) {
                        log.warn(SKIP_WARNING_PREFIX + lostSource);
                    }
                }
            }
            return new LocalScanResult(LocalSnapshot.of(files), sourceOverrides, vanishedDirectoryCount);
        }

        private record Winner(Path source, boolean unmappable, long sizeBytes, Instant modifiedAt) {}

        private record Loss(RelativePath relativePath, Path source) {}
    }
}
