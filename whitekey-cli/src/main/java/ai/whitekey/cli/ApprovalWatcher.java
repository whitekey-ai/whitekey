package ai.whitekey.cli;

import ai.whitekey.api.dto.Action;
import ai.whitekey.api.dto.HttpAction;
import ai.whitekey.cli.client.ApiClient;
import ai.whitekey.cli.client.CliException;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;

/**
 * Interactive queue watcher that presents pending actions oldest-first,
 * one at a time, using a pointer menu for immediate approval or rejection.
 */
final class ApprovalWatcher {

    enum Choice {
        APPROVE("Approve", ApprovalCommand.DECISION_ALLOW, false),
        APPROVE_AND_SAVE("Approve and save as rule", ApprovalCommand.DECISION_ALLOW, true),
        REJECT("Reject", ApprovalCommand.DECISION_BLOCK, false);

        final String label;
        final String decision;
        final boolean saveRule;

        Choice(String label, String decision, boolean saveRule) {
            this.label = label;
            this.decision = decision;
            this.saveRule = saveRule;
        }
    }

    interface Prompt {
        /**
         * Displays the 3-option pointer menu for {@code current} and returns the chosen action,
         * {@code null} if the user quits, or {@link PromptResult#stale()} if the action ceased to be pending.
         */
        PromptResult select(Action current) throws IOException;
    }

    record PromptResult(Choice choice, boolean quit, boolean stale) {
        static PromptResult selected(Choice choice) {
            return new PromptResult(choice, false, false);
        }

        static PromptResult quitSignal() {
            return new PromptResult(null, true, false);
        }

        static PromptResult staleSignal() {
            return new PromptResult(null, false, true);
        }
    }

    private static final int ESC_SEQUENCE_TIMEOUT_MS = 30;
    private static final int MAX_BODY_PREVIEW_CHARS = 160;
    private static final Choice[] OPTIONS = Choice.values();

    private final ApiClient apiClient;
    private final PrintWriter out;
    private final int pollIntervalMs;

    ApprovalWatcher(ApiClient apiClient, PrintWriter out, int pollIntervalMs) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.out = Objects.requireNonNull(out, "out");
        this.pollIntervalMs = pollIntervalMs;
    }

    void watchInteractive() {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            boolean dumb = terminal.getType() != null && terminal.getType().startsWith("dumb");
            if (dumb) {
                BufferedReader lineReader = new BufferedReader(new InputStreamReader(terminal.input()));
                watchLoop(new LinePrompt(lineReader));
            } else {
                watchLoop(new JLinePointerPrompt(terminal));
            }
        } catch (IOException e) {
            throw new CliException("Terminal error: " + e.getMessage());
        }
    }

    void watchLoop(Prompt prompt) throws IOException {
        boolean waitingMessageShown = false;
        while (true) {
            List<Action> pending = apiClient.getPendingApprovals();
            if (pending == null || pending.isEmpty()) {
                if (!waitingMessageShown) {
                    out.println("Waiting for incoming actions... (Press Ctrl+C to quit)");
                    out.flush();
                    waitingMessageShown = true;
                }
                if (!sleepPollInterval()) {
                    return;
                }
                continue;
            }

            waitingMessageShown = false;
            Action current = pending.get(0);
            printActionCard(current, pending.size());

            PromptResult result = prompt.select(current);
            if (result.quit()) {
                out.println("Stopped watching.");
                out.flush();
                return;
            }
            if (result.stale()) {
                out.println("Action " + current.id() + " is no longer awaiting approval.");
                out.flush();
                continue;
            }

            Choice choice = result.choice();
            try {
                apiClient.resolveApproval(current.id(), choice.decision, choice.saveRule);
                String label = ApprovalCommand.DECISION_ALLOW.equals(choice.decision)
                        ? ApprovalCommand.LABEL_APPROVED
                        : ApprovalCommand.LABEL_REJECTED;
                out.println("Action " + current.id() + " " + label + "."
                        + (choice.saveRule ? " (Rule saved)" : ""));
                out.flush();
            } catch (CliException ex) {
                // If the action expired or client disconnected right before resolve, move straight to the next one
                if (isNoLongerPendingError(ex.getMessage())) {
                    out.println("Skipped " + current.id() + ": " + ex.getMessage());
                    out.flush();
                } else {
                    throw ex;
                }
            }
        }
    }

    private void printActionCard(Action action, int totalWaiting) {
        out.println();
        out.println("------------------------------------------------------------");
        out.printf("Action 1 of %d pending (oldest first)  ID: %s%n", totalWaiting, action.id());
        if (action instanceof HttpAction ha) {
            String method = ha.method() != null ? ha.method() : "HTTP";
            String target = formatHttpTarget(ha);
            out.printf("Request: %s %s%n", method, target);
            if (ha.body() != null && !ha.body().isBlank()) {
                String compactBody = ha.body().replaceAll("\\s+", " ").trim();
                if (compactBody.length() > MAX_BODY_PREVIEW_CHARS) {
                    compactBody = compactBody.substring(0, MAX_BODY_PREVIEW_CHARS) + "...";
                }
                out.printf("Body:    %s%n", compactBody);
            }
        } else {
            out.printf("Type:    %s%n", action.type());
        }
        out.flush();
    }

    private static String formatHttpTarget(HttpAction ha) {
        if (ha.uri() != null && !ha.uri().isBlank()) {
            return ha.uri();
        }
        String host = ha.host() != null ? ha.host() : "";
        String port = ha.port() != null ? ":" + ha.port() : "";
        String path = ha.path() != null ? ha.path() : "";
        return host + port + path;
    }

    private boolean sleepPollInterval() {
        try {
            Thread.sleep(pollIntervalMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isNoLongerPendingError(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("409")
                || message.contains("404")
                || message.toLowerCase().contains("no longer awaiting approval");
    }

    private final class JLinePointerPrompt implements Prompt {
        private final Terminal terminal;

        JLinePointerPrompt(Terminal terminal) {
            this.terminal = terminal;
        }

        @Override
        public PromptResult select(Action current) throws IOException {
            int selected = 0;
            renderMenu(selected, false);
            Attributes saved = terminal.enterRawMode();
            try {
                NonBlockingReader reader = terminal.reader();
                while (true) {
                    int ch = reader.read(pollIntervalMs);
                    if (ch == NonBlockingReader.READ_EXPIRED) {
                        if (!isStillPending(current.id())) {
                            return PromptResult.staleSignal();
                        }
                        continue;
                    }
                    if (ch == -1 || ch == 3 || ch == 'q' || ch == 'Q') {
                        return PromptResult.quitSignal();
                    }
                    if (ch == '\r' || ch == '\n') {
                        return PromptResult.selected(OPTIONS[selected]);
                    }

                    int next = handleNavigationKey(ch, selected, reader);
                    if (next != selected) {
                        selected = next;
                        renderMenu(selected, true);
                    }
                }
            } finally {
                terminal.setAttributes(saved);
            }
        }

        private int handleNavigationKey(int ch, int selected, NonBlockingReader reader) throws IOException {
            if (ch == 27) { // ESC sequence for arrow keys
                int second = reader.read(ESC_SEQUENCE_TIMEOUT_MS);
                if (second == '[' || second == 'O') {
                    int third = reader.read(ESC_SEQUENCE_TIMEOUT_MS);
                    if (third == 'A') {
                        return (selected - 1 + OPTIONS.length) % OPTIONS.length;
                    }
                    if (third == 'B') {
                        return (selected + 1) % OPTIONS.length;
                    }
                }
                return selected;
            }
            if (ch == 'k' || ch == 'K' || ch == 'w' || ch == 'W') {
                return (selected - 1 + OPTIONS.length) % OPTIONS.length;
            }
            if (ch == 'j' || ch == 'J' || ch == 's' || ch == 'S') {
                return (selected + 1) % OPTIONS.length;
            }
            if (ch >= '1' && ch < '1' + OPTIONS.length) {
                return ch - '1';
            }
            return selected;
        }

        private boolean isStillPending(String actionId) {
            List<Action> pending = apiClient.getPendingApprovals();
            return pending != null && pending.stream().anyMatch(a -> actionId.equals(a.id()));
        }

        private void renderMenu(int selected, boolean redraw) {
            int totalLines = OPTIONS.length + 1;
            if (redraw) {
                out.print("\u001B[" + totalLines + "A\r");
            }
            for (int i = 0; i < OPTIONS.length; i++) {
                String prefix = (i == selected) ? "> " : "  ";
                if (redraw) {
                    out.print("\u001B[2K");
                }
                out.printf("%s%d. %s%n", prefix, i + 1, OPTIONS[i].label);
            }
            if (redraw) {
                out.print("\u001B[2K");
            }
            out.println("(Use Up/Down or 1-3 to move pointer, Enter to confirm, q to quit)");
            out.flush();
        }
    }

    private final class LinePrompt implements Prompt {
        private final BufferedReader reader;

        LinePrompt(BufferedReader reader) {
            this.reader = reader;
        }

        @Override
        public PromptResult select(Action current) throws IOException {
            for (int i = 0; i < OPTIONS.length; i++) {
                out.printf("  %d. %s%n", i + 1, OPTIONS[i].label);
            }
            while (true) {
                out.print("Select [1-" + OPTIONS.length + ", q to quit]: ");
                out.flush();
                String line = reader.readLine();
                if (line == null) {
                    return PromptResult.quitSignal();
                }
                String trimmed = line.trim();
                if ("q".equalsIgnoreCase(trimmed)) {
                    return PromptResult.quitSignal();
                }
                if (trimmed.length() == 1) {
                    int idx = trimmed.charAt(0) - '1';
                    if (idx >= 0 && idx < OPTIONS.length) {
                        return PromptResult.selected(OPTIONS[idx]);
                    }
                }
                out.println("Invalid choice. Enter 1-" + OPTIONS.length + ", or q.");
            }
        }
    }
}
