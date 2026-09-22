package ru.corelia.configuration;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Strict scalar-object JSON Schema profile. Unsupported keywords fail at load time. */
public final class AttributeSchema {
    private static final Set<String> ROOT = Set.of("$schema", "type", "properties", "required", "additionalProperties", "title", "description");
    private static final Set<String> FIELD = Set.of("type", "title", "description", "minLength", "maxLength", "pattern", "format", "minimum", "maximum", "enum");
    private final JsonNode schema;
    private final Set<String> required;
    private final Map<String, Pattern> patterns;

    public AttributeSchema(JsonNode input) {
        if (input == null || !input.isObject()) throw new ConfigurationException("schema must be an object");
        schema = input.deepCopy();
        keywords(schema, ROOT, "schema");
        if (!"object".equals(schema.path("type").asString()) || !schema.path("properties").isObject()
                || !schema.path("additionalProperties").isBoolean() || schema.path("additionalProperties").asBoolean())
            throw new ConfigurationException("schema requires type object, properties and additionalProperties: false");
        if (schema.has("$schema") && !"https://json-schema.org/draft/2020-12/schema".equals(schema.path("$schema").asString()))
            throw new ConfigurationException("Unsupported JSON Schema dialect");
        required = new LinkedHashSet<>();
        if (schema.has("required")) {
            if (!schema.path("required").isArray()) throw new ConfigurationException("schema.required must be an array");
            for (JsonNode field : schema.path("required")) {
                if (!field.isTextual() || !schema.path("properties").has(field.asString()) || !required.add(field.asString()))
                    throw new ConfigurationException("Invalid or duplicate required field");
            }
        }
        patterns = new HashMap<>();
        for (var entry : schema.path("properties").properties()) {
            String name = entry.getKey(); JsonNode field = entry.getValue();
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || !field.isObject()) throw new ConfigurationException("Invalid field: " + name);
            keywords(field, FIELD, name);
            String type = field.path("type").asString();
            if (!Set.of("string", "integer", "number", "boolean").contains(type)) throw new ConfigurationException("Unsupported type: " + name);
            for (String k : List.of("minLength", "maxLength")) {
                if (field.has(k) && (!type.equals("string") || !field.path(k).isIntegralNumber() || !field.path(k).canConvertToInt() || field.path(k).asInt() < 0))
                    throw new ConfigurationException("Invalid " + k + ": " + name);
            }
            for (String k : List.of("minimum", "maximum")) {
                if (field.has(k) && (!(type.equals("integer") || type.equals("number")) || !field.path(k).isNumber()))
                    throw new ConfigurationException("Invalid " + k + ": " + name);
            }
            if (field.has("minLength") && field.has("maxLength") && field.path("minLength").asInt() > field.path("maxLength").asInt())
                throw new ConfigurationException("Contradictory lengths: " + name);
            if (field.has("minimum") && field.has("maximum") && decimal(field.path("minimum")).compareTo(decimal(field.path("maximum"))) > 0)
                throw new ConfigurationException("Contradictory bounds: " + name);
            if (field.has("format") && (!type.equals("string") || !field.path("format").isTextual() || !"date".equals(field.path("format").asString())))
                throw new ConfigurationException("Unsupported format: " + name);
            if (field.has("pattern")) {
                if (!type.equals("string") || !field.path("pattern").isTextual()) throw new ConfigurationException("Invalid pattern: " + name);
                try { patterns.put(name, Pattern.compile(field.path("pattern").asString())); }
                catch (PatternSyntaxException e) { throw new ConfigurationException("Invalid pattern: " + name, e); }
            }
            if (field.has("enum")) {
                if (!field.path("enum").isArray() || field.path("enum").isEmpty()) throw new ConfigurationException("Invalid enum: " + name);
                var seen = new HashSet<JsonNode>();
                for (JsonNode value : field.path("enum")) {
                    if (!matchesType(type, value) || !seen.add(value)) throw new ConfigurationException("Invalid enum member: " + name);
                }
            }
        }
    }

    public JsonNode definition() { return schema.deepCopy(); }
    public List<String> fields() { return List.copyOf(schema.path("properties").propertyNames()); }

    /** A partial command checks supplied values; application validates the merged snapshot too. */
    public JsonNode validate(JsonNode attributes, boolean partial) {
        if (attributes == null || !attributes.isObject()) throw new AttributeValidationException("attributes", "must be an object");
        for (String field : attributes.propertyNames()) if (!schema.path("properties").has(field)) fail(field, "unknown attribute");
        for (String name : fields()) {
            JsonNode value = attributes.path(name), field = schema.path("properties").path(name);
            if (value.isMissingNode()) {
                if (!partial && required.contains(name)) fail(name, "required");
                continue;
            }
            if (!matchesType(field.path("type").asString(), value)) fail(name, "invalid type");
            if (value.isTextual()) {
                String text = value.asString(); int length = text.codePointCount(0, text.length());
                if (field.has("minLength") && length < field.path("minLength").asInt()) fail(name, "minLength");
                if (field.has("maxLength") && length > field.path("maxLength").asInt()) fail(name, "maxLength");
                if (patterns.containsKey(name) && !patterns.get(name).matcher(text).find()) fail(name, "pattern");
                if (field.has("format")) {
                    try { if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) fail(name, "date"); LocalDate.parse(text); }
                    catch (DateTimeParseException e) { fail(name, "date"); }
                }
            }
            if (value.isNumber()) {
                if (field.has("minimum") && decimal(value).compareTo(decimal(field.path("minimum"))) < 0) fail(name, "minimum");
                if (field.has("maximum") && decimal(value).compareTo(decimal(field.path("maximum"))) > 0) fail(name, "maximum");
            }
            if (field.has("enum")) {
                boolean found = false;
                for (JsonNode candidate : field.path("enum")) if (candidate.equals(value) || candidate.isNumber() && value.isNumber() && decimal(candidate).compareTo(decimal(value)) == 0) found = true;
                if (!found) fail(name, "enum");
            }
        }
        return attributes.deepCopy();
    }
    private static boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isNumber() && decimal(value).stripTrailingZeros().scale() <= 0;
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            default -> false;
        };
    }
    private static BigDecimal decimal(JsonNode value) { return new BigDecimal(value.asString()); }
    private static void fail(String name, String constraint) { throw new AttributeValidationException("attributes." + name, constraint); }
    public static void keywords(JsonNode node, Set<String> allowed, String path) {
        for (String key : node.propertyNames()) if (!allowed.contains(key)) throw new ConfigurationException("Unsupported keyword " + path + "." + key);
    }
}
