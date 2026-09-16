package ru.corelia.configuration;

import java.util.*;

public final class DocumentTypeRegistry {
    private final Map<String, DocumentTypeDefinition> types;
    public DocumentTypeRegistry(Collection<DocumentTypeDefinition> definitions) {
        var result = new LinkedHashMap<String, DocumentTypeDefinition>();
        for (var definition : definitions) if (result.putIfAbsent(definition.id(), definition) != null)
            throw new ConfigurationException("Duplicate document type: " + definition.id());
        if (result.isEmpty()) throw new ConfigurationException("documentTypes must not be empty");
        types = Collections.unmodifiableMap(result);
    }
    public DocumentTypeDefinition require(String id) {
        var result = types.get(id);
        if (result == null) throw new ConfigurationException("Unknown document type: " + id);
        return result;
    }
    public List<DocumentTypeDefinition> all() { return List.copyOf(types.values()); }
}
