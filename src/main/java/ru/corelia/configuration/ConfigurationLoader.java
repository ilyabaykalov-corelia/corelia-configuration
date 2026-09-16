package ru.corelia.configuration;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Startup-only loading. No fallback to a built-in customer's configuration. */
public final class ConfigurationLoader {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    public record LoadedConfiguration(DocumentTypeRegistry documentTypes, Map<String, Operation> operations) {
        public LoadedConfiguration { operations = Collections.unmodifiableMap(new LinkedHashMap<>(operations)); }
    }
    public record Operation(String text, boolean multiaggregate) {}

    public LoadedConfiguration load(Path directory, String productVersion) {
        try {
            Path root = directory.toRealPath();
            JsonNode config = JSON.readTree(Files.readString(inside(root, "configuration.json")));
            if (config == null || !config.isObject()) throw new ConfigurationException("Configuration must be an object");
            AttributeSchema.keywords(config, Set.of("schemaVersion", "compatibility", "operations", "documentTypes"), "configuration");
            if (!config.path("schemaVersion").isIntegralNumber() || !config.path("schemaVersion").canConvertToInt() || config.path("schemaVersion").asInt() != 1)
                throw new ConfigurationException("Unsupported configuration schemaVersion");
            JsonNode compatibility = config.path("compatibility");
            if (!compatibility.isObject()) throw new ConfigurationException("Missing compatibility");
            AttributeSchema.keywords(compatibility, Set.of("corelia"), "compatibility");
            checkVersion(DocumentTypeDefinition.requiredText(compatibility, "corelia"), productVersion);
            var operations = new LinkedHashMap<String, Operation>();
            if (!config.path("operations").isObject()) throw new ConfigurationException("operations must be an object");
            for (var entry : config.path("operations").properties()) {
                String id = entry.getKey(); JsonNode op = entry.getValue();
                if (!id.matches("[A-Za-z_][A-Za-z0-9_]*") || !op.isObject()) throw new ConfigurationException("Invalid operation: " + id);
                AttributeSchema.keywords(op, Set.of("file", "multiaggregate"), "operations." + id);
                if (!op.path("multiaggregate").isBoolean()) throw new ConfigurationException("Missing multiaggregate flag: " + id);
                String text = Files.readString(inside(root, DocumentTypeDefinition.requiredText(op, "file"))).trim();
                var name = java.util.regex.Pattern.compile("^(?:query|mutation)\\s+([A-Za-z_][A-Za-z0-9_]*)(?=[\\s({])").matcher(text);
                if (!name.find() || !id.equals(name.group(1))) throw new ConfigurationException("Operation name mismatch: " + id);
                operations.put(id, new Operation(text, op.path("multiaggregate").asBoolean()));
            }
            if (!config.path("documentTypes").isArray()) throw new ConfigurationException("documentTypes must be an array");
            var definitions = new ArrayList<DocumentTypeDefinition>();
            for (JsonNode definition : config.path("documentTypes")) definitions.add(new DocumentTypeDefinition(definition, operations.keySet()));
            return new LoadedConfiguration(new DocumentTypeRegistry(definitions), operations);
        } catch (IOException e) {
            throw new ConfigurationException("Cannot read configuration package: " + directory, e);
        }
    }
    private static Path inside(Path root, String relative) throws IOException {
        Path path = Path.of(relative);
        if (path.isAbsolute() || !root.resolve(path).normalize().startsWith(root)) throw new ConfigurationException("Configuration path escapes package");
        Path resolved = root.resolve(path).toRealPath();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) throw new ConfigurationException("Configuration resource escapes package or is not a file");
        return resolved;
    }
    /** Initial explicit range grammar: >=MAJOR.MINOR.PATCH <MAJOR.MINOR.PATCH. */
    private static void checkVersion(String range, String version) {
        var match = java.util.regex.Pattern.compile(">=([0-9]+\\.[0-9]+\\.[0-9]+) <([0-9]+\\.[0-9]+\\.[0-9]+)").matcher(range);
        if (!match.matches() || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) throw new ConfigurationException("Unsupported Corelia version range");
        if (compare(match.group(1), match.group(2)) >= 0 || compare(version, match.group(1)) < 0 || compare(version, match.group(2)) >= 0)
            throw new ConfigurationException("Incompatible Corelia version: " + version);
    }
    private static int compare(String a, String b) {
        String[] left = a.split("\\."), right = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int value = new java.math.BigInteger(left[i]).compareTo(new java.math.BigInteger(right[i]));
            if (value != 0) return value;
        }
        return 0;
    }
}
