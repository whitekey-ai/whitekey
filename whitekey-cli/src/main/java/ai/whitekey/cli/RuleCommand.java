package ai.whitekey.cli;

import ai.whitekey.api.dto.CreateHttpRuleRequest;
import ai.whitekey.api.dto.HttpRule;
import ai.whitekey.api.dto.ProtocolConstants;
import ai.whitekey.api.dto.Rule;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.stream.Collectors;

@Command(name = "rules", description = "Manage policy rules", mixinStandardHelpOptions = true,
        subcommands = {
                RuleCommand.ListCommand.class,
                RuleCommand.AddCommand.class,
                RuleCommand.RemoveCommand.class
        })
public class RuleCommand extends BaseCommand {

    @Override
    public void run() {
        System.out.println("Use 'rules --help' to see available subcommands (list, add, remove).");
    }

    @Command(name = "list", description = "List all active policy rules", mixinStandardHelpOptions = true)
    public static class ListCommand extends BaseCommand {

        @Override
        public void run() {
            List<Rule> rules = getApiClient().getRules();
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
                            http.name(), effect, methods, hosts, paths.equals("*") ? "" : " " + paths, cond);
                } else {
                    System.out.printf("%s  %-5s [%s]%n",
                            r.name(), r.effect() != null ? r.effect() : "-", r.type());
                }
            }
        }

        private static String formatList(List<?> items) {
            return (items == null || items.isEmpty())
                    ? "*"
                    : items.stream().map(Object::toString).collect(Collectors.joining(","));
        }
    }

    @Command(name = "add", description = "Add a new HTTP policy rule")
    public static class AddCommand extends BaseCommand {

        @Option(names = "--help", usageHelp = true, description = "Show this help message and exit.")
        private boolean help;

        @Option(names = {"-n", "--name"}, required = true, description = "Unique rule name (e.g. allow_github)")
        private String name;

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

        @Override
        public void run() {
            CreateHttpRuleRequest payload = new CreateHttpRuleRequest(
                    name,
                    ProtocolConstants.HTTP,
                    effect.toUpperCase(),
                    methods,
                    hosts,
                    ports,
                    paths,
                    condition);

            Rule created = getApiClient().createRule(payload);
            System.out.println("Rule created: " + created.name());
        }
    }

    @Command(name = "remove", description = "Remove a policy rule by name", mixinStandardHelpOptions = true)
    public static class RemoveCommand extends BaseCommand {

        @Parameters(index = "0", description = "Rule name")
        private String name;

        @Override
        public void run() {
            getApiClient().deleteRule(name);
            System.out.println("Rule '" + name + "' deleted successfully.");
        }
    }
}
