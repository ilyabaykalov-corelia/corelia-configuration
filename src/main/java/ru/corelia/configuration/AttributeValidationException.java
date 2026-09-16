package ru.corelia.configuration;

/** Invalid command attributes; HTTP status mapping belongs to the application. */
public final class AttributeValidationException extends IllegalArgumentException {
    public AttributeValidationException(String path, String constraint) {
        super(path + ": " + constraint);
    }
}
