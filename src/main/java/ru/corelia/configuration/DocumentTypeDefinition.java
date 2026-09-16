package ru.corelia.configuration;

import tools.jackson.databind.JsonNode;
import java.util.*;

/** Immutable customer metadata; callers receive defensive copies of JSON values. */
public final class DocumentTypeDefinition {
    private final JsonNode definition;
    private final AttributeSchema schema;
    public DocumentTypeDefinition(JsonNode input, Set<String> operations) {
        if (input == null || !input.isObject()) throw new ConfigurationException("documentTypes entries must be objects");
        definition = input.deepCopy();
        AttributeSchema.keywords(definition, Set.of("id", "title", "schemaVersion", "schema", "ui", "storage", "workflow", "attachments", "presentation", "normalization", "authorization"), "documentType");
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
        JsonNode storage = definition.path("storage");
        if (!storage.isObject()) throw new ConfigurationException("Missing storage: " + id());
        AttributeSchema.keywords(storage, Set.of("provider", "entity", "details", "operations", "fields"), "storage");
        requiredText(storage, "provider"); requiredText(storage, "entity"); requiredText(storage, "details");
        if (!storage.path("operations").isObject() || storage.path("operations").isEmpty()) throw new ConfigurationException("Missing storage operations");
        for (var op : storage.path("operations").properties()) {
            if (!op.getValue().isTextual() || !operations.contains(op.getValue().asString())) throw new ConfigurationException("Unknown storage operation: " + op.getKey());
        }
        JsonNode mapping = storage.path("fields");
        if (!mapping.isObject() || !mapping.propertyNames().equals(new LinkedHashSet<>(schema.fields()))) throw new ConfigurationException("Incomplete storage fields: " + id());
        Set<String> targets = new HashSet<>();
        for (JsonNode field : mapping) if (!field.isTextual() || !field.asString().matches("[A-Za-z_][A-Za-z0-9_]*") || Set.of("id", "status", "document").contains(field.asString()) || !targets.add(field.asString())) throw new ConfigurationException("Invalid or duplicate storage field mapping");
        JsonNode workflow = definition.path("workflow");
        if (!workflow.isObject()) throw new ConfigurationException("Missing workflow: " + id());
        AttributeSchema.keywords(workflow, Set.of("processes", "actions", "creationSource", "creationAction", "externalFields", "completion", "terminalStatuses"), "workflow");
        if (!workflow.path("processes").isObject() || !workflow.path("actions").isObject()) throw new ConfigurationException("Invalid workflow");
        for (var process : workflow.path("processes").properties()) requiredText(workflow.path("processes"), process.getKey());
        for (JsonNode alias : workflow.path("actions")) if (!alias.isTextual() || !workflow.path("processes").has(alias.asString())) throw new ConfigurationException("Unknown process alias: " + id());
        if (workflow.has("creationSource") && !Set.of("platform-settings", "configuration").contains(workflow.path("creationSource").asString())) throw new ConfigurationException("Invalid creationSource");
        if ("configuration".equals(workflow.path("creationSource").asString()) && !workflow.path("actions").has(requiredText(workflow, "creationAction"))) throw new ConfigurationException("Unknown creation action");
        if (workflow.has("externalFields")) {
            if (!workflow.path("externalFields").isArray()) throw new ConfigurationException("Invalid externalFields");
            for (JsonNode field : workflow.path("externalFields")) if (!field.isTextual() || !schema.fields().contains(field.asString())) throw new ConfigurationException("Unknown external field");
        }
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
        AttributeSchema.keywords(attachments, Set.of("enabled", "initialRequired", "maxCount"), "attachments");
        if (!attachments.path("enabled").isBoolean() || !attachments.path("initialRequired").isBoolean()
                || !attachments.path("maxCount").isIntegralNumber() || !attachments.path("maxCount").canConvertToInt() || attachments.path("maxCount").asInt() < 0
                || attachments.path("initialRequired").asBoolean() && (!attachments.path("enabled").asBoolean() || attachments.path("maxCount").asInt() == 0)
                || !attachments.path("enabled").asBoolean() && attachments.path("maxCount").asInt() != 0)
            throw new ConfigurationException("Invalid attachment policy: " + id());
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
    public JsonNode storage() { return definition.path("storage").deepCopy(); }
    public JsonNode workflow() { return definition.path("workflow").deepCopy(); }
    public JsonNode attachments() { return definition.path("attachments").deepCopy(); }
    static String requiredText(JsonNode node, String key) {
        if (!node.path(key).isTextual() || node.path(key).asString().isBlank()) throw new ConfigurationException("Missing text: " + key);
        return node.path(key).asString();
    }
}
