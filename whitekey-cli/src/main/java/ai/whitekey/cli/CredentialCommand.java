package ai.whitekey.cli;

import ai.whitekey.api.dto.CreateHttpCredentialRequest;
import ai.whitekey.api.dto.Credential;
import ai.whitekey.api.dto.CredentialPreset;
import ai.whitekey.api.dto.HttpCredential;
import ai.whitekey.api.dto.HttpInjection;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

@Command(name = "credentials", description = "Manage credentials (stored encrypted, injected on egress)",
        subcommands = {
                CredentialCommand.ListCommand.class,
                CredentialCommand.PresetsCommand.class,
                CredentialCommand.AddCommand.class,
                CredentialCommand.RemoveCommand.class
        })
public class CredentialCommand extends BaseCommand {

    public static final String DEFAULT_INJECTION_TARGET = "HEADER";
    public static final String WILDCARD_HOST = "*";

    @Command(name = "list", description = "List configured credentials (secrets masked)")
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
                    String masked = hc.maskedSecret() != null ? hc.maskedSecret() : "";
                    List<HttpInjection> injections = hc.injections();
                    if (injections.isEmpty()) {
                        System.out.printf("%s  -  %s%n", hc.name(), masked);
                    } else if (injections.size() == 1) {
                        HttpInjection inj = injections.getFirst();
                        System.out.printf("%s  %s  %s:%s  %s%n",
                                hc.name(),
                                inj.host() != null ? inj.host() : WILDCARD_HOST,
                                inj.injectionTarget() != null ? inj.injectionTarget() : DEFAULT_INJECTION_TARGET,
                                inj.key() != null ? inj.key() : "-",
                                masked);
                    } else {
                        String summary = injections.stream()
                                .map(inj -> String.format("%s (%s:%s)",
                                        inj.host() != null ? inj.host() : WILDCARD_HOST,
                                        inj.injectionTarget() != null ? inj.injectionTarget() : DEFAULT_INJECTION_TARGET,
                                        inj.key() != null ? inj.key() : "-"))
                                .collect(Collectors.joining(", "));
                        System.out.printf("%s  %s  %s%n", hc.name(), summary, masked);
                    }
                } else {
                    System.out.printf("%s  [%s]  %s%n",
                            c.name(), c.type(), c.maskedSecret() != null ? c.maskedSecret() : "");
                }
            }
        }
    }

    @Command(name = "presets", description = "List available built-in credential presets")
    public static class PresetsCommand extends BaseCommand {

        @Override
        public void run() {
            List<CredentialPreset> presets = getApiClient().getCredentialPresets();
            if (presets == null || presets.isEmpty()) {
                System.out.println("No presets available.");
                return;
            }

            for (CredentialPreset p : presets) {
                String hosts = p.injections().stream()
                        .map(HttpInjection::host)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining(", "));
                System.out.printf("%-12s %-16s %s%n", p.id(), p.displayName(), hosts);
            }
        }
    }

    @Command(name = "add", description = "Add or update a credential")
    public static class AddCommand extends BaseCommand {

        @Option(names = "--name", required = true, description = "Credential name (e.g. github-pat)")
        private String name;

        @Option(names = "--secret", required = true, description = "Secret token value")
        private String secret;

        @Option(names = "--preset", description = "Preset ID (e.g. github)")
        private String preset;

        @Option(names = "--host", description = "Target host (e.g. api.example.com, *.example.com)")
        private String host;

        @Option(names = "--injection-target", description = "Injection target: HEADER, QUERY, or COOKIE (default: HEADER or preset default)")
        private String injectionTarget;

        @Option(names = "--key", description = "Header, query, or cookie name")
        private String key;

        @Option(names = "--format", description = "Format template (e.g. 'Bearer %%s')")
        private String format;

        @Override
        public void run() {
            CreateHttpCredentialRequest payload = new CreateHttpCredentialRequest(
                    name,
                    preset,
                    host,
                    injectionTarget != null ? injectionTarget.toUpperCase(Locale.ROOT) : null,
                    key,
                    format,
                    secret);

            Credential created = getApiClient().createCredential(payload);
            System.out.println("Saved " + created.name());
        }
    }

    @Command(name = "remove", description = "Remove a credential by name")
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
