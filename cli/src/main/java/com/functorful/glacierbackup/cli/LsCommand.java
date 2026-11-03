package com.functorful.glacierbackup.cli;

import com.functorful.glacierbackup.application.ListingEntry;
import com.functorful.glacierbackup.domain.RestoreState;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "ls", description = "List remote objects.")
public final class LsCommand extends AbstractCommand {

    private static final String EMPTY_PREFIX = "";
    private static final String DASH = "-";

    @Parameters(arity = "0..1", paramLabel = "<remote-prefix>")
    private String prefix;

    @Option(names = "-l", description = "Long listing: size, last modified, storage class, restore state.")
    private boolean longFormat;

    @Option(names = "--recursive", description = "List all keys under the prefix instead of only direct children.")
    private boolean recursive;

    public LsCommand(Function<CliOptions, AppContext> contextFactory) {
        super(contextFactory);
    }

    @Override
    protected Integer doCall(AppContext context) {
        String effectivePrefix = prefix != null ? prefix : EMPTY_PREFIX;
        List<ListingEntry> entries = context.listUseCase().run(effectivePrefix, longFormat);
        List<DisplayRow> rows = recursive ? fullRows(entries) : collapsedRows(entries, effectivePrefix);
        render(rows);
        return GlacierBackupCommand.EXIT_OK;
    }

    private record DisplayRow(String name, Optional<ListingEntry> entry) {}

    private static List<DisplayRow> fullRows(List<ListingEntry> entries) {
        return entries.stream().map(entry -> new DisplayRow(entry.key(), Optional.of(entry))).toList();
    }

    private static List<DisplayRow> collapsedRows(List<ListingEntry> entries, String prefix) {
        String prefixWithSlash = prefix.isEmpty() ? EMPTY_PREFIX : (prefix.endsWith("/") ? prefix : prefix + "/");
        List<DisplayRow> rows = new ArrayList<>();
        Set<String> seenDirs = new LinkedHashSet<>();
        for (ListingEntry entry : entries) {
            String remainder =
                    entry.key().startsWith(prefixWithSlash) ? entry.key().substring(prefixWithSlash.length()) : entry.key();
            int slashIndex = remainder.indexOf('/');
            if (slashIndex >= 0) {
                String dirName = remainder.substring(0, slashIndex) + "/";
                if (seenDirs.add(dirName)) {
                    rows.add(new DisplayRow(dirName, Optional.empty()));
                }
            } else {
                rows.add(new DisplayRow(remainder, Optional.of(entry)));
            }
        }
        return rows;
    }

    private void render(List<DisplayRow> rows) {
        if (!longFormat) {
            for (DisplayRow row : rows) {
                log().info(row.name());
            }
            return;
        }

        List<String[]> columns = rows.stream().map(LsCommand::toColumns).toList();
        int sizeWidth = maxWidth(columns, 0);
        int modifiedWidth = maxWidth(columns, 1);
        int classWidth = maxWidth(columns, 2);
        int stateWidth = maxWidth(columns, 3);

        for (String[] column : columns) {
            log().info(pad(column[0], sizeWidth) + "  " + pad(column[1], modifiedWidth) + "  " + pad(column[2], classWidth)
                    + "  " + pad(column[3], stateWidth) + "  " + column[4]);
        }
    }

    private static String[] toColumns(DisplayRow row) {
        if (row.entry().isEmpty()) {
            return new String[] {DASH, DASH, DASH, DASH, row.name()};
        }
        ListingEntry entry = row.entry().get();
        return new String[] {
            Long.toString(entry.sizeBytes()),
            entry.lastModifiedAt().toString(),
            entry.storageClass().toString(),
            renderRestoreState(entry.restoreState()),
            row.name()
        };
    }

    private static String renderRestoreState(Optional<RestoreState> restoreState) {
        if (restoreState.isEmpty()) {
            return DASH;
        }
        return switch (restoreState.get()) {
            case RestoreState.NotStarted ignored -> DASH;
            case RestoreState.InProgress ignored -> "in-progress";
            case RestoreState.Ready ready -> "ready-until " + ready.until();
        };
    }

    private static int maxWidth(List<String[]> columns, int index) {
        return columns.stream().mapToInt(column -> column[index].length()).max().orElse(0);
    }

    private static String pad(String value, int width) {
        return value + " ".repeat(Math.max(0, width - value.length()));
    }
}
