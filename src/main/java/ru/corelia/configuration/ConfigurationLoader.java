package ru.corelia.configuration;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Startup-only loading. Source layouts are normalized before they reach Corelia services. */
public final class ConfigurationLoader {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Pattern ID = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    public record LoadedConfiguration(DocumentTypeRegistry documentTypes, Map<String, Operation> operations) {
        public LoadedConfiguration {
            operations = Collections.unmodifiableMap(new LinkedHashMap<>(operations));
        }
    }
    public record Operation(String text, boolean multiaggregate) {}

    public LoadedConfiguration load(Path directory, String productVersion) {
        try {
            Path root = directory.toRealPath();
            JsonNode manifest = read(root.resolve("configuration.json"), "configuration.json");
            if (manifest == null || !manifest.isObject()) throw new ConfigurationException("Configuration must be an object");
            if (!manifest.path("schemaVersion").isIntegralNumber() || !manifest.path("schemaVersion").canConvertToInt())
                throw new ConfigurationException("Unsupported configuration schemaVersion");
            if (manifest.path("schemaVersion").asInt() != 2) throw new ConfigurationException("Unsupported configuration schemaVersion");
            return readV2(root, manifest, productVersion);
        } catch (IOException e) {
            throw new ConfigurationException("Cannot read configuration package: " + directory, e);
        }
    }

    private LoadedConfiguration readV2(Path root, JsonNode manifest, String productVersion) throws IOException {
        AttributeSchema.keywords(manifest, Set.of("schemaVersion", "compatibility", "sources"), "configuration");
        validateCompatibility(manifest, productVersion);
        JsonNode sources = manifest.path("sources");
        if (!sources.isObject()) throw new ConfigurationException("Missing sources");
        AttributeSchema.keywords(sources, Set.of("entities", "ui", "operations", "permissions"), "sources");
        Map<String, Path> roots = new LinkedHashMap<>();
        for (String type : List.of("entities", "ui", "operations", "permissions"))
            roots.put(type, sourceDirectory(root, DocumentTypeDefinition.requiredText(sources, type), type));

        var operations = readV2Operations(root, scan(roots.get("operations"), "operations"));
        var entities = fragmentsById(root, scan(roots.get("entities"), "entities"), "entity", Set.of("id", "title", "schemaVersion", "schema", "storage", "workflow", "attachments", "presentation", "normalization"));
        var ui = fragmentsById(root, scan(roots.get("ui"), "ui"), "ui", Set.of("id", "ui"));
        var permissions = fragmentsById(root, scan(roots.get("permissions"), "permissions"), "permissions", Set.of("id", "authorization"));
        var definitions = new ArrayList<DocumentTypeDefinition>();
        for (var entry : entities.entrySet()) {
            String id = entry.getKey(); ObjectNode entity = (ObjectNode) entry.getValue().node().deepCopy();
            Fragment uiFragment = ui.remove(id), permissionFragment = permissions.remove(id);
            if (uiFragment == null) throw new ConfigurationException(entry.getValue().display() + ": Missing UI fragment for '" + id + "'");
            if (permissionFragment == null) throw new ConfigurationException(entry.getValue().display() + ": Missing permissions fragment for '" + id + "'");
            entity.set("ui", uiFragment.node().path("ui").deepCopy());
            entity.set("authorization", permissionFragment.node().path("authorization").deepCopy());
            definitions.add(new DocumentTypeDefinition(entity, operations.keySet()));
        }
        if (!ui.isEmpty()) throw new ConfigurationException(ui.values().iterator().next().display() + ": Unknown entity '" + ui.keySet().iterator().next() + "'");
        if (!permissions.isEmpty()) throw new ConfigurationException(permissions.values().iterator().next().display() + ": Unknown entity '" + permissions.keySet().iterator().next() + "'");
        definitions.sort(Comparator.comparing(DocumentTypeDefinition::id));
        var registry = new DocumentTypeRegistry(definitions);
        return new LoadedConfiguration(registry, operations);
    }

    private Map<String, Operation> readV2Operations(Path root, List<Path> files) throws IOException {
        var result = new LinkedHashMap<String, Operation>();
        var origins = new LinkedHashMap<String, String>();
        for (Path file : files) {
            String display = display(root, file); JsonNode node = read(file, display);
            if (!node.isObject()) throw new ConfigurationException(display + ": Operation must be an object");
            AttributeSchema.keywords(node, Set.of("id", "file", "multiaggregate"), display);
            String id = DocumentTypeDefinition.requiredText(node, "id");
            validateIdAndName(id, file, "operation", display);
            if (origins.putIfAbsent(id, display) != null) throw new ConfigurationException("Duplicate operation '" + id + "'\nDefined in:\n  " + origins.get(id) + "\n  " + display);
            result.put(id, operation(root, file.getParent(), id, node, display));
        }
        if (result.isEmpty()) throw new ConfigurationException("operations must not be empty");
        return ordered(result);
    }

    private Operation operation(Path root, Path base, String id, JsonNode op, String display) throws IOException {
        if (!ID.matcher(id).matches() || !op.isObject()) throw new ConfigurationException(display + ": Invalid operation '" + id + "'");
        AttributeSchema.keywords(op, Set.of("id", "file", "multiaggregate"), display);
        if (!op.path("multiaggregate").isBoolean()) throw new ConfigurationException(display + ": Missing multiaggregate flag: " + id);
        String relative = DocumentTypeDefinition.requiredText(op, "file");
        Path graphql = base.resolve(relative).normalize();
        if (!graphql.startsWith(root) || !Files.isRegularFile(graphql) || !graphql.toRealPath().startsWith(root))
            throw new ConfigurationException(display + ": GraphQL resource does not exist: " + relative);
        String text = Files.readString(graphql).trim();
        var name = Pattern.compile("^(?:query|mutation)\\s+([A-Za-z_][A-Za-z0-9_]*)(?=[\\s({])").matcher(text);
        if (!name.find() || !id.equals(name.group(1))) throw new ConfigurationException(display + ": Operation name mismatch: " + id);
        return new Operation(text, op.path("multiaggregate").asBoolean());
    }

    private Map<String, Fragment> fragmentsById(Path root, List<Path> files, String label, Set<String> keys) throws IOException {
        var result = new LinkedHashMap<String, Fragment>();
        for (Path file : files) {
            String display = display(root, file); JsonNode node = read(file, display);
            if (!node.isObject()) throw new ConfigurationException(display + ": " + label + " must be an object");
            AttributeSchema.keywords(node, keys, display);
            String id = DocumentTypeDefinition.requiredText(node, "id");
            validateIdAndName(id, file, label, display);
            Fragment previous = result.putIfAbsent(id, new Fragment(display, node));
            if (previous != null) throw new ConfigurationException("Duplicate " + label + " '" + id + "'\nDefined in:\n  " + previous.display() + "\n  " + display);
        }
        if (result.isEmpty()) throw new ConfigurationException(label + " fragments must not be empty");
        return result;
    }

    private void validateIdAndName(String id, Path file, String type, String display) {
        if (!ID.matcher(id).matches()) throw new ConfigurationException(display + ": Invalid " + type + " identifier: " + id);
        String expected = id.replaceAll("([a-z0-9])([A-Z])", "$1-$2").replace('_', '-').toLowerCase(Locale.ROOT) + ".json";
        if (!expected.equals(file.getFileName().toString())) throw new ConfigurationException(display + ": Expected file name '" + expected + "' for " + type + " '" + id + "'");
    }

    private static Map<String, Operation> ordered(Map<String, Operation> operations) {
        var result = new LinkedHashMap<String, Operation>();
        new TreeMap<>(operations).forEach(result::put);
        return result;
    }

    private void validateCompatibility(JsonNode config, String productVersion) {
        JsonNode compatibility = config.path("compatibility");
        if (!compatibility.isObject()) throw new ConfigurationException("Missing compatibility");
        AttributeSchema.keywords(compatibility, Set.of("corelia"), "compatibility");
        checkVersion(DocumentTypeDefinition.requiredText(compatibility, "corelia"), productVersion);
    }
    private Path sourceDirectory(Path root, String relative, String type) throws IOException {
        Path path = inside(root, relative);
        if (!Files.isDirectory(path)) throw new ConfigurationException("Source '" + type + "' must be a directory: " + relative);
        return path;
    }
    private List<Path> scan(Path directory, String type) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing(Path::toString)).toList();
        }
    }
    private static JsonNode read(Path file, String display) throws IOException {
        if (!Files.isRegularFile(file)) throw new ConfigurationException("Configuration resource is not a file: " + display);
        return JSON.readTree(Files.readString(file));
    }
    private static Path inside(Path root, String relative) throws IOException {
        Path path = Path.of(relative);
        if (path.isAbsolute() || !root.resolve(path).normalize().startsWith(root)) throw new ConfigurationException("Configuration path escapes package");
        Path resolved = root.resolve(path).toRealPath();
        if (!resolved.startsWith(root)) throw new ConfigurationException("Configuration resource escapes package");
        return resolved;
    }
    private static String display(Path root, Path file) { return root.relativize(file).toString().replace('\\', '/'); }
    private record Fragment(String display, JsonNode node) {}
    /** Initial explicit range grammar: >=MAJOR.MINOR.PATCH <MAJOR.MINOR.PATCH. */
    private static void checkVersion(String range, String version) {
        var match = Pattern.compile(">=([0-9]+\\.[0-9]+\\.[0-9]+) <([0-9]+\\.[0-9]+\\.[0-9]+)").matcher(range);
        if (!match.matches() || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) throw new ConfigurationException("Unsupported Corelia version range");
        if (compare(match.group(1), match.group(2)) >= 0 || compare(version, match.group(1)) < 0 || compare(version, match.group(2)) >= 0)
            throw new ConfigurationException("Incompatible Corelia version: " + version);
    }
    private static int compare(String a, String b) {
        String[] left = a.split("\\."), right = b.split("\\.");
        for (int i = 0; i < 3; i++) { int value = new java.math.BigInteger(left[i]).compareTo(new java.math.BigInteger(right[i])); if (value != 0) return value; }
        return 0;
    }
}
