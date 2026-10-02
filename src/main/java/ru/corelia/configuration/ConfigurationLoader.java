package ru.corelia.configuration;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Загрузка только при старте. Исходная структура нормализуется до передачи сервисам Corelia. */
public final class ConfigurationLoader {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Pattern ID = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** Нормализованный результат загрузки configuration package.
     * @param documentTypes реестр доступных типов
     * @param providerBindings storage/workflow binding, изолированные от domain definition
     * @param permissionGrants соответствие непрозрачных прав Corelia ролям identity provider-а
     * @param packageRoot канонический корень проверенного пакета
     */
    public record LoadedConfiguration(
            DocumentTypeRegistry documentTypes,
            Map<String, JsonNode> providerBindings,
            Map<String, Set<String>> permissionGrants,
            Path packageRoot) {
        public LoadedConfiguration {
            providerBindings = Collections.unmodifiableMap(new LinkedHashMap<>(providerBindings));
            var grants = new LinkedHashMap<String, Set<String>>();
            for (var entry : permissionGrants.entrySet()) grants.put(entry.getKey(), Set.copyOf(entry.getValue()));
            permissionGrants = Collections.unmodifiableMap(grants);
            packageRoot = packageRoot.toAbsolutePath().normalize();
        }

        /** Совместимость с существующими потребителями конфигурации без native permissions. */
        public LoadedConfiguration(DocumentTypeRegistry documentTypes, Map<String, JsonNode> providerBindings, Path packageRoot) {
            this(documentTypes, providerBindings, Map.of(), packageRoot);
        }
    }

    /** Загружает package версии 2 или 3, проверяя пути, fragments и совместимость версии Corelia. */
    public LoadedConfiguration load(Path directory, String productVersion) {
        try {
            Path root = directory.toRealPath();
            JsonNode manifest = read(root.resolve("configuration.json"), "configuration.json");
            if (manifest == null || !manifest.isObject()) throw new ConfigurationException("Configuration must be an object");
            if (!manifest.path("schemaVersion").isIntegralNumber() || !manifest.path("schemaVersion").canConvertToInt())
                throw new ConfigurationException("Unsupported configuration schemaVersion");
            return switch (manifest.path("schemaVersion").asInt()) {
                case 2 -> readV2(root, manifest, productVersion);
                case 3 -> readV3(root, manifest, productVersion);
                default -> throw new ConfigurationException("Unsupported configuration schemaVersion");
            };
        } catch (IOException e) {
            throw new ConfigurationException("Cannot read configuration package: " + directory, e);
        }
    }

    private LoadedConfiguration readV2(Path root, JsonNode manifest, String productVersion) throws IOException {
        AttributeSchema.keywords(manifest, Set.of("schemaVersion", "compatibility", "sources", "permissionGrants"), "configuration");
        validateCompatibility(manifest, productVersion);
        JsonNode sources = manifest.path("sources");
        if (!sources.isObject()) throw new ConfigurationException("Missing sources");
        AttributeSchema.keywords(sources, Set.of("entities", "ui", "operations", "permissions"), "sources");
        Map<String, Path> roots = new LinkedHashMap<>();
        for (String type : List.of("entities", "ui", "operations", "permissions"))
            roots.put(type, sourceDirectory(root, DocumentTypeDefinition.requiredText(sources, type), type));

        var entities = fragmentsById(root, scan(roots.get("entities"), "entities"), "entity", Set.of("id", "title", "schemaVersion", "schema", "storage", "workflow", "attachments", "presentation", "normalization"));
        var ui = fragmentsById(root, scan(roots.get("ui"), "ui"), "ui", Set.of("id", "ui"));
        var permissions = fragmentsById(root, scan(roots.get("permissions"), "permissions"), "permissions", Set.of("id", "authorization"));
        var definitions = new ArrayList<DocumentTypeDefinition>();
        var bindings = new LinkedHashMap<String, JsonNode>();
        for (var entry : entities.entrySet()) {
            String id = entry.getKey(); ObjectNode entity = (ObjectNode) entry.getValue().node().deepCopy();
            Fragment uiFragment = ui.remove(id), permissionFragment = permissions.remove(id);
            if (uiFragment == null) throw new ConfigurationException(entry.getValue().display() + ": Missing UI fragment for '" + id + "'");
            if (permissionFragment == null) throw new ConfigurationException(entry.getValue().display() + ": Missing permissions fragment for '" + id + "'");
            entity.set("ui", uiFragment.node().path("ui").deepCopy());
            entity.set("authorization", permissionFragment.node().path("authorization").deepCopy());
            JsonNode storage = entity.remove("storage");
            JsonNode workflow = entity.path("workflow");
            var binding = JSON.createObjectNode();
            binding.set("storage", storage == null ? JSON.getNodeFactory().nullNode() : storage);
            binding.set("workflow", workflow.deepCopy());
            bindings.put(id, binding);
            var coreWorkflow = JSON.createObjectNode();
            for (String field : List.of("completion", "terminalStatuses", "commands")) if (workflow.has(field)) coreWorkflow.set(field, workflow.path(field).deepCopy());
            entity.set("workflow", coreWorkflow);
            definitions.add(new DocumentTypeDefinition(entity));
        }
        if (!ui.isEmpty()) throw new ConfigurationException(ui.values().iterator().next().display() + ": Unknown entity '" + ui.keySet().iterator().next() + "'");
        if (!permissions.isEmpty()) throw new ConfigurationException(permissions.values().iterator().next().display() + ": Unknown entity '" + permissions.keySet().iterator().next() + "'");
        definitions.sort(Comparator.comparing(DocumentTypeDefinition::id));
        var registry = new DocumentTypeRegistry(definitions);
        return new LoadedConfiguration(registry, bindings, permissionGrants(manifest), root);
    }

    /**
     * Загружает V3 package. В отличие от V2, один документный fragment содержит data model,
     * search и UI metadata, а workflow и permission policy вынесены в самостоятельные fragments.
     * На выходе остаётся существующая нейтральная модель, поэтому сервисы не зависят от версии package.
     */
    private LoadedConfiguration readV3(Path root, JsonNode manifest, String productVersion) throws IOException {
        AttributeSchema.keywords(manifest, Set.of("schemaVersion", "compatibility", "sources", "permissionGrants", "branding"), "configuration");
        validateCompatibility(manifest, productVersion);
        JsonNode sources = manifest.path("sources");
        if (!sources.isObject()) throw new ConfigurationException("Missing sources");
        AttributeSchema.keywords(sources, Set.of("documents", "workflows", "permissions"), "sources");
        Map<String, Path> roots = new LinkedHashMap<>();
        for (String type : List.of("documents", "workflows", "permissions"))
            roots.put(type, sourceDirectory(root, DocumentTypeDefinition.requiredText(sources, type), type));

        var documents = fragmentsById(root, scan(roots.get("documents"), "documents"), "document", Set.of(
                "id", "title", "schemaVersion", "attributes", "presentation", "normalization", "ui", "search", "attachments"));
        var workflows = fragmentsById(root, scan(roots.get("workflows"), "workflows"), "workflow", Set.of(
                "id", "processKey", "bpmnFile", "startActions", "tasks", "completion", "terminalStatuses", "commands"));
        var permissions = fragmentsById(root, scan(roots.get("permissions"), "permissions"), "permissions", Set.of(
                "id", "permissions", "executorRole", "editableStatuses", "initialUploadStatuses"));
        var definitions = new ArrayList<DocumentTypeDefinition>();
        var bindings = new LinkedHashMap<String, JsonNode>();
        for (var entry : documents.entrySet()) {
            String id = entry.getKey();
            Fragment workflow = workflows.remove(id), permission = permissions.remove(id);
            if (workflow == null) throw new ConfigurationException(entry.getValue().display() + ": Missing workflow fragment for '" + id + "'");
            if (permission == null) throw new ConfigurationException(entry.getValue().display() + ": Missing permissions fragment for '" + id + "'");
            ObjectNode definition = (ObjectNode) entry.getValue().node().deepCopy();
            definition.set("schema", definition.remove("attributes"));
            definition.set("ui", normalizedV3Ui(id, definition.path("ui"), definition.path("search")));
            definition.remove("search");
            definition.set("authorization", normalizedV3Authorization(permission.node()));
            definition.set("workflow", coreWorkflow(workflow.node()));
            definitions.add(new DocumentTypeDefinition(definition));
            bindings.put(id, v3Binding(workflow.node()));
        }
        if (!workflows.isEmpty()) throw new ConfigurationException(workflows.values().iterator().next().display() + ": Unknown document '" + workflows.keySet().iterator().next() + "'");
        if (!permissions.isEmpty()) throw new ConfigurationException(permissions.values().iterator().next().display() + ": Unknown document '" + permissions.keySet().iterator().next() + "'");
        definitions.sort(Comparator.comparing(DocumentTypeDefinition::id));
        return new LoadedConfiguration(new DocumentTypeRegistry(definitions), bindings, permissionGrants(manifest), root);
    }

    private ObjectNode normalizedV3Ui(String id, JsonNode ui, JsonNode search) {
        if (!ui.isObject()) throw new ConfigurationException("Missing ui: " + id);
        AttributeSchema.keywords(ui, Set.of("createForm", "viewCard", "editCard", "table", "sections", "tabs", "dateField", "masks", "initialValues"), "ui");
        JsonNode createForm = ui.path("createForm"), viewCard = ui.path("viewCard"), editCard = ui.path("editCard"), table = ui.path("table");
        for (JsonNode form : List.of(createForm, viewCard, editCard)) {
            if (!form.isObject() || !form.path("fields").isArray()) throw new ConfigurationException("V3 form must declare fields: " + id);
            AttributeSchema.keywords(form, Set.of("fields", "label", "sections", "tabs"), "ui form");
        }
        if (!table.isObject() || !table.path("columns").isArray()) throw new ConfigurationException("V3 table must declare columns: " + id);
        AttributeSchema.keywords(table, Set.of("columns", "label"), "ui table");
        if (!search.isObject()) throw new ConfigurationException("Missing search: " + id);
        AttributeSchema.keywords(search, Set.of("filterableFields", "sortableFields", "indexHints"), "search");
        for (String key : List.of("filterableFields", "sortableFields", "indexHints")) if (!search.path(key).isArray()) throw new ConfigurationException("Invalid search." + key + ": " + id);
        var normalized = JSON.createObjectNode();
        normalized.set("fields", editCard.path("fields").deepCopy());
        normalized.set("columns", table.path("columns").deepCopy());
        normalized.set("searchFields", search.path("filterableFields").deepCopy());
        normalized.set("sortFields", search.path("sortableFields").deepCopy());
        normalized.set("createForm", createForm.deepCopy());
        normalized.set("viewCard", viewCard.deepCopy());
        normalized.set("editCard", editCard.deepCopy());
        normalized.set("table", table.deepCopy());
        normalized.set("sections", ui.path("sections").deepCopy());
        normalized.set("tabs", ui.path("tabs").deepCopy());
        normalized.set("indexHints", search.path("indexHints").deepCopy());
        for (String key : List.of("dateField", "masks", "initialValues")) if (ui.has(key)) normalized.set(key, ui.path(key).deepCopy());
        return normalized;
    }

    private ObjectNode normalizedV3Authorization(JsonNode fragment) {
        JsonNode permissions = fragment.path("permissions");
        if (!permissions.isObject()) throw new ConfigurationException("Missing permissions");
        AttributeSchema.keywords(permissions, Set.of("create", "read", "edit", "attachmentAdd"), "permissions");
        var authorization = JSON.createObjectNode();
        for (String key : List.of("create", "edit")) authorization.put(key + "Permission", DocumentTypeDefinition.requiredText(permissions, key));
        if (permissions.has("read")) authorization.put("readPermission", DocumentTypeDefinition.requiredText(permissions, "read"));
        if (permissions.has("attachmentAdd")) authorization.put("attachmentAddPermission", DocumentTypeDefinition.requiredText(permissions, "attachmentAdd"));
        authorization.put("executorRole", DocumentTypeDefinition.requiredText(fragment, "executorRole"));
        for (String key : List.of("editableStatuses", "initialUploadStatuses")) authorization.set(key, fragment.path(key).deepCopy());
        return authorization;
    }

    private ObjectNode coreWorkflow(JsonNode fragment) {
        DocumentTypeDefinition.requiredText(fragment, "processKey");
        DocumentTypeDefinition.requiredText(fragment, "bpmnFile");
        if (!fragment.path("startActions").isArray()) throw new ConfigurationException("Invalid workflow startActions");
        var workflow = JSON.createObjectNode();
        for (String field : List.of("completion", "terminalStatuses", "commands")) if (fragment.has(field)) workflow.set(field, fragment.path(field).deepCopy());
        return workflow;
    }

    private ObjectNode v3Binding(JsonNode fragment) {
        var binding = JSON.createObjectNode();
        binding.set("storage", JSON.getNodeFactory().nullNode());
        var workflow = binding.putObject("workflow");
        var flowable = workflow.putObject("flowable");
        flowable.put("definitionKey", fragment.path("processKey").asString());
        flowable.put("bpmn", fragment.path("bpmnFile").asString());
        flowable.set("startActions", fragment.path("startActions").deepCopy());
        if (fragment.has("tasks")) flowable.set("tasks", fragment.path("tasks").deepCopy());
        return binding;
    }

    private Map<String, Set<String>> permissionGrants(JsonNode manifest) {
        if (!manifest.has("permissionGrants")) return Map.of();
        JsonNode source = manifest.path("permissionGrants");
        if (!source.isObject() || source.isEmpty()) throw new ConfigurationException("permissionGrants must be a non-empty object");
        var grants = new LinkedHashMap<String, Set<String>>();
        for (var entry : source.properties()) {
            String permission = entry.getKey(); JsonNode roles = entry.getValue();
            if (permission.isBlank() || !roles.isArray() || roles.isEmpty())
                throw new ConfigurationException("Invalid permission grant: " + permission);
            var allowed = new LinkedHashSet<String>();
            for (JsonNode role : roles) {
                if (!role.isTextual() || role.asString().isBlank() || !allowed.add(role.asString()))
                    throw new ConfigurationException("Invalid role grant: " + permission);
            }
            grants.put(permission, Set.copyOf(allowed));
        }
        return grants;
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
