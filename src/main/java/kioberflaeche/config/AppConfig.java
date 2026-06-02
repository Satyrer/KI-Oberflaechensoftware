package kioberflaeche.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public record AppConfig(String aiEndpoint, String aiApiKey, String aiModel, String chatDirectory) {
    private static final String DEFAULT_AI_ENDPOINT = "http://localhost:8000/chat";
    private static final String DEFAULT_CHAT_DIRECTORY = "chats";

    public static AppConfig load() {
        Properties properties = new Properties();
        try (InputStream stream = AppConfig.class.getResourceAsStream("/application.properties")) {
            if (stream != null) {
                properties.load(stream);
            }
        } catch (IOException ignored) {
            // Defaults and environment variables keep the app usable without a config file.
        }

        return new AppConfig(
                value("ki.endpoint", "KI_ENDPOINT", properties, DEFAULT_AI_ENDPOINT),
                value("ki.apiKey", "KI_API_KEY", properties, ""),
                value("ki.model", "KI_MODEL", properties, ""),
                value("chat.directory", "KI_CHAT_DIRECTORY", properties, DEFAULT_CHAT_DIRECTORY)
        );
    }

    private static String value(String propertyKey, String envKey, Properties properties, String defaultValue) {
        String systemValue = System.getProperty(propertyKey);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue.trim();
        }

        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String fileValue = properties.getProperty(propertyKey);
        if (fileValue != null && !fileValue.isBlank()) {
            return fileValue.trim();
        }

        return defaultValue;
    }
}
