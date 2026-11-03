package com.functorful.glacierbackup.cli;

import com.functorful.glacierbackup.application.RestoreStatusLine;
import com.functorful.glacierbackup.domain.RestoreState;
import java.util.List;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "restore-status", description = "Show restore state for remote objects requiring restore.")
public final class RestoreStatusCommand extends AbstractCommand {

    private static final String EMPTY_PREFIX = "";
    private static final String DASH = "-";

    @Parameters(arity = "0..1", paramLabel = "<remote-prefix>")
    private String prefix;

    public RestoreStatusCommand(Function<CliOptions, AppContext> contextFactory) {
        super(contextFactory);
    }

    @Override
    protected Integer doCall(AppContext context) {
        String effectivePrefix = prefix != null ? prefix : EMPTY_PREFIX;
        List<RestoreStatusLine> lines = context.restoreStatusUseCase().run(effectivePrefix);
        render(lines);
        return GlacierBackupCommand.EXIT_OK;
    }

    private void render(List<RestoreStatusLine> lines) {
        int keyWidth = lines.stream().mapToInt(line -> line.key().length()).max().orElse(0);
        int classWidth = lines.stream().mapToInt(line -> line.storageClass().toString().length()).max().orElse(0);
        for (RestoreStatusLine line : lines) {
            log().info(pad(line.key(), keyWidth) + "  " + pad(line.storageClass().toString(), classWidth) + "  "
                    + renderState(line.state()));
        }
    }

    private static String renderState(RestoreState state) {
        return switch (state) {
            case RestoreState.NotStarted ignored -> DASH;
            case RestoreState.InProgress ignored -> "in-progress";
            case RestoreState.Ready ready -> "ready-until " + ready.until();
        };
    }

    private static String pad(String value, int width) {
        return value + " ".repeat(Math.max(0, width - value.length()));
    }
}
