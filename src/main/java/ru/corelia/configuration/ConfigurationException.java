package ru.corelia.configuration;

/** Некорректная deployment-конфигурация; исключение не содержит значений документа или секретов. */
public final class ConfigurationException extends IllegalArgumentException {
    public ConfigurationException(String message) { super(message); }
    public ConfigurationException(String message, Throwable cause) { super(message, cause); }
}
