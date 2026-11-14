package ai.whitekey.cli;

import ai.whitekey.cli.client.ApiClient;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

abstract class BaseCommand implements Runnable {

    @Spec
    protected CommandSpec spec;

    @Option(names = "--help", usageHelp = true, description = "Show this help message and exit.")
    private boolean help;

    public ApiClient getApiClient() {
        return ((WhiteKeyRootCommand) spec.root().userObject()).getApiClient();
    }

    protected void resolve(String actionId, String decision, boolean saveRule) {
        getApiClient().resolveApproval(actionId, decision, saveRule);
        System.out.println(ApprovalCommand.formatResolution(actionId, decision, saveRule));
    }

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }
}
