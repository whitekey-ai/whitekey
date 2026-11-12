package ai.whitekey.cli;

import ai.whitekey.api.dto.CreateHttpRuleRequest;
import ai.whitekey.api.dto.HttpRule;
import ai.whitekey.api.dto.ProtocolConstants;
import ai.whitekey.api.dto.Rule;
import ai.whitekey.cli.client.ApiClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.List;

@Command(name = "rules", description = "Manage policy rules", mixinStandardHelpOptions = true,
        subcommands = {
                RuleCommand.ListCommand.class,
                RuleCommand.AddCommand.class,
                RuleCommand.RemoveCommand.class
        })
public class RuleCommand implements Runnable {

    public static final String DEFAULT_RULE_DESCRIPTION = "CLI Rule";

    @ParentCommand
    private WhiteKeyRootCommand root;

    public ApiClient getApiClient() {
        return root.getApiClient();
    }

    @Override
    public void run() {
        System.out.println("Use 'rules --help' to see available subcommands (list, add, remove).");
    }

    @Command(name = "list", description = "List all active policy rules", mixinStandardHelpOptions = true)
    public static class ListCommand implements Runnable {
        @ParentCommand
        private RuleCommand parent;

        @Override
        public void run() {
            List<Rule> rules = parent.getApiClient().getRules();
            if (rules == null || rules.isEmpty()) {
                System.out.println("No policy rules configured.");
                return;
            }

            for (Rule r : rules) {
                if (r instanceof HttpRule http) {
                    String effect = http.effect() != null ? http.effect() : "-";
                    String methods = formatList(http.methods());
                    String hosts = formatList(http.hosts());
                    String paths = formatList(http.paths());
                    String cond = (http.bodyCondition() != null && !http.bodyCondition().isBlank()) ? " when " + http.bodyCondition() : "";
                    System.out.printf("%s  %-5s %-10s %s%s%s%n",
                            http.id(), effect, methods, hosts, paths.equals("*") ? "" : " " + paths, cond);
                } else {
                    System.out.printf("%s  %-5s [%s] %s%n",
                            r.id(), r.effect() != null ? r.effect() : "-", r.type(), r.description() != null ? r.description() : "");
                }
            }
        }

        private static String formatList(List<?> items) {
            return (items == null || items.isEmpty()) ? "*" : items.stream().map(Object::toString).reduce((a, b) -> a + "," + b).orElse("*");
        }
    }

    @Command(name = "add", description = "Add a new HTTP policy rule")
    public static class AddCommand implements Runnable {
        @ParentCommand
        private RuleCommand parent;

        @Option(names = "--help", usageHelp = true, description = "Show this help message and exit.")
        private boolean help;

        @Option(names = {"-e", "--effect"}, required = true, description = "HttpRule effect: ALLOW, BLOCK, or ASK")
        private String effect;

        @Option(names = {"-m", "--methods"}, split = ",", description = "Comma-separated HTTP methods (e.g. GET,POST)")
        private List<String> methods = List.of();

        @Option(names = {"-h", "--hosts"}, split = ",", description = "Comma-separated hosts (e.g. api.github.com,*.slack.com)")
        private List<String> hosts = List.of();

        @Option(names = {"-p", "--ports"}, split = ",", description = "Comma-separated ports (e.g. 80,443)")
        private List<Integer> ports = List.of();

        @Option(names = {"--paths"}, split = ",", description = "Comma-separated paths (e.g. /v1/*)")
        private List<String> paths = List.of();

        @Option(names = {"-c", "--condition"}, description = "CEL condition expression")
        private String condition;

        @Option(names = {"-d", "--description"}, description = "HttpRule description")
        private String description;

        @Override
        public void run() {
            CreateHttpRuleRequest payload = new CreateHttpRuleRequest(
                    ProtocolConstants.HTTP,
                    effect.toUpperCase(),
                    description != null ? description : DEFAULT_RULE_DESCRIPTION,
                    methods,
                    hosts,
                    ports,
                    paths,
                    condition);

            Rule created = parent.getApiClient().createRule(payload);
            System.out.println("Rule created successfully with ID: " + created.id());
        }
    }

    @Command(name = "remove", description = "Remove a policy rule by ID", mixinStandardHelpOptions = true)
    public static class RemoveCommand implements Runnable {
        @ParentCommand
        private RuleCommand parent;

        @Parameters(index = "0", description = "HttpRule ID (UUID)")
        private String ruleId;

        @Override
        public void run() {
            parent.getApiClient().deleteRule(ruleId);
            System.out.println("Rule " + ruleId + " deleted successfully.");
        }
    }
}
