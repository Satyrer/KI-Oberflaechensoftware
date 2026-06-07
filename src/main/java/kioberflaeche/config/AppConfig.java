package kioberflaeche.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record AppConfig(
        String aiEndpoint,
        String aiProvider,
        String aiApiKey,
        String aiModel,
        int aiTimeoutSeconds,
        String chatDirectory,
        String n8nAdminBaseUrl,
        String n8nAdminToken,
        String n8nWebBaseUrl,
        String n8nWebEmail,
        String n8nWebPassword,
        int n8nWebTimeoutSeconds,
        String n8nSchreibAiBaseUrl,
        int n8nSchreibAiTimeoutSeconds,
        String n8nChatWebhookUrl,
        String n8nChatWebhookAuthorization
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
                value("ki.provider", "KI_PROVIDER", properties, "ollama"),
                value("ki.apiKey", "KI_API_KEY", properties, ""),
                value("ki.model", "KI_MODEL", properties, DEFAULT_AI_MODEL),
                intValue("ki.timeoutSeconds", "KI_TIMEOUT_SECONDS", properties, 300),
                value("chat.directory", "KI_CHAT_DIRECTORY", properties, DEFAULT_CHAT_DIRECTORY),
                n8nAdminBaseUrl(properties),
                value("n8n.chatAdmin.token", "N8N_CHAT_ADMIN_TOKEN", properties, ""),
                n8nWebBaseUrl(properties),
                value("n8n.web.email", "N8N_WEB_EMAIL", properties, ""),
                value("n8n.web.password", "N8N_WEB_PASSWORD", properties, ""),
                intValue("n8n.web.timeoutSeconds", "N8N_WEB_TIMEOUT_SECONDS", properties, 90),
                n8nSchreibAiBaseUrl(properties),
                intValue("n8n.schreibAi.timeoutSeconds", "N8N_SCHREIB_AI_TIMEOUT_SECONDS", properties, 240),
                value("n8n.chatWebhook.url", "N8N_CHAT_WEBHOOK_URL", properties, ""),
                value("n8n.chatWebhook.authorization", "N8N_CHAT_WEBHOOK_AUTHORIZATION", properties, "")
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

    private static String n8nWebBaseUrl(Properties properties) {
        String baseUrl = value("n8n.web.baseUrl", "N8N_WEB_BASE_URL", properties, "");
        if (!baseUrl.isBlank()) {
            return stripTrailingSlash(baseUrl);
        }

        String host = value("n8n.host", "N8N_HOST", properties, value("ki.host", "KI_HOST", properties, DEFAULT_AI_HOST));
        String port = value("n8n.web.port", "N8N_WEB_PORT", properties, "5678");
        return "http://" + host + ":" + port;
    }

    private static String n8nSchreibAiBaseUrl(Properties properties) {
        String baseUrl = value("n8n.schreibAi.baseUrl", "N8N_SCHREIB_AI_BASE_URL", properties, "");
        if (!baseUrl.isBlank()) {
            return stripTrailingSlash(baseUrl);
        }
        return n8nWebBaseUrl(properties);
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

    private static int intValue(String propertyKey, String envKey, Properties properties, int defaultValue) {
        String rawValue = value(propertyKey, envKey, properties, String.valueOf(defaultValue));
        try {
            return Math.max(1, Integer.parseInt(rawValue));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public String toString() {
        return "AppConfig[aiEndpoint=" + aiEndpoint
                + ", aiProvider=" + aiProvider
                + ", aiApiKey=" + mask(aiApiKey)
                + ", aiModel=" + aiModel
                + ", aiTimeoutSeconds=" + aiTimeoutSeconds
                + ", chatDirectory=" + chatDirectory
                + ", n8nAdminBaseUrl=" + n8nAdminBaseUrl
                + ", n8nAdminToken=" + mask(n8nAdminToken)
                + ", n8nWebBaseUrl=" + n8nWebBaseUrl
                + ", n8nWebEmail=" + mask(n8nWebEmail)
                + ", n8nWebPassword=" + mask(n8nWebPassword)
                + ", n8nWebTimeoutSeconds=" + n8nWebTimeoutSeconds
                + ", n8nSchreibAiBaseUrl=" + n8nSchreibAiBaseUrl
                + ", n8nSchreibAiTimeoutSeconds=" + n8nSchreibAiTimeoutSeconds
                + ", n8nChatWebhookUrl=" + n8nChatWebhookUrl
                + ", n8nChatWebhookAuthorization=" + mask(n8nChatWebhookAuthorization)
                + "]";
    }

    private String mask(String value) {
        return value == null || value.isBlank() ? "" : "***";
    }
}
