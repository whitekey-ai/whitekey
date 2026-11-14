package ai.whitekey.cli;

import ai.whitekey.api.dto.Status;
import picocli.CommandLine.Command;

@Command(name = "status", description = "Show WhiteKey proxy and policy engine status", mixinStandardHelpOptions = true)
public class StatusCommand extends BaseCommand {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_STOPPED = "STOPPED";

    @Override
    public void run() {
        Status status = getApiClient().getStatus();

        System.out.printf("Proxy: %s (port %s)%n",
                status.proxyRunning() ? STATUS_RUNNING : STATUS_STOPPED,
                status.proxyRunning() ? status.proxyPort() : "-");
        System.out.printf("Rules: %d active%n", status.activeRulesCount());
        System.out.printf("Pending Approvals: %d%n", status.pendingApprovalsCount());
    }
}
