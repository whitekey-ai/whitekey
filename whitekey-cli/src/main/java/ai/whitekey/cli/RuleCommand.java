package ai.whitekey.cli;

import ai.whitekey.api.dto.CreateHttpRuleRequest;
import ai.whitekey.api.dto.HttpRule;
import ai.whitekey.api.dto.ProtocolConstants;
import ai.whitekey.api.dto.Rule;
import ai.whitekey.api.dto.RuleEffect;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Command(name = "rules", description = "Manage policy rules",
        subcommands = {
                RuleCommand.ListCommand.class,
                RuleCommand.AddCommand.class,
                RuleCommand.RemoveCommand.class
        })
public class RuleCommand extends BaseCommand {

    @Command(name = "list", description = "List all active policy rules")
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
                    StringBuilder details = new StringBuilder();
                    if (!paths.equals("*")) {
                        details.append(' ').append(paths);
                    }
                    if (http.ports() != null && !http.ports().isEmpty()) {
                        details.append(" ports=").append(formatList(http.ports()));
                    }
                    if (http.queryParams() != null && !http.queryParams().isEmpty()) {
                        details.append(" query=").append(formatMultiMap(http.queryParams()));
                    }
                    if (http.headers() != null && !http.headers().isEmpty()) {
                        details.append(" headers=").append(formatMultiMap(http.headers()));
                    }
                    if (http.bodyCondition() != null && !http.bodyCondition().isBlank()) {
                        details.append(" when ").append(http.bodyCondition());
                    }
                    System.out.printf("%s  %-5s %-10s %s%s%n",
                            http.name(), effect, methods, hosts, details);
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

        private static String formatMultiMap(Map<String, List<String>> map) {
            return map.entrySet().stream()
                    .map(entry -> entry.getValue() == null || entry.getValue().isEmpty()
                            ? entry.getKey()
                            : entry.getKey() + "=" + String.join(",", entry.getValue()))
                    .collect(Collectors.joining(","));
        }
    }

    @Command(name = "add", description = "Add a new HTTP policy rule")
    public static class AddCommand extends BaseCommand {

        @Option(names = "--name", required = true, description = "Unique rule name (e.g. allow_github)")
        private String name;

        @Option(
                names = "--effect",
                required = true,
                converter = EffectConverter.class,
                description = "Rule effect: ${COMPLETION-CANDIDATES}")
        private RuleEffect effect;

        @Option(names = "--methods", split = ",", description = "Comma-separated HTTP methods (e.g. GET,POST)")
        private List<String> methods = List.of();

        @Option(names = "--hosts", required = true, split = ",", description = "Comma-separated exact DNS hosts or IP addresses (e.g. api.github.com,127.0.0.1,::1)")
        private List<String> hosts = List.of();

        @Option(names = "--ports", split = ",", description = "Comma-separated ports (e.g. 80,443)")
        private List<Integer> ports = List.of();

        @Option(names = "--paths", defaultValue = "/*", split = ",", description = "Comma-separated path patterns (default: /*, e.g. /v1/*)")
        private List<String> paths = List.of("/*");

        @Option(names = "--query", split = ",", description = "Comma-separated query params (e.g. env=prod,page=1)")
        private List<String> query = List.of();

        @Option(names = "--headers", split = ",", description = "Comma-separated HTTP headers (e.g. X-Env=prod)")
        private List<String> headers = List.of();

        @Option(names = "--condition", description = "CEL body condition expression")
        private String condition;

        @Override
        public void run() {
            CreateHttpRuleRequest payload = new CreateHttpRuleRequest(
                    name,
                    ProtocolConstants.HTTP,
                    effect.name(),
                    methods,
                    hosts,
                    ports,
                    paths,
                    parseMultiMap(query),
                    parseMultiMap(headers),
                    condition);

            Rule created = getApiClient().createRule(payload);
            System.out.println("Rule created: " + created.name());
        }

        private static Map<String, List<String>> parseMultiMap(List<String> entries) {
            if (entries == null || entries.isEmpty()) {
                return Map.of();
            }
            Map<String, List<String>> map = new LinkedHashMap<>();
            for (String entry : entries) {
                int eq = entry.indexOf('=');
                if (eq > 0) {
                    map.computeIfAbsent(entry.substring(0, eq).trim(), k -> new ArrayList<>())
                            .add(entry.substring(eq + 1).trim());
                } else {
                    map.computeIfAbsent(entry.trim(), k -> new ArrayList<>());
                }
            }
            return map;
        }
    }

    private static final class EffectConverter implements CommandLine.ITypeConverter<RuleEffect> {
        @Override
        public RuleEffect convert(String value) {
            return RuleEffect.fromString(value);
        }
    }

    @Command(name = "remove", description = "Remove a policy rule by name")
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
