package ru.corelia.configuration;

/** Некорректные атрибуты команды; сопоставление с HTTP-статусом выполняет приложение. */
public final class AttributeValidationException extends IllegalArgumentException {
    public AttributeValidationException(String path, String constraint) {
        super(path + ": " + constraint);
    }
}
