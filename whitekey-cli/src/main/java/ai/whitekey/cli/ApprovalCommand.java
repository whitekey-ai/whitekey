package ai.whitekey.cli;

import ai.whitekey.api.dto.Action;
import ai.whitekey.api.dto.HttpAction;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintWriter;
import java.util.List;

@Command(name = "approvals", description = "Manage pending action approvals",
        subcommands = {
                ApprovalCommand.ListCommand.class,
                ApprovalCommand.WatchCommand.class,
                ApprovalCommand.ApproveCommand.class,
                ApprovalCommand.RejectCommand.class
        })
public class ApprovalCommand extends BaseCommand {

    public static final String DECISION_ALLOW = "ALLOW";
    public static final String DECISION_BLOCK = "BLOCK";
    public static final String LABEL_APPROVED = "APPROVED";
    public static final String LABEL_REJECTED = "REJECTED";

    static String formatResolution(String actionId, String decision, boolean saveRule) {
        String label = DECISION_ALLOW.equalsIgnoreCase(decision) ? LABEL_APPROVED : LABEL_REJECTED;
        return "Action " + actionId + " " + label + "." + (saveRule ? " (Rule saved)" : "");
    }

    @Command(name = "watch", description = "Watch incoming actions one by one and approve/reject interactively")
    public static class WatchCommand extends BaseCommand {

        @Option(names = {"--interval"}, defaultValue = "300", description = "Poll interval in milliseconds (default: 300)")
        private int pollIntervalMs;

        @Override
        public void run() {
            new ApprovalWatcher(getApiClient(), new PrintWriter(System.out, true), pollIntervalMs)
                    .watchInteractive();
        }
    }

    @Command(name = "list", description = "List actions awaiting approval")
    public static class ListCommand extends BaseCommand {

        @Override
        public void run() {
            List<Action> pending = getApiClient().getPendingApprovals();
            if (pending == null || pending.isEmpty()) {
                System.out.println("No pending approval requests.");
                return;
            }

            for (Action action : pending) {
                if (action instanceof HttpAction ha) {
                    String method = ha.method() != null ? ha.method() : "HTTP";
                    String target = (ha.host() != null ? ha.host() : "") + (ha.path() != null ? ha.path() : "");
                    System.out.printf("%s  %-6s %s%n", ha.id(), method, target);
                } else {
                    System.out.printf("%s  [%s]%n", action.id(), action.type());
                }
            }
        }
    }

    @Command(name = "approve", description = "Approve a pending action")
    public static class ApproveCommand extends BaseCommand {

        @Parameters(index = "0", description = "Action ID (UUID)")
        private String actionId;

        @Option(names = {"--save-rule"}, description = "Automatically save a permanent rule for this action")
        private boolean saveRule;

        @Override
        public void run() {
            resolve(actionId, DECISION_ALLOW, saveRule);
        }
    }

    @Command(name = "reject", description = "Reject a pending action")
    public static class RejectCommand extends BaseCommand {

        @Parameters(index = "0", description = "Action ID (UUID)")
        private String actionId;

        @Override
        public void run() {
            resolve(actionId, DECISION_BLOCK, false);
        }
    }
}
