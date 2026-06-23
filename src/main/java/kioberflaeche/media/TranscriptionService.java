package kioberflaeche.media;

import kioberflaeche.config.AppConfig;
import kioberflaeche.util.SimpleJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TranscriptionService {
    private final AppConfig config;
    private final HttpClient httpClient;

    public TranscriptionService(AppConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public void transcribe(
            Path input,
            Path output,
            String language,
            String outputMode,
            String speakerName1,
            String speakerName2,
            String narratorVoice3
    ) throws IOException, InterruptedException {
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        if (!config.transcriptionN8nWebhookUrl().isBlank()) {
            transcribeWithWebhook(input, output, language, outputMode, speakerName1, speakerName2, narratorVoice3);
            return;
        }
        transcribeWithCommand(input, output, language);
    }

    private void transcribeWithCommand(Path input, Path output, String language) throws IOException, InterruptedException {
        List<String> command = config.transcriptionCommand().isBlank()
                ? defaultWhisperCommand(input, output, language)
                : CommandLine.parseTemplate(config.transcriptionCommand(), input, output, language);
        ProcessResult result = run(command);
        if (!result.success()) {
            throw new IOException("Transkription wurde mit Code " + result.exitCode() + " beendet:\n" + result.output());
        }
        if (!Files.isRegularFile(output)) {
            Path generated = output.getParent().resolve(stripExtension(input.getFileName().toString()) + ".txt");
            if (Files.isRegularFile(generated)) {
                Files.copy(generated, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.writeString(output, result.output(), StandardCharsets.UTF_8);
            }
        }
    }

    private List<String> defaultWhisperCommand(Path input, Path output, String language) {
        List<String> command = new ArrayList<>();
        command.add("whisper");
        command.add(input.toString());
        command.add("--language");
        command.add(language == null || language.isBlank() ? config.transcriptionDefaultLanguage() : language);
        command.add("--output_format");
        command.add("txt");
        command.add("--output_dir");
        command.add(output.getParent().toString());
        return command;
    }

    private void transcribeWithWebhook(
            Path input,
            Path output,
            String language,
            String outputMode,
            String speakerName1,
            String speakerName2,
            String narratorVoice3
    ) throws IOException, InterruptedException {
        String boundary = "----KI-Oberflaeche-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, input, language, outputMode, speakerName1, speakerName2, narratorVoice3);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.transcriptionN8nWebhookUrl()))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(config.n8nSchreibAiTimeoutSeconds()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "text/plain, application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (!config.transcriptionN8nAuthorization().isBlank()) {
            builder.header("Authorization", config.transcriptionN8nAuthorization());
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("n8n-Transkription meldet HTTP " + response.statusCode() + ":\n" + response.body());
        }
        writeWebhookResponse(output, outputMode, response.body());
    }

    private void writeWebhookResponse(Path output, String outputMode, String responseBody) throws IOException {
        if ("structured".equalsIgnoreCase(outputMode)) {
            Files.writeString(output, responseBody, StandardCharsets.UTF_8);
            writeStructuredTextSidecar(output, responseBody);
            return;
        }

        String text = extractStructuredText(responseBody);
        Files.writeString(output, text.isBlank() ? responseBody : text, StandardCharsets.UTF_8);
    }

    public static Path structuredTextPath(Path output) {
        String fileName = output.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String baseName = dot <= 0 ? fileName : fileName.substring(0, dot);
        return output.getParent().resolve(baseName + ".txt");
    }

    private void writeStructuredTextSidecar(Path output, String jsonBody) throws IOException {
        String text = extractStructuredText(jsonBody);
        if (text.isBlank()) {
            return;
        }
        Files.writeString(structuredTextPath(output), text, StandardCharsets.UTF_8);
    }

    private String extractStructuredText(String jsonBody) {
        try {
            Map<String, Object> root = SimpleJson.asObject(SimpleJson.parse(jsonBody));
            String text = firstPresent(root, "text", "plainText", "transcript");
            return text.strip();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private String firstPresent(Map<String, Object> root, String... keys) {
        for (String key : keys) {
            String value = SimpleJson.asString(root.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private byte[] multipartBody(
            String boundary,
            Path input,
            String language,
            String outputMode,
            String speakerName1,
            String speakerName2,
            String narratorVoice3
    ) throws IOException {
        String fileName = input.getFileName().toString();
        byte[] fileBytes = Files.readAllBytes(input);
        String header = filePartHeader(boundary, fileName);
        String fields = textPart(boundary, "language", language == null || language.isBlank() ? config.transcriptionDefaultLanguage() : language)
                + textPart(boundary, "outputMode", outputMode == null || outputMode.isBlank() ? "plain" : outputMode)
                + textPart(boundary, "speakerName1", speakerName1)
                + textPart(boundary, "speakerName2", speakerName2)
                + textPart(boundary, "narratorVoice3", narratorVoice3);
        String footer = "\r\n--" + boundary + "--\r\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] fieldBytes = fields.getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = footer.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[headerBytes.length + fileBytes.length + fieldBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
        System.arraycopy(fileBytes, 0, body, headerBytes.length, fileBytes.length);
        System.arraycopy(fieldBytes, 0, body, headerBytes.length + fileBytes.length, fieldBytes.length);
        System.arraycopy(footerBytes, 0, body, headerBytes.length + fileBytes.length + fieldBytes.length, footerBytes.length);
        return body;
    }

    private String filePartHeader(String boundary, String fileName) {
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + escapeQuoted(fileName) + "\"; filename*=UTF-8''" + encodedFileName + "\r\n"
                + "Content-Type: " + contentType(fileName) + "\r\n\r\n";
    }

    private String textPart(String boundary, String name, String value) {
        return "\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + (value == null ? "" : value) + "\r\n";
    }

    private String contentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (lower.endsWith(".flac")) {
            return "audio/flac";
        }
        if (lower.endsWith(".ogg")) {
            return "audio/ogg";
        }
        if (lower.endsWith(".opus")) {
            return "audio/opus";
        }
        if (lower.endsWith(".aac")) {
            return "audio/aac";
        }
        return "application/octet-stream";
    }

    private String escapeQuoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private ProcessResult run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new ProcessResult(exitCode, output);
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }
}
