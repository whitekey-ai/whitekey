package ai.whitekey.policy;

import com.google.protobuf.NullValue;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.ast.CelExprVisitor;
import dev.cel.common.types.SimpleType;
import dev.cel.parser.CelParser;
import dev.cel.parser.CelParserFactory;

import org.springframework.stereotype.Component;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

@Component
public class CelBodyEvaluator {

    public static final String VAR_BODY = "body";
    public static final String VAR_DATA = "data";

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final CelParser PARSER = CelParserFactory.standardCelParserBuilder().build();

    private static final int MAX_CACHE_ENTRIES = 1000;
    private final Map<String, CompiledCelProgram> cache = new ConcurrentHashMap<>();

    private record CompiledCelProgram(Cel.Program program, Set<String> variables) {}

    private static class VariableCollector extends CelExprVisitor {
        private final Set<String> idents = new HashSet<>();

        @Override
        protected void visit(CelExpr expr, CelExpr.CelIdent ident) {
            idents.add(ident.name());
            super.visit(expr, ident);
        }
    }

    public void validate(String expression) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        try {
            getOrCompile(expression.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid CEL body expression: " + e.getMessage(), e);
        }
    }

    public boolean isValidJson(byte[] bodyBytes) {
        return withStream(bodyBytes, this::isValidJson);
    }

    public boolean isValidJson(Path filePath) {
        return withStream(filePath, this::isValidJson);
    }

    public boolean isValidJson(InputStream inputStream) {
        if (inputStream == null) {
            return true;
        }
        try (JsonParser parser = JSON.createParser(inputStream)) {
            JsonToken token = parser.nextToken();
            if (token == null) {
                return true;
            }
            if (token != JsonToken.START_OBJECT && token != JsonToken.START_ARRAY) {
                return false;
            }
            parser.skipChildren();
            return parser.nextToken() == null;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean matches(String expression, byte[] bodyBytes) {
        return withStream(bodyBytes, in -> matches(expression, in));
    }

    public boolean matches(String expression, Path filePath) {
        return withStream(filePath, in -> matches(expression, in));
    }

    private static boolean withStream(byte[] bodyBytes, Predicate<InputStream> consumer) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return consumer.test(null);
        }
        return consumer.test(new ByteArrayInputStream(bodyBytes));
    }

    private static boolean withStream(Path filePath, Predicate<InputStream> consumer) {
        try {
            if (filePath == null || !Files.exists(filePath) || Files.size(filePath) == 0) {
                return consumer.test(null);
            }
            try (InputStream in = Files.newInputStream(filePath)) {
                return consumer.test(in);
            }
        } catch (IOException e) {
            return false;
        }
    }

    public boolean matches(String expression, InputStream inputStream) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        CompiledCelProgram compiled;
        try {
            compiled = getOrCompile(expression.trim());
        } catch (Exception e) {
            return false;
        }

        Set<String> vars = compiled.variables();
        Map<String, Object> activation = new HashMap<>();

        if (inputStream != null) {
            try (JsonParser parser = JSON.createParser(inputStream)) {
                JsonToken token = parser.nextToken();
                if (token == JsonToken.START_OBJECT) {
                    while (parser.nextToken() != JsonToken.END_OBJECT && parser.currentToken() != null) {
                        String propName = parser.currentName();
                        parser.nextToken(); // Move to property value
                        if (vars.contains(propName)) {
                            Object val = JSON.readValue(parser, Object.class);
                            activation.put(propName, val != null ? val : NullValue.NULL_VALUE);
                        } else {
                            parser.skipChildren();
                        }
                    }
                } else if (token == JsonToken.START_ARRAY) {
                    Object arrayVal = JSON.readValue(parser, Object.class);
                    Object celVal = arrayVal != null ? arrayVal : NullValue.NULL_VALUE;
                    if (vars.contains(VAR_BODY)) {
                        activation.put(VAR_BODY, celVal);
                    }
                    if (vars.contains(VAR_DATA)) {
                        activation.put(VAR_DATA, celVal);
                    }
                }
                if (token != null && parser.nextToken() != null) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        }

        for (String var : vars) {
            activation.putIfAbsent(var, NullValue.NULL_VALUE);
        }

        try {
            Object result = compiled.program().eval(activation);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }

    private CompiledCelProgram getOrCompile(String expression) {
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        return cache.computeIfAbsent(expression, this::compile);
    }

    private CompiledCelProgram compile(String expression) {
        try {
            CelValidationResult parseResult = PARSER.parse(expression);
            if (parseResult.hasError()) {
                throw new IllegalArgumentException(parseResult.getErrorString());
            }

            CelAbstractSyntaxTree ast = parseResult.getAst();
            VariableCollector collector = new VariableCollector();
            collector.visit(ast);

            var celBuilder = CelFactory.plannerCelBuilder();
            for (String id : collector.idents) {
                celBuilder.addVar(id, SimpleType.DYN);
            }
            Cel cel = celBuilder.build();
            CelAbstractSyntaxTree checkedAst = cel.compile(expression).getAst();
            return new CompiledCelProgram(cel.createProgram(checkedAst), Set.copyOf(collector.idents));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("CEL compilation failed: " + e.getMessage(), e);
        }
    }
}
