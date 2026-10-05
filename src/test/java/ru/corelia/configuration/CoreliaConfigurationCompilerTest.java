package ru.corelia.configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class CoreliaConfigurationCompilerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path root;

    @Test void compilesV3ProviderNeutralConfigurationArtifacts() throws Exception {
        Path source = packageSource(); Path output = root.resolve("release");
        CoreliaConfigurationCompiler.compile(source, output, "0.1.0");
        assertTrue(Files.isRegularFile(output.resolve("corelia/configuration.json")));
        assertTrue(Files.isRegularFile(output.resolve("corelia/documents/test-form.json")));
        assertTrue(Files.isRegularFile(output.resolve("corelia/workflows/test-form.json")));
        assertEquals("V3 test application", JSON.readTree(Files.readString(output.resolve("corelia/branding/branding.json"))).path("title").asString());
        assertTrue(Files.isRegularFile(output.resolve("corelia/branding/assets/logo.svg")));
        assertFalse(Files.exists(output.resolve("corelia/graphql")));
    }

    private Path packageSource() throws Exception {
        Path source = Files.createDirectories(root.resolve("source"));
        for (String directory : new String[]{"documents", "workflows", "permissions", "bpmn", "branding/assets"}) Files.createDirectories(source.resolve(directory));
        Files.writeString(source.resolve("configuration.json"), """
                {"schemaVersion":3,"compatibility":{"corelia":">=0.1.0 <1.0.0"},
                 "sources":{"documents":"documents","workflows":"workflows","permissions":"permissions"}}
                """);
        Files.writeString(source.resolve("branding/branding.json"), """
                {"title":"V3 test application","theme":{"primaryColor":"#3477d4"},
                 "assets":{"logo":"/logo.svg","favicon":"/favicon.ico"}}
                """);
        Files.writeString(source.resolve("branding/assets/logo.svg"), "<svg />");
        Files.writeString(source.resolve("documents/test-form.json"), """
                {"id":"TEST_FORM","title":"Test","schemaVersion":1,
                 "attributes":{"type":"object","additionalProperties":false,"required":[],"properties":{}},
                 "presentation":{"statuses":{"CREATED":"Created"},"aliases":{},"tones":{},"initialStatus":"CREATED"},
                 "ui":{"createForm":{"fields":[]},"viewCard":{"fields":[]},"editCard":{"fields":[]},"table":{"columns":[]}},
                 "search":{"filterableFields":[],"sortableFields":[],"indexHints":[]},
                 "attachments":{"enabled":false,"initialRequired":false,"maxCount":0}}
                """);
        Files.writeString(source.resolve("workflows/test-form.json"), """
                {"id":"TEST_FORM","processKey":"test_form","bpmnFile":"bpmn/test-form.bpmn","startActions":[]}
                """);
        Files.writeString(source.resolve("permissions/test-form.json"), """
                {"id":"TEST_FORM","permissions":{"create":"create","edit":"edit"},"executorRole":"operator",
                 "editableStatuses":[],"initialUploadStatuses":[]}
                """);
        Files.writeString(source.resolve("bpmn/test-form.bpmn"), "<definitions />");
        return source;
    }
}
