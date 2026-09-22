package ru.corelia.configuration;

import tools.jackson.databind.JsonNode;
import java.util.*;

/** Неизменяемые доменные метаданные; вызывающие получают защитные копии JSON. */
public final class DocumentTypeDefinition {
    private final JsonNode definition;
    private final AttributeSchema schema;
    public DocumentTypeDefinition(JsonNode input) {
        if (input == null || !input.isObject()) throw new ConfigurationException("documentTypes entries must be objects");
        definition = input.deepCopy();
        AttributeSchema.keywords(definition, Set.of("id", "title", "schemaVersion", "schema", "ui", "workflow", "attachments", "presentation", "normalization", "authorization"), "documentType");
        requiredText(definition, "id"); requiredText(definition, "title");
        if (!id().matches("[A-Za-z_][A-Za-z0-9_]*")) throw new ConfigurationException("Invalid document type identifier");
        if (!definition.path("schemaVersion").isIntegralNumber() || !definition.path("schemaVersion").canConvertToInt() || schemaVersion() < 1)
            throw new ConfigurationException("Invalid attribute schemaVersion: " + id());
        schema = new AttributeSchema(definition.path("schema"));
        if (definition.has("normalization")) {
            JsonNode normalization = definition.path("normalization");
            if (!normalization.isObject()) throw new ConfigurationException("Invalid normalization");
            for (var entry : normalization.properties()) {
                if (!schema.fields().contains(entry.getKey()) || !entry.getValue().isObject()) throw new ConfigurationException("Invalid normalization field");
                AttributeSchema.keywords(entry.getValue(), Set.of("trim", "nullAsEmpty", "defaultEmpty"), "normalization");
                if (!"string".equals(schema.definition().path("properties").path(entry.getKey()).path("type").asString())) throw new ConfigurationException("Normalization requires string field");
                for (JsonNode flag : entry.getValue()) if (!flag.isBoolean()) throw new ConfigurationException("Invalid normalization flag");
            }
        }
        if (definition.has("presentation")) {
            JsonNode presentation = definition.path("presentation");
            if (!presentation.isObject()) throw new ConfigurationException("Invalid presentation");
            AttributeSchema.keywords(presentation, Set.of("statuses", "aliases", "tones", "initialStatus"), "presentation");
            for (String key : List.of("statuses", "aliases", "tones")) {
                if (!presentation.path(key).isObject()) throw new ConfigurationException("Invalid presentation." + key);
                for (var entry : presentation.path(key).properties()) requiredText(presentation.path(key), entry.getKey());
            }
            requiredText(presentation, "initialStatus");
            if (!presentation.path("statuses").has(presentation.path("initialStatus").asString())) throw new ConfigurationException("Unknown initial status");
            for (JsonNode alias : presentation.path("aliases")) if (!presentation.path("statuses").has(alias.asString())) throw new ConfigurationException("Unknown status alias");
            for (var tone : presentation.path("tones").properties()) if (!presentation.path("statuses").has(tone.getKey()) || !Set.of("success", "warning", "error", "info").contains(tone.getValue().asString())) throw new ConfigurationException("Invalid status tone");
        }
        if (definition.has("authorization")) {
            JsonNode authorization = definition.path("authorization");
            if (!authorization.isObject()) throw new ConfigurationException("Invalid authorization");
            AttributeSchema.keywords(authorization, Set.of("createPermission", "editPermission", "executorRole", "editableStatuses", "initialUploadStatuses"), "authorization");
            for (String key : List.of("createPermission", "editPermission", "executorRole")) requiredText(authorization, key);
            for (String key : List.of("editableStatuses", "initialUploadStatuses")) {
                if (!authorization.path(key).isArray()) throw new ConfigurationException("Invalid authorization " + key);
                for (JsonNode status : authorization.path(key)) if (!status.isTextual() || !definition.path("presentation").path("statuses").has(status.asString())) throw new ConfigurationException("Unknown authorization status");
            }
        }
        JsonNode ui = definition.path("ui");
        if (!ui.isObject()) throw new ConfigurationException("Missing ui: " + id());
        AttributeSchema.keywords(ui, Set.of("columns", "fields", "searchFields", "dateField", "sortFields"), "ui");
        for (String key : List.of("columns", "fields", "searchFields", "sortFields")) {
            if (!ui.path(key).isArray()) throw new ConfigurationException("ui." + key + " must be an array");
            Set<String> seen = new HashSet<>();
            for (JsonNode field : ui.path(key)) {
                if (!field.isTextual() || !schema.fields().contains(field.asString()) || !seen.add(field.asString()))
                    throw new ConfigurationException("Invalid ui field reference: " + id() + "." + key);
            }
        }
        if (ui.has("dateField") && (!ui.path("dateField").isTextual() || !schema.fields().contains(ui.path("dateField").asString())
                || !"date".equals(schema.definition().path("properties").path(ui.path("dateField").asString()).path("format").asString())))
            throw new ConfigurationException("Invalid ui.dateField: " + id());
        JsonNode workflow = definition.path("workflow");
        if (!workflow.isObject()) throw new ConfigurationException("Missing workflow: " + id());
        AttributeSchema.keywords(workflow, Set.of("completion", "terminalStatuses"), "workflow");
        if (workflow.has("terminalStatuses")) {
            if (!workflow.path("terminalStatuses").isArray()) throw new ConfigurationException("Invalid terminalStatuses");
            for (JsonNode status : workflow.path("terminalStatuses")) if (!status.isTextual() || !definition.path("presentation").path("statuses").has(status.asString())) throw new ConfigurationException("Unknown terminal status");
        }
        if (workflow.has("completion")) {
            JsonNode completion = workflow.path("completion");
            if (!completion.isObject()) throw new ConfigurationException("Invalid workflow completion");
            AttributeSchema.keywords(completion, Set.of("statusField", "assigneeField", "assignmentStatuses", "assignmentCodes", "autoStart"), "completion");
            for (String key : List.of("statusField", "assigneeField")) if (!requiredText(completion, key).matches("[A-Za-z_][A-Za-z0-9_]*")) throw new ConfigurationException("Invalid completion field");
            for (String key : List.of("assignmentStatuses", "assignmentCodes")) {
                if (!completion.path(key).isArray()) throw new ConfigurationException("Invalid completion " + key);
                for (JsonNode value : completion.path(key)) if (!value.isTextual() || value.asString().isBlank()) throw new ConfigurationException("Invalid completion value");
            }
            if (!completion.path("autoStart").isBoolean()) throw new ConfigurationException("Invalid completion autoStart");
        }
        JsonNode attachments = definition.path("attachments");
        if (!attachments.isObject()) throw new ConfigurationException("Missing attachments: " + id());
        AttributeSchema.keywords(attachments, Set.of("enabled", "initialRequired", "maxCount", "maxSizeBytes", "allowedExtensions"), "attachments");
        if (!attachments.path("enabled").isBoolean() || !attachments.path("initialRequired").isBoolean()
                || !attachments.path("maxCount").isIntegralNumber() || !attachments.path("maxCount").canConvertToInt() || attachments.path("maxCount").asInt() < 0
                || attachments.path("initialRequired").asBoolean() && (!attachments.path("enabled").asBoolean() || attachments.path("maxCount").asInt() == 0)
                || !attachments.path("enabled").asBoolean() && attachments.path("maxCount").asInt() != 0)
            throw new ConfigurationException("Invalid attachment policy: " + id());
        var attachmentPolicy = (tools.jackson.databind.node.ObjectNode) attachments;
        if (!attachmentPolicy.has("maxSizeBytes")) attachmentPolicy.put("maxSizeBytes", 10L * 1024 * 1024);
        if (!attachmentPolicy.path("maxSizeBytes").isIntegralNumber() || !attachmentPolicy.path("maxSizeBytes").canConvertToLong()
                || attachmentPolicy.path("maxSizeBytes").asLong() < 1 || attachmentPolicy.path("maxSizeBytes").asLong() > 1024L * 1024 * 1024)
            throw new ConfigurationException("Invalid attachment maxSizeBytes: " + id());
        if (!attachmentPolicy.has("allowedExtensions")) attachmentPolicy.putArray("allowedExtensions");
        if (!attachmentPolicy.path("allowedExtensions").isArray())
            throw new ConfigurationException("Invalid attachment allowedExtensions: " + id());
        for (JsonNode extension : attachmentPolicy.path("allowedExtensions"))
            if (!extension.isTextual() || !extension.asString().matches("[a-z0-9]+")) throw new ConfigurationException("Invalid attachment extension: " + id());
    }
    public String id() { return definition.path("id").asString(); }
    public String title() { return definition.path("title").asString(); }
    public int schemaVersion() { return definition.path("schemaVersion").asInt(); }
    public AttributeSchema schema() { return schema; }
    public JsonNode validate(JsonNode attributes, boolean partial) {
        if (attributes == null || !attributes.isObject()) return schema.validate(attributes, partial);
        var normalized = (tools.jackson.databind.node.ObjectNode) attributes.deepCopy();
        for (var entry : definition.path("normalization").properties()) {
            String field = entry.getKey(); JsonNode rules = entry.getValue(), value = normalized.path(field);
            if (value.isMissingNode() && !partial && rules.path("defaultEmpty").asBoolean()
                    || value.isNull() && rules.path("nullAsEmpty").asBoolean()) normalized.put(field, "");
            else if (value.isTextual() && rules.path("trim").asBoolean()) normalized.put(field, value.asString().trim());
        }
        return schema.validate(normalized, partial);
    }
    public JsonNode authorization() { return definition.path("authorization").deepCopy(); }
    public JsonNode presentation() { return definition.path("presentation").deepCopy(); }
    public JsonNode definition() { return definition.deepCopy(); }
    public JsonNode ui() { return definition.path("ui").deepCopy(); }
    public JsonNode workflow() { return definition.path("workflow").deepCopy(); }
    public JsonNode attachments() { return definition.path("attachments").deepCopy(); }
    static String requiredText(JsonNode node, String key) {
        if (!node.path(key).isTextual() || node.path(key).asString().isBlank()) throw new ConfigurationException("Missing text: " + key);
        return node.path(key).asString();
    }
}
