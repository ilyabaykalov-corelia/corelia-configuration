package ru.corelia.configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Собирает проверенный provider-neutral customer configuration package. */
public final class CoreliaConfigurationCompiler {
    private static final JsonMapper JSON = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private CoreliaConfigurationCompiler() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 3) throw new IllegalArgumentException("Usage: CoreliaConfigurationCompiler SOURCE OUTPUT CORELIA_VERSION");
        compile(Path.of(args[0]), Path.of(args[1]), args[2]);
    }

    public static void compile(Path source, Path output, String version) throws IOException {
        source = source.toRealPath();
        Path parent = output.toAbsolutePath().normalize().getParent().toRealPath();
        output = parent.resolve(output.getFileName());
        if (Files.exists(output)) throw new ConfigurationException("Output must not exist; compile into a new release directory");
        if (output.startsWith(source)) throw new ConfigurationException("Output must be outside the source package");
        new ConfigurationLoader().load(source, version);
        validateBranding(source);
        JsonNode manifest = JSON.readTree(Files.readString(source.resolve("configuration.json")));
        Path staging = Files.createTempDirectory(parent, ".corelia-config-");
        try {
            Path runtime = Files.createDirectories(staging.resolve("corelia"));
            copyFile(source, runtime, Path.of("configuration.json"));
            for (var entry : manifest.path("sources").properties())
                copyTree(source, runtime, Path.of(entry.getValue().asString()));
            for (String resource : new String[]{"bpmn", "branding"}) copyTreeIfPresent(source, runtime, Path.of(resource));
            var release = JSON.createObjectNode().put("schemaVersion", 1).put("coreliaVersion", version);
            release.put("configurationSha256", sha256(Files.readString(runtime.resolve("configuration.json"))));
            Files.writeString(staging.resolve("manifest.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(release));
            new ConfigurationLoader().load(runtime, version);
            Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(staging)) try (var paths = Files.walk(staging)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void copyTreeIfPresent(Path source, Path target, Path relative) throws IOException {
        if (Files.exists(source.resolve(relative))) copyTree(source, target, relative);
    }

    private static void copyTree(Path source, Path target, Path relative) throws IOException {
        Path input = source.resolve(relative).normalize();
        if (!input.startsWith(source) || !Files.isDirectory(input)) return;
        try (var paths = Files.walk(input)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) copyFile(source, target, source.relativize(file));
        }
    }

    private static void copyFile(Path source, Path target, Path relative) throws IOException {
        Path input = source.resolve(relative).normalize();
        if (!input.startsWith(source) || !Files.isRegularFile(input) || !input.toRealPath().startsWith(source))
            throw new ConfigurationException("Configuration resource escapes package: " + relative);
        Path output = target.resolve(relative).normalize();
        if (!output.startsWith(target)) throw new ConfigurationException("Configuration output escapes package: " + relative);
        Files.createDirectories(output.getParent());
        Files.copy(input, output, StandardCopyOption.COPY_ATTRIBUTES);
    }

    /** Проверяет самостоятельный runtime-контракт branding из customer package. */
    private static void validateBranding(Path source) throws IOException {
        Path file = source.resolve("branding/branding.json");
        if (!Files.exists(file)) return;
        JsonNode branding = JSON.readTree(Files.readString(file));
        if (!branding.isObject()) throw new ConfigurationException("Invalid branding");
        AttributeSchema.keywords(branding, Set.of("title", "theme", "assets"), "branding");
        DocumentTypeDefinition.requiredText(branding, "title");
        if (branding.has("theme")) {
            JsonNode theme = branding.path("theme");
            if (!theme.isObject()) throw new ConfigurationException("Invalid branding theme");
            for (var entry : theme.properties())
                if (!entry.getValue().isTextual() || entry.getValue().asString().isBlank())
                    throw new ConfigurationException("Invalid branding theme token: " + entry.getKey());
        }
        if (branding.has("assets")) {
            JsonNode assets = branding.path("assets");
            if (!assets.isObject()) throw new ConfigurationException("Invalid branding assets");
            AttributeSchema.keywords(assets, Set.of("logo", "favicon"), "branding assets");
            for (String key : new String[]{"logo", "favicon"}) if (assets.has(key)) DocumentTypeDefinition.requiredText(assets, key);
        }
    }

    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
