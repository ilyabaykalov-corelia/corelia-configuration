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
        assertFalse(Files.exists(output.resolve("corelia/platform-v-ac.json")));
        assertFalse(Files.exists(output.resolve("platform-v")));
    }

    @Test void rejectsPlatformAuthorizationArtifacts() throws Exception {
        Path source = packageSource();
        Files.writeString(source.resolve("platform-v-ac.json"), "{}");
        assertThrows(ConfigurationException.class, () -> CoreliaConfigurationCompiler.compile(source, root.resolve("release"), "0.1.0"));
    }

    private Path packageSource() throws Exception {
        Path source = Files.createDirectories(root.resolve("source"));
        for (String directory : new String[]{"documents", "workflows", "permissions", "bpmn"}) Files.createDirectories(source.resolve(directory));
        Files.writeString(source.resolve("configuration.json"), """
                {"schemaVersion":3,"compatibility":{"corelia":">=0.1.0 <1.0.0"},
                 "branding":{"applicationName":"V3 test application","logo":"/branding/logo.svg","favicon":"/branding/favicon.ico","theme":{"primaryColor":"#3477d4"}},
                 "sources":{"documents":"documents","workflows":"workflows","permissions":"permissions"}}
                """);
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
