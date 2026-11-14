package ai.whitekey.cli;

import ai.whitekey.cli.client.ApiClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "whitekey",
        description = "WhiteKey - Security Proxy Gateway & Secret Broker for AI Agents",
        version = "0.0.1",
        subcommands = {
                StatusCommand.class,
                SetupCommand.class,
                RuleCommand.class,
                CredentialCommand.class,
                ApprovalCommand.class,
                HistoryCommand.class
        })
public class WhiteKeyRootCommand extends BaseCommand {

    @Option(names = "--version", versionHelp = true, description = "Print version information and exit.")
    private boolean versionRequested;

    @Option(names = "--server", descriptionKey = "WHITEKEY_SERVER_URL", description = "WhiteKey server URL (default: http://127.0.0.1:1111 or WHITEKEY_SERVER_URL env)")
    private String serverUrl;

    @Option(names = "--token", descriptionKey = "WHITEKEY_ADMIN_TOKEN", description = "WhiteKey admin token (default: reads ~/.local/share/whitekey/whitekey_admin_token)")
    private String adminToken;

    private ApiClient apiClient;

    @Override
    public ApiClient getApiClient() {
        if (apiClient == null) {
            apiClient = new ApiClient(serverUrl, adminToken);
        }
        return apiClient;
    }
}
