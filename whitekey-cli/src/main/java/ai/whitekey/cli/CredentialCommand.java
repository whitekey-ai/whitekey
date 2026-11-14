package ai.whitekey.cli;

import ai.whitekey.api.dto.CreateHttpCredentialRequest;
import ai.whitekey.api.dto.Credential;
import ai.whitekey.api.dto.CredentialPreset;
import ai.whitekey.api.dto.HttpCredential;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;

@Command(name = "credentials", description = "Manage credentials (stored encrypted, injected on egress)", mixinStandardHelpOptions = true,
        subcommands = {
                CredentialCommand.ListCommand.class,
                CredentialCommand.PresetsCommand.class,
                CredentialCommand.SetCommand.class,
                CredentialCommand.RemoveCommand.class
        })
public class CredentialCommand extends BaseCommand {

    public static final String DEFAULT_TARGET_TYPE = "HEADER";
    public static final String WILDCARD_HOST = "*";

    @Override
    public void run() {
        System.out.println("Use 'credentials --help' to see available subcommands (list, presets, set, remove).");
    }

    @Command(name = "list", description = "List configured credentials (secrets masked)", mixinStandardHelpOptions = true)
    public static class ListCommand extends BaseCommand {

        @Override
        public void run() {
            List<Credential> credentials = getApiClient().getCredentials();
            if (credentials == null || credentials.isEmpty()) {
                System.out.println("No credentials configured.");
                return;
            }

            for (Credential c : credentials) {
                if (c instanceof HttpCredential hc) {
                    System.out.printf("%s  %s  %s:%s  %s%n",
                            hc.name(),
                            hc.host() != null ? hc.host() : WILDCARD_HOST,
                            hc.targetType() != null ? hc.targetType() : DEFAULT_TARGET_TYPE,
                            hc.targetKey() != null ? hc.targetKey() : "-",
                            hc.maskedSecret() != null ? hc.maskedSecret() : "");
                } else {
                    System.out.printf("%s  [%s]  %s%n",
                            c.name(), c.type(), c.maskedSecret() != null ? c.maskedSecret() : "");
                }
            }
        }
    }

    @Command(name = "presets", description = "List available built-in credential presets", mixinStandardHelpOptions = true)
    public static class PresetsCommand extends BaseCommand {

        @Override
        public void run() {
            List<CredentialPreset> presets = getApiClient().getCredentialPresets();
            if (presets == null || presets.isEmpty()) {
                System.out.println("No presets available.");
                return;
            }

            for (CredentialPreset p : presets) {
                System.out.printf("%-12s %-16s %s%n", p.id(), p.displayName(), p.defaultHost() != null ? p.defaultHost() : "");
            }
        }
    }

    @Command(name = "set", description = "Add or update a credential")
    public static class SetCommand extends BaseCommand {

        @Option(names = "--help", usageHelp = true, description = "Show this help message and exit.")
        private boolean help;

        @Option(names = {"-n", "--name"}, required = true, description = "Credential name (e.g. github-pat)")
        private String name;

        @Option(names = {"-s", "--secret"}, required = true, description = "Secret token value")
        private String secret;

        @Option(names = {"-p", "--preset"}, description = "Preset ID (e.g. github, stripe, slack)")
        private String preset;

        @Option(names = {"-H", "--host"}, description = "Target host (e.g. api.github.com, *.slack.com)")
        private String host;

        @Option(names = {"-t", "--type"}, defaultValue = "HEADER", description = "Target type: HEADER, QUERY, or COOKIE")
        private String targetType;

        @Option(names = {"-k", "--key"}, description = "Header, query, or cookie name")
        private String key;

        @Option(names = {"-f", "--format"}, description = "Format template (e.g. 'Bearer %%s')")
        private String format;

        @Override
        public void run() {
            CreateHttpCredentialRequest payload = new CreateHttpCredentialRequest(
                    name,
                    preset,
                    host,
                    targetType != null ? targetType.toUpperCase() : null,
                    key,
                    format,
                    secret);

            Credential created = getApiClient().createCredential(payload);
            System.out.println("Saved " + created.name());
        }
    }

    @Command(name = "remove", description = "Remove a credential by name", mixinStandardHelpOptions = true)
    public static class RemoveCommand extends BaseCommand {

        @Parameters(index = "0", description = "Credential name to remove")
        private String name;

        @Override
        public void run() {
            getApiClient().deleteCredential(name);
            System.out.println("Credential '" + name + "' removed.");
        }
    }
}
