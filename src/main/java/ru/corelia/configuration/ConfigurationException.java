package ru.corelia.configuration;

/** Invalid deployment configuration. Never contains document values or secrets. */
public final class ConfigurationException extends IllegalArgumentException {
    public ConfigurationException(String message) { super(message); }
    public ConfigurationException(String message, Throwable cause) { super(message, cause); }
}
