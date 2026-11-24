package ai.whitekey.policy;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class CelBodyEvaluator {

    private static final String VAR_BODY = "body";
    private static final String VAR_DATA = "data";
    private static final int MAX_CACHED_PROGRAMS = 512;

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final CelParser PARSER = CelParserFactory.standardCelParserBuilder().build();

    private final LoadingCache<String, CompiledCelProgram> cache = CacheBuilder.newBuilder()
            .maximumSize(MAX_CACHED_PROGRAMS)
            .build(CacheLoader.from(this::compile));

    public record BatchResult(boolean validJson, Set<String> matchedExpressions) {}

    private record CompiledCelProgram(Cel.Program program, Set<String> variables) {}

    public void validate(String expression) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        getOrCompile(expression.trim());
    }

    public BatchResult evaluateAll(Collection<String> expressions, Path bodyFile) {
        try {
            if (bodyFile == null || !Files.exists(bodyFile) || Files.size(bodyFile) == 0) {
                return evaluateAll(expressions, (InputStream) null);
            }
            try (InputStream in = Files.newInputStream(bodyFile)) {
                return evaluateAll(expressions, in);
            }
        } catch (IOException e) {
            return new BatchResult(false, Set.of());
        }
    }

    public BatchResult evaluateAll(Collection<String> expressions, byte[] bodyBytes) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return evaluateAll(expressions, (InputStream) null);
        }
        return evaluateAll(expressions, new ByteArrayInputStream(bodyBytes));
    }

    private BatchResult evaluateAll(Collection<String> expressions, InputStream inputStream) {
        Map<String, CompiledCelProgram> compiled = new LinkedHashMap<>();
        Set<String> allVars = new HashSet<>();
        if (expressions != null) {
            for (String raw : expressions) {
                String expr = raw != null ? raw.trim() : "";
                if (expr.isEmpty() || compiled.containsKey(expr)) {
                    continue;
                }
                try {
                    CompiledCelProgram program = getOrCompile(expr);
                    compiled.put(expr, program);
                    allVars.addAll(program.variables());
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        Map<String, Object> activation = new HashMap<>();
        if (!readActivation(inputStream, allVars, activation)) {
            return new BatchResult(false, Set.of());
        }
        allVars.forEach(var -> activation.putIfAbsent(var, NullValue.NULL_VALUE));

        Set<String> matched = new HashSet<>();
        for (Map.Entry<String, CompiledCelProgram> entry : compiled.entrySet()) {
            try {
                if (Boolean.TRUE.equals(entry.getValue().program().eval(activation))) {
                    matched.add(entry.getKey());
                }
            } catch (Exception ignored) {
            }
        }
        return new BatchResult(true, matched);
    }

    private static boolean readActivation(InputStream inputStream, Set<String> allVars, Map<String, Object> activation) {
        if (inputStream == null) {
            return true;
        }
        try (JsonParser parser = JSON.createParser(inputStream)) {
            JsonToken token = parser.nextToken();
            if (token == null) {
                return true;
            }
            if (token == JsonToken.START_OBJECT) {
                while (parser.nextToken() != JsonToken.END_OBJECT && parser.currentToken() != null) {
                    String propName = parser.currentName();
                    parser.nextToken();
                    if (allVars.contains(propName)) {
                        Object val = JSON.readValue(parser, Object.class);
                        activation.put(propName, val != null ? val : NullValue.NULL_VALUE);
                    } else {
                        parser.skipChildren();
                    }
                }
            } else if (token == JsonToken.START_ARRAY) {
                boolean needsRootArray = allVars.contains(VAR_BODY) || allVars.contains(VAR_DATA);
                if (needsRootArray) {
                    Object arrayVal = JSON.readValue(parser, Object.class);
                    Object celVal = arrayVal != null ? arrayVal : NullValue.NULL_VALUE;
                    if (allVars.contains(VAR_BODY)) {
                        activation.put(VAR_BODY, celVal);
                    }
                    if (allVars.contains(VAR_DATA)) {
                        activation.put(VAR_DATA, celVal);
                    }
                } else {
                    parser.skipChildren();
                }
            } else {
                return false;
            }
            return parser.nextToken() == null;
        } catch (Exception e) {
            return false;
        }
    }

    private CompiledCelProgram getOrCompile(String expression) {
        try {
            return cache.getUnchecked(expression);
        } catch (UncheckedExecutionException e) {
            if (e.getCause() instanceof IllegalArgumentException iae) {
                throw iae;
            }
            throw e;
        }
    }

    private CompiledCelProgram compile(String expression) {
        try {
            CelValidationResult parseResult = PARSER.parse(expression);
            if (parseResult.hasError()) {
                throw new IllegalArgumentException("Invalid CEL body expression: " + parseResult.getErrorString());
            }

            CelAbstractSyntaxTree ast = parseResult.getAst();
            Set<String> idents = new HashSet<>();
            new CelExprVisitor() {
                @Override
                protected void visit(CelExpr expr, CelExpr.CelIdent ident) {
                    idents.add(ident.name());
                    super.visit(expr, ident);
                }
            }.visit(ast);

            var celBuilder = CelFactory.plannerCelBuilder();
            idents.forEach(id -> celBuilder.addVar(id, SimpleType.DYN));
            Cel cel = celBuilder.build();
            CelValidationResult checkResult = cel.check(ast);
            if (checkResult.hasError()) {
                throw new IllegalArgumentException("Invalid CEL body expression: " + checkResult.getErrorString());
            }
            return new CompiledCelProgram(cel.createProgram(checkResult.getAst()), Set.copyOf(idents));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid CEL body expression: " + e.getMessage(), e);
        }
    }
}
