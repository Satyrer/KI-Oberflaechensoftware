package kioberflaeche.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public record AppConfig(
        String aiEndpoint,
        String aiProvider,
        String aiApiKey,
        String aiModel,
        int aiTimeoutSeconds,
        String chatDirectory,
        String n8nWebBaseUrl,
        String n8nWebEmail,
        String n8nWebPassword,
        int n8nWebTimeoutSeconds,
        String n8nSchreibAiBaseUrl,
        int n8nSchreibAiTimeoutSeconds,
        String n8nChatWebhookUrl,
        String n8nChatWebhookAuthorization,
        String ffmpegPath,
        String transcriptionCommand,
        String transcriptionN8nWebhookUrl,
        String transcriptionN8nAuthorization,
        String transcriptionDefaultLanguage
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
                n8nWebBaseUrl(properties),
                value("n8n.web.email", "N8N_WEB_EMAIL", properties, ""),
                value("n8n.web.password", "N8N_WEB_PASSWORD", properties, ""),
                intValue("n8n.web.timeoutSeconds", "N8N_WEB_TIMEOUT_SECONDS", properties, 90),
                n8nSchreibAiBaseUrl(properties),
                intValue("n8n.schreibAi.timeoutSeconds", "N8N_SCHREIB_AI_TIMEOUT_SECONDS", properties, 240),
                value("n8n.chatWebhook.url", "N8N_CHAT_WEBHOOK_URL", properties, ""),
                value("n8n.chatWebhook.authorization", "N8N_CHAT_WEBHOOK_AUTHORIZATION", properties, ""),
                value("media.ffmpeg.path", "MEDIA_FFMPEG_PATH", properties, "ffmpeg"),
                value("transcription.command", "TRANSCRIPTION_COMMAND", properties, ""),
                n8nTranscriptionWebhookUrl(properties),
                value("transcription.n8n.authorization", "TRANSCRIPTION_N8N_AUTHORIZATION", properties, ""),
                value("transcription.defaultLanguage", "TRANSCRIPTION_DEFAULT_LANGUAGE", properties, "de")
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

        for (Path path : localConfigCandidates(configuredPath)) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try (InputStream stream = Files.newInputStream(path)) {
                properties.load(stream);
                return;
            } catch (IOException ignored) {
                // System properties and environment variables can still override defaults.
            }
        }
    }

    private static List<Path> localConfigCandidates(String configuredPath) {
        List<Path> candidates = new ArrayList<>();
        if (configuredPath != null && !configuredPath.isBlank()) {
            candidates.add(Path.of(configuredPath.trim()));
        } else {
            candidates.add(Path.of(LOCAL_CONFIG_PATH));
            candidates.addAll(classLocationConfigCandidates());

            String userHome = System.getProperty("user.home");
            if (userHome != null && !userHome.isBlank()) {
                candidates.add(Path.of(userHome, "IdeaProjects", "KI Oberflaechensoftware", "config", "local.properties"));
            }
        }

        Set<Path> unique = new LinkedHashSet<>();
        for (Path candidate : candidates) {
            unique.add(candidate.toAbsolutePath().normalize());
        }
        return new ArrayList<>(unique);
    }

    private static List<Path> classLocationConfigCandidates() {
        List<Path> candidates = new ArrayList<>();
        try {
            Path location = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(location)) {
                location = location.getParent();
            }
            for (Path current = location; current != null; current = current.getParent()) {
                candidates.add(current.resolve("config").resolve("local.properties"));
            }
        } catch (Exception ignored) {
            // Relative and user-home candidates still cover normal local launches.
        }
        return candidates;
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

    private static String n8nTranscriptionWebhookUrl(Properties properties) {
        String url = value("transcription.n8n.webhookUrl", "TRANSCRIPTION_N8N_WEBHOOK_URL", properties, "");
        if (!url.isBlank()) {
            return url;
        }
        return n8nWebBaseUrl(properties) + "/webhook/schreib-ai/transcribe";
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
                + ", n8nWebBaseUrl=" + n8nWebBaseUrl
                + ", n8nWebEmail=" + mask(n8nWebEmail)
                + ", n8nWebPassword=" + mask(n8nWebPassword)
                + ", n8nWebTimeoutSeconds=" + n8nWebTimeoutSeconds
                + ", n8nSchreibAiBaseUrl=" + n8nSchreibAiBaseUrl
                + ", n8nSchreibAiTimeoutSeconds=" + n8nSchreibAiTimeoutSeconds
                + ", n8nChatWebhookUrl=" + n8nChatWebhookUrl
                + ", n8nChatWebhookAuthorization=" + mask(n8nChatWebhookAuthorization)
                + ", ffmpegPath=" + ffmpegPath
                + ", transcriptionCommand=" + mask(transcriptionCommand)
                + ", transcriptionN8nWebhookUrl=" + transcriptionN8nWebhookUrl
                + ", transcriptionN8nAuthorization=" + mask(transcriptionN8nAuthorization)
                + ", transcriptionDefaultLanguage=" + transcriptionDefaultLanguage
                + "]";
    }

    private String mask(String value) {
        return value == null || value.isBlank() ? "" : "***";
    }
}
