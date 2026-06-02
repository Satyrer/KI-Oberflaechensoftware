package kioberflaeche.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record AppConfig(
        String aiEndpoint,
        String aiApiKey,
        String aiModel,
        String chatDirectory,
        String n8nAdminBaseUrl,
        String n8nAdminToken
) {
    private static final String LOCAL_CONFIG_PATH = "config/local.properties";
    private static final String DEFAULT_AI_HOST = "localhost";
    private static final String DEFAULT_AI_PORT = "11435";
    private static final String DEFAULT_AI_PATH = "/api/chat";
    private static final String DEFAULT_AI_MODEL = "mistral-rag:latest";
    private static final String DEFAULT_CHAT_DIRECTORY = "chats";

    public static AppConfig load() {
        Properties properties = new Properties();
        loadClasspathProperties(properties);
        loadLocalProperties(properties);

        String endpoint = value("ki.endpoint", "KI_ENDPOINT", properties, "");
        if (endpoint.isBlank()) {
            endpoint = endpointFromParts(properties);
        }

        return new AppConfig(
                endpoint,
                value("ki.apiKey", "KI_API_KEY", properties, ""),
                value("ki.model", "KI_MODEL", properties, DEFAULT_AI_MODEL),
                value("chat.directory", "KI_CHAT_DIRECTORY", properties, DEFAULT_CHAT_DIRECTORY),
                n8nAdminBaseUrl(properties),
                value("n8n.chatAdmin.token", "N8N_CHAT_ADMIN_TOKEN", properties, "")
        );
    }

    private static void loadClasspathProperties(Properties properties) {
        try (InputStream stream = AppConfig.class.getResourceAsStream("/application.properties")) {
            if (stream != null) {
                properties.load(stream);
            }
        } catch (IOException ignored) {
            // Defaults keep the app usable without a bundled config file.
        }
    }

    private static void loadLocalProperties(Properties properties) {
        String configuredPath = System.getProperty("ki.config");
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = System.getenv("KI_CONFIG");
        }
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = LOCAL_CONFIG_PATH;
        }

        Path path = Path.of(configuredPath);
        if (!Files.isRegularFile(path)) {
            return;
        }

        try (InputStream stream = Files.newInputStream(path)) {
            properties.load(stream);
        } catch (IOException ignored) {
            // System properties and environment variables can still override defaults.
        }
    }

    private static String endpointFromParts(Properties properties) {
        String host = value("ki.host", "KI_HOST", properties, DEFAULT_AI_HOST);
        String port = value("ki.port", "KI_PORT", properties, DEFAULT_AI_PORT);
        String path = value("ki.path", "KI_PATH", properties, DEFAULT_AI_PATH);
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return "http://" + host + ":" + port + path;
    }

    private static String n8nAdminBaseUrl(Properties properties) {
        String baseUrl = value("n8n.chatAdmin.baseUrl", "N8N_CHAT_ADMIN_BASE_URL", properties, "");
        if (!baseUrl.isBlank()) {
            return stripTrailingSlash(baseUrl);
        }

        String host = value("n8n.host", "N8N_HOST", properties, value("ki.host", "KI_HOST", properties, DEFAULT_AI_HOST));
        String port = value("n8n.chatAdmin.port", "N8N_CHAT_ADMIN_PORT", properties, "8088");
        return "http://" + host + ":" + port;
    }

    private static String stripTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
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
