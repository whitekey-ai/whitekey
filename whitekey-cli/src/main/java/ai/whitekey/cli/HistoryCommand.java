package ai.whitekey.cli;

import ai.whitekey.api.dto.Action;
import ai.whitekey.api.dto.HttpAction;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

@Command(name = "history", description = "Show history of recent proxy actions",
        subcommands = {
                HistoryCommand.ClearCommand.class
        })
public class HistoryCommand extends BaseCommand {

    public static final String DEFAULT_LIMIT = "20";

    @Option(names = "--limit", defaultValue = DEFAULT_LIMIT, description = "Max number of actions")
    private int limit;

    @Option(names = "--status", description = "Filter by status (PENDING, ALLOWED, DENIED, CANCELLED)")
    private String status;

    @Option(names = "--method", description = "Filter by HTTP method")
    private String method;

    @Option(names = "--host", description = "Filter by host")
    private String host;

    @Override
    public void run() {
        List<Action> actions = getApiClient().getHistory(limit, status, method, host);
        if (actions == null || actions.isEmpty()) {
            System.out.println("No matching actions found.");
            return;
        }

        int statusWidth = actions.stream()
                .mapToInt(a -> a.status() != null ? a.status().length() : 1)
                .max()
                .orElse(7);
        statusWidth = Math.max(7, statusWidth);
        String httpRowFormat = "%s  %-" + statusWidth + "s %-6s %s%n";
        String genericRowFormat = "%s  %-" + statusWidth + "s [%s]%n";

        for (Action action : actions) {
            String actionStatus = action.status() != null ? action.status() : "-";
            if (action instanceof HttpAction ha) {
                String actionMethod = ha.method() != null ? ha.method() : "-";
                String target = (ha.host() != null ? ha.host() : "") + (ha.path() != null ? ha.path() : "");
                System.out.printf(httpRowFormat, ha.id(), actionStatus, actionMethod, target.isEmpty() ? "-" : target);
            } else {
                System.out.printf(genericRowFormat, action.id(), actionStatus, action.type());
            }
        }
    }

    @Command(name = "clear", description = "Clear history of finished actions")
    public static class ClearCommand extends BaseCommand {

        @Override
        public void run() {
            getApiClient().clearHistory();
            System.out.println("Finished action history cleared.");
        }
    }
}
