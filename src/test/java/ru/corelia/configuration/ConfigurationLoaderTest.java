package ru.corelia.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationLoaderTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path root;
    private ObjectNode config() {
        return (ObjectNode) JSON.readTree("""
            {"schemaVersion":1,"compatibility":{"corelia":">=0.1.0 <1.0.0"},
             "operations":{"readDocument":{"file":"read.graphql","multiaggregate":false}},
             "documentTypes":[{"id":"TEST_FORM","title":"Test form","schemaVersion":1,
               "schema":{"type":"object","additionalProperties":false,"required":["number"],
                 "properties":{"number":{"type":"string","minLength":1,"maxLength":4},
                   "date":{"type":"string","format":"date"},"amount":{"type":"number","minimum":0},
                   "year":{"type":"integer","minimum":1000,"maximum":9999},"approved":{"type":"boolean"}}},
               "ui":{"columns":["number"],"fields":["number","date","amount","year","approved"],"searchFields":["number"],"sortFields":["date"],"dateField":"date"},
               "storage":{"provider":"test","entity":"TestForm","details":"details","operations":{"get":"readDocument"},
                 "fields":{"number":"number","date":"date","amount":"amount","year":"year","approved":"approved"}},
               "workflow":{"processes":{"approval":"testApproval"},"actions":{"SEND":"approval"}},
               "attachments":{"enabled":true,"initialRequired":false,"maxCount":10}}]}
            """);
    }
    private ConfigurationLoader.LoadedConfiguration load(ObjectNode config) throws Exception {
        Files.createDirectories(root.resolve("data-model/entities"));
        Files.createDirectories(root.resolve("ui")); Files.createDirectories(root.resolve("permissions"));
        Files.createDirectories(root.resolve("operations")); Files.createDirectories(root.resolve("graphql"));
        Files.writeString(root.resolve("configuration.json"), "{\"schemaVersion\":2,\"compatibility\":{\"corelia\":\">=0.1.0 <1.0.0\"},\"sources\":{\"entities\":\"data-model/entities\",\"ui\":\"ui\",\"operations\":\"operations\",\"permissions\":\"permissions\"}}");
        ObjectNode definition = type(config);
        if (!definition.has("presentation")) { ObjectNode presentation = definition.putObject("presentation"); presentation.putObject("statuses").put("CREATED", "Created"); presentation.putObject("aliases"); presentation.putObject("tones"); presentation.put("initialStatus", "CREATED"); }
        ObjectNode entity = definition.deepCopy(); entity.remove("ui"); entity.remove("authorization");
        Files.writeString(root.resolve("data-model/entities/test-form.json"), JSON.writeValueAsString(entity));
        ObjectNode ui = JSON.createObjectNode().put("id", "TEST_FORM"); ui.set("ui", definition.path("ui").deepCopy());
        Files.writeString(root.resolve("ui/test-form.json"), JSON.writeValueAsString(ui));
        ObjectNode policy = JSON.createObjectNode().put("createPermission", "create").put("editPermission", "edit").put("executorRole", "operator");
        policy.putArray("editableStatuses").add("CREATED"); policy.putArray("initialUploadStatuses").add("CREATED");
        ObjectNode authorization = JSON.createObjectNode().put("id", "TEST_FORM"); authorization.set("authorization", policy);
        Files.writeString(root.resolve("permissions/test-form.json"), JSON.writeValueAsString(authorization));
        Files.writeString(root.resolve("operations/read-document.json"), "{\"id\":\"readDocument\",\"file\":\"../graphql/read.graphql\",\"multiaggregate\":false}");
        Files.writeString(root.resolve("graphql/read.graphql"), "query readDocument { documents { id } }");
        return new ConfigurationLoader().load(root, "0.1.0");
    }
    private ObjectNode type(ObjectNode config) { return (ObjectNode) config.path("documentTypes").get(0); }
    @Test void loadsAndDefensivelyCopiesAllMutableMetadata() throws Exception {
        var registry = load(config()).documentTypes();
        var type = registry.require("TEST_FORM");
        ((ObjectNode) type.ui()).put("dateField", "invalid");
        ((ObjectNode) type.schema().definition()).remove("properties");
        assertEquals("date", type.ui().path("dateField").asString());
        assertEquals(5, type.schema().fields().size());
        assertThrows(UnsupportedOperationException.class, () -> registry.all().clear());
        assertThrows(ConfigurationException.class, () -> registry.require("MISSING"));
    }
    @Test void validatesTypesBoundsDatesUnknownFieldsAndPartialUpdates() throws Exception {
        var schema = load(config()).documentTypes().require("TEST_FORM").schema();
        for (String value : new String[]{"{}", "{\"number\":null}", "{\"number\":\"\"}", "{\"number\":\"12345\"}", "{\"number\":\"1\",\"date\":\"2025-02-29\"}", "{\"number\":\"1\",\"amount\":-0.01}", "{\"number\":\"1\",\"year\":2020.5}", "{\"number\":\"1\",\"approved\":\"true\"}", "{\"number\":\"1\",\"extra\":1}"})
            assertThrows(AttributeValidationException.class, () -> schema.validate(JSON.readTree(value), false), value);
        assertDoesNotThrow(() -> schema.validate(JSON.readTree("{\"number\":\"😀😀😀😀\",\"date\":\"2024-02-29\",\"year\":2020.0,\"amount\":0,\"approved\":false}"), false));
        assertDoesNotThrow(() -> schema.validate(JSON.readTree("{}"), true));
        assertThrows(AttributeValidationException.class, () -> schema.validate(JSON.readTree("{\"number\":null}"), true));
    }
    @Test void rejectsUnknownKeywordsRatherThanSilentlyWeakeningValidation() throws Exception {
        var config = config();
        ((ObjectNode) type(config).path("schema")).put("oneOf", "unsupported");
        assertThrows(ConfigurationException.class, () -> load(config));
    }
    @Test void rejectsBrokenCoreliaReferences() {
        for (String reference : new String[]{"ui"}) {
            var config = config(); var type = type(config);
            switch (reference) {
                case "ui" -> ((ObjectNode) type.path("ui")).putArray("columns").add("missing");
                default -> throw new IllegalStateException(reference);
            }
            assertThrows(ConfigurationException.class, () -> load(config), reference);
        }
    }
    @Test void acceptsOptionalMasksOnlyForConfiguredFields() throws Exception {
        var config = config();
        ((ObjectNode) type(config).path("ui")).putObject("masks").put("number", "000-000");
        assertEquals("000-000", load(config).documentTypes().require("TEST_FORM").ui().path("masks").path("number").asString());
        var invalid = config();
        ((ObjectNode) type(invalid).path("ui")).putObject("masks").put("missing", "000");
        assertThrows(ConfigurationException.class, () -> load(invalid));
    }
    @Test void rejectsVersionMismatchAndMalformedAttachmentPolicy() throws Exception {
        var config = config(); load(config);
        Files.writeString(root.resolve("configuration.json"), "{\"schemaVersion\":1}");
        assertThrows(ConfigurationException.class, () -> new ConfigurationLoader().load(root, "0.1.0"));
        load(config);
        Files.writeString(root.resolve("configuration.json"), "{\"schemaVersion\":2,\"compatibility\":{\"corelia\":\">=0.2.0 <1.0.0\"},\"sources\":{\"entities\":\"data-model/entities\",\"ui\":\"ui\",\"operations\":\"operations\",\"permissions\":\"permissions\"}}");
        assertThrows(ConfigurationException.class, () -> new ConfigurationLoader().load(root, "0.1.0"));
        load(config);
        ((ObjectNode) type(config).path("attachments")).put("enabled", false);
        assertThrows(ConfigurationException.class, () -> load(config));
    }
    @Test void rejectsDuplicateJsonKeys() throws Exception {
        load(config());
        Files.writeString(root.resolve("configuration.json"), "{\"schemaVersion\":2,\"schemaVersion\":2}");
        assertThrows(RuntimeException.class, () -> new ConfigurationLoader().load(root, "0.1.0"));
    }
    @Test void rejectsPathTraversalAndSymlinksOutsidePackage() throws Exception {
        var config = config();
        load(config);
        Files.writeString(root.resolve("configuration.json"), "{\"schemaVersion\":2,\"compatibility\":{\"corelia\":\">=0.1.0 <1.0.0\"},\"sources\":{\"entities\":\"..\",\"ui\":\"ui\",\"operations\":\"operations\",\"permissions\":\"permissions\"}}");
        assertThrows(ConfigurationException.class, () -> new ConfigurationLoader().load(root, "0.1.0"));
    }
}
