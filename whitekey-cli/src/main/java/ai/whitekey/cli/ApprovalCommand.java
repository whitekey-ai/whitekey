package ai.whitekey.cli;

import ai.whitekey.api.dto.Action;
import ai.whitekey.api.dto.HttpAction;
import ai.whitekey.api.dto.ApprovalRequest;
import ai.whitekey.cli.client.ApiClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.List;

@Command(name = "approvals", description = "Manage pending approval requests", mixinStandardHelpOptions = true,
        subcommands = {
                ApprovalCommand.ListCommand.class,
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

    void resolve(String requestId, String decision, boolean saveRule) {
        getApiClient().resolveApproval(requestId, decision, saveRule);
        String label = DECISION_ALLOW.equalsIgnoreCase(decision) ? LABEL_APPROVED : LABEL_REJECTED;
        System.out.println("Request " + requestId + " " + label + "." + (saveRule ? " (Rule saved)" : ""));
    }

    @Override
    public void run() {
        System.out.println("Use 'approvals --help' to see available subcommands (list, approve, reject).");
    }

    @Command(name = "list", description = "List pending approval requests", mixinStandardHelpOptions = true)
    public static class ListCommand implements Runnable {
        @ParentCommand
        private ApprovalCommand parent;

        @Override
        public void run() {
            List<ApprovalRequest> pending = parent.getApiClient().getPendingApprovals();
            if (pending == null || pending.isEmpty()) {
                System.out.println("No pending approval requests.");
                return;
            }

            for (ApprovalRequest req : pending) {
                Action a = req.action();
                if (a instanceof HttpAction ha) {
                    String method = ha.method() != null ? ha.method() : "HTTP";
                    String target = (ha.host() != null ? ha.host() : "") + (ha.path() != null ? ha.path() : "");
                    String reason = (ha.reason() != null && !ha.reason().isBlank()) ? " (" + ha.reason() + ")" : "";
                    System.out.printf("%s  %-6s %s%s%n", req.id(), method, target, reason);
                } else if (a != null) {
                    System.out.printf("%s  [%s] %s%n", req.id(), a.type(), a.reason() != null ? a.reason() : "");
                }
            }
        }
    }

    @Command(name = "approve", description = "Approve a pending request", mixinStandardHelpOptions = true)
    public static class ApproveCommand implements Runnable {
        @ParentCommand
        private ApprovalCommand parent;

        @Parameters(index = "0", description = "Approval request ID (UUID)")
        private String requestId;

        @Option(names = {"--save-rule"}, description = "Automatically save a permanent rule for this request")
        private boolean saveRule;

        @Override
        public void run() {
            parent.resolve(requestId, DECISION_ALLOW, saveRule);
        }
    }

    @Command(name = "reject", description = "Reject a pending request", mixinStandardHelpOptions = true)
    public static class RejectCommand implements Runnable {
        @ParentCommand
        private ApprovalCommand parent;

        @Parameters(index = "0", description = "Approval request ID (UUID)")
        private String requestId;

        @Option(names = {"--save-rule"}, description = "Automatically save a permanent rule for this request")
        private boolean saveRule;

        @Override
        public void run() {
            parent.resolve(requestId, DECISION_BLOCK, saveRule);
        }
    }
}
