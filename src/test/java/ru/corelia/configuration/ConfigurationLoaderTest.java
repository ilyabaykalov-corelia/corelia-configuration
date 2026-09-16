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
        Files.writeString(root.resolve("configuration.json"), JSON.writeValueAsString(config));
        Files.writeString(root.resolve("read.graphql"), "query readDocument { documents { id } }");
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
    @Test void rejectsBrokenReferencesAndDuplicateTypes() {
        for (String reference : new String[]{"ui", "operation", "mapping", "reserved", "workflow", "duplicate"}) {
            var config = config(); var type = type(config);
            switch (reference) {
                case "ui" -> ((ObjectNode) type.path("ui")).putArray("columns").add("missing");
                case "operation" -> ((ObjectNode) type.path("storage").path("operations")).put("get", "missing");
                case "mapping" -> ((ObjectNode) type.path("storage").path("fields")).remove("number");
                case "reserved" -> ((ObjectNode) type.path("storage").path("fields")).put("number", "status");
                case "workflow" -> ((ObjectNode) type.path("workflow").path("actions")).put("SEND", "missing");
                case "duplicate" -> ((tools.jackson.databind.node.ArrayNode) config.path("documentTypes")).add(type.deepCopy());
            }
            assertThrows(ConfigurationException.class, () -> load(config), reference);
        }
    }
    @Test void rejectsVersionMismatchAndMalformedAttachmentPolicy() {
        var config = config(); config.put("schemaVersion", 2);
        assertThrows(ConfigurationException.class, () -> load(config));
        config.put("schemaVersion", 1);
        ((ObjectNode) config.path("compatibility")).put("corelia", ">=0.2.0 <1.0.0");
        assertThrows(ConfigurationException.class, () -> load(config));
        ((ObjectNode) config.path("compatibility")).put("corelia", ">=0.1.0 <1.0.0");
        ((ObjectNode) type(config).path("attachments")).put("enabled", false);
        assertThrows(ConfigurationException.class, () -> load(config));
    }
    @Test void rejectsDuplicateJsonKeysAndWrongOperationNames() throws Exception {
        load(config());
        Files.writeString(root.resolve("configuration.json"), "{\"schemaVersion\":1,\"schemaVersion\":2}");
        assertThrows(RuntimeException.class, () -> new ConfigurationLoader().load(root, "0.1.0"));
        load(config());
        Files.writeString(root.resolve("read.graphql"), "query unexpected { documents { id } }");
        assertThrows(ConfigurationException.class, () -> new ConfigurationLoader().load(root, "0.1.0"));
    }
    @Test void rejectsPathTraversalAndSymlinksOutsidePackage() throws Exception {
        var config = config();
        ((ObjectNode) config.path("operations").path("readDocument")).put("file", "../external.graphql");
        assertThrows(ConfigurationException.class, () -> load(config));
        var outside = Files.createTempFile(root.getParent(), "outside-", ".graphql");
        try {
            Files.writeString(outside, "query readDocument { documents { id } }");
            Files.createSymbolicLink(root.resolve("link.graphql"), outside);
            ((ObjectNode) config.path("operations").path("readDocument")).put("file", "link.graphql");
            assertThrows(ConfigurationException.class, () -> load(config));
        } finally { Files.deleteIfExists(outside); }
    }
}
