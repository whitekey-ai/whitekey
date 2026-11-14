package ai.whitekey.cli;

import ai.whitekey.api.dto.Action;
import ai.whitekey.api.dto.HttpAction;
import ai.whitekey.cli.client.ApiClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.PrintWriter;
import java.util.List;

@Command(name = "approvals", description = "Manage pending action approvals", mixinStandardHelpOptions = true,
        subcommands = {
                ApprovalCommand.ListCommand.class,
                ApprovalCommand.WatchCommand.class,
                ApprovalCommand.ApproveCommand.class,
                ApprovalCommand.RejectCommand.class
        })
public class ApprovalCommand implements Runnable {

    public static final String DECISION_ALLOW = "ALLOW";
    public static final String DECISION_BLOCK = "BLOCK";
    public static final String LABEL_APPROVED = "APPROVED";
    public static final String LABEL_REJECTED = "REJECTED";

    @ParentCommand
    private WhiteKeyRootCommand root;

    public ApiClient getApiClient() {
        return root.getApiClient();
    }

    void resolve(String actionId, String decision, boolean saveRule) {
        getApiClient().resolveApproval(actionId, decision, saveRule);
        String label = DECISION_ALLOW.equalsIgnoreCase(decision) ? LABEL_APPROVED : LABEL_REJECTED;
        System.out.println("Action " + actionId + " " + label + "." + (saveRule ? " (Rule saved)" : ""));
    }

    @Override
    public void run() {
        System.out.println("Use 'approvals --help' to see available subcommands (list, watch, approve, reject).");
    }

    @Command(name = "watch", description = "Watch incoming actions one by one and approve/reject interactively", mixinStandardHelpOptions = true)
    public static class WatchCommand implements Runnable {
        @ParentCommand
        private ApprovalCommand parent;

        @Option(names = {"--interval"}, defaultValue = "300", description = "Poll interval in milliseconds (default: 300)")
        private int pollIntervalMs;

        @Override
        public void run() {
            new ApprovalWatcher(parent.getApiClient(), new PrintWriter(System.out, true), pollIntervalMs)
                    .watchInteractive();
        }
    }

    @Command(name = "list", description = "List actions awaiting approval", mixinStandardHelpOptions = true)
    public static class ListCommand implements Runnable {
        @ParentCommand
        private ApprovalCommand parent;

        @Override
        public void run() {
            List<Action> pending = parent.getApiClient().getPendingApprovals();
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

    @Command(name = "approve", description = "Approve a pending action", mixinStandardHelpOptions = true)
    public static class ApproveCommand implements Runnable {
        @ParentCommand
        private ApprovalCommand parent;

        @Parameters(index = "0", description = "Action ID (UUID)")
        private String actionId;

        @Option(names = {"--save-rule"}, description = "Automatically save a permanent rule for this action")
        private boolean saveRule;

        @Override
        public void run() {
            parent.resolve(actionId, DECISION_ALLOW, saveRule);
        }
    }

    @Command(name = "reject", description = "Reject a pending action", mixinStandardHelpOptions = true)
    public static class RejectCommand implements Runnable {
        @ParentCommand
        private ApprovalCommand parent;

        @Parameters(index = "0", description = "Action ID (UUID)")
        private String actionId;

        @Override
        public void run() {
            parent.resolve(actionId, DECISION_BLOCK, false);
        }
    }
}
