package kioberflaeche.media;

import kioberflaeche.config.AppConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
                .timeout(Duration.ofMinutes(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (!config.transcriptionN8nAuthorization().isBlank()) {
            builder.header("Authorization", config.transcriptionN8nAuthorization());
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("n8n-Transkription meldet HTTP " + response.statusCode() + ":\n" + response.body());
        }
        Files.writeString(output, response.body(), StandardCharsets.UTF_8);
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
        String header = textPart(boundary, "language", language == null || language.isBlank() ? config.transcriptionDefaultLanguage() : language)
                + textPart(boundary, "outputMode", outputMode == null || outputMode.isBlank() ? "plain" : outputMode)
                + textPart(boundary, "speakerName1", speakerName1)
                + textPart(boundary, "speakerName2", speakerName2)
                + textPart(boundary, "narratorVoice3", narratorVoice3)
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = footer.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
        System.arraycopy(fileBytes, 0, body, headerBytes.length, fileBytes.length);
        System.arraycopy(footerBytes, 0, body, headerBytes.length + fileBytes.length, footerBytes.length);
        return body;
    }

    private String textPart(String boundary, String name, String value) {
        return "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + (value == null ? "" : value) + "\r\n";
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
