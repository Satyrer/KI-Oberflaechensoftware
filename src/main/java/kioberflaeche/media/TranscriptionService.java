package kioberflaeche.media;

import kioberflaeche.config.AppConfig;
import kioberflaeche.util.SimpleJson;
import kioberflaeche.ai.ModelRouting;
import kioberflaeche.ai.RequestBudgets;

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
import java.util.Locale;
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

    public record TranscriptionResult(String selectedModel, String actualModel, double elapsedSeconds, String backendTiming, String structuringNotice) {
        public String summary() {
            String model = actualModel.isBlank()
                    ? "Modell gewaehlt: Whisper " + selectedModel + " (Server bestaetigt das Modell nicht)"
                    : "Modell verwendet: Whisper " + actualModel;
            return model + String.format(Locale.ROOT, "; Gesamtdauer: %.1f s", elapsedSeconds) + backendTiming + structuringNotice;
        }
    }

    public void transcribe(
            Path input, Path output, String language, String outputMode,
            String speakerName1, String speakerName2, String narratorVoice3
    ) throws IOException, InterruptedException {
        transcribe(input, output, language, "small", outputMode, speakerName1, speakerName2, narratorVoice3);
    }

    public TranscriptionResult transcribe(
            Path input, Path output, String language, String asrModel, String outputMode,
            String speakerName1, String speakerName2, String narratorVoice3
    ) throws IOException, InterruptedException {
        return transcribe(input,output,language,asrModel,outputMode,speakerName1,speakerName2,narratorVoice3,"auto");
    }

    public TranscriptionResult transcribe(Path input, Path output, String language, String asrModel, String outputMode,
            String speakerName1, String speakerName2, String narratorVoice3, String modelSelection) throws IOException, InterruptedException {
        if("structured".equalsIgnoreCase(outputMode) && config.transcriptionN8nWebhookUrl().isBlank())
            throw new IOException("Die strukturierte Ausgabe mit Modellwahl benoetigt den eingerichteten Server-Workflow.");
        String model = normalizeAsrModel(asrModel);
        long started = System.nanoTime();
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        AsrMetadata metadata = new AsrMetadata("", "", "");
        if (!config.transcriptionN8nWebhookUrl().isBlank()) {
            metadata = transcribeWithWebhook(input, output, language, model, outputMode, speakerName1, speakerName2, narratorVoice3, modelSelection);
        } else {
            transcribeWithCommand(input, output, language, model);
        }
        return new TranscriptionResult(model, metadata.model(), (System.nanoTime() - started) / 1_000_000_000.0, metadata.timing(), metadata.structuringNotice());
    }

    private String normalizeAsrModel(String value) throws IOException {
        String model = value == null || value.isBlank() ? "small" : value.trim().toLowerCase(Locale.ROOT);
        if (!List.of("small", "large-v3").contains(model)) {
            throw new IOException("Unbekanntes Spracherkennungsmodell: " + model);
        }
        return model;
    }

    private void transcribeWithCommand(Path input, Path output, String language, String asrModel) throws IOException, InterruptedException {
        String template = config.transcriptionCommand();
        if (!template.isBlank() && !template.contains("{model}")) {
            throw new IOException("Das lokale Transkriptionskommando muss {model} enthalten, damit die Modellauswahl wirksam ist.");
        }
        List<String> command = template.isBlank()
                ? defaultWhisperCommand(input, output, language, asrModel)
                : CommandLine.parseTemplate(template.replace("{model}", asrModel), input, output, language);
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

    private List<String> defaultWhisperCommand(Path input, Path output, String language, String asrModel) {
        List<String> command = new ArrayList<>();
        command.add("whisper");
        command.add(input.toString());
        command.add("--model");
        command.add(asrModel);
        command.add("--device");
        command.add("cpu");
        command.add("--fp16");
        command.add("False");
        String selectedLanguage = language == null || language.isBlank() ? config.transcriptionDefaultLanguage() : language;
        if (!"auto".equalsIgnoreCase(selectedLanguage)) {
            command.add("--language");
            command.add(selectedLanguage);
        }
        command.add("--output_format");
        command.add("txt");
        command.add("--output_dir");
        command.add(output.getParent().toString());
        return command;
    }

    private AsrMetadata transcribeWithWebhook(
            Path input,
            Path output,
            String language,
            String asrModel,
            String outputMode,
            String speakerName1,
            String speakerName2,
            String narratorVoice3,
            String modelSelection
    ) throws IOException, InterruptedException {
        String boundary = "----KI-Oberflaeche-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, input, language, asrModel, outputMode, speakerName1, speakerName2, narratorVoice3, modelSelection);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.transcriptionN8nWebhookUrl()))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(transcriptionTimeout(outputMode))
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
        ValidatedResponse validated = validateResponse(response, outputMode);
        Map<String, Object> root = validated.root();
        Map<String, Object> asr = SimpleJson.asObject(root.get("asr"));
        String actual = metadataValue(asr, response, "model", "X-ASR-Model").strip();
        if (!actual.isBlank() && !asrModel.equals(actual)) {
            throw new IOException("Transkription meldet ein anderes Modell als ausgewaehlt. Die vorhandene Ergebnisdatei wurde nicht veraendert.");
        }
        if("structured".equalsIgnoreCase(outputMode)) {
            var ai=SimpleJson.asObject(root.get("ai"));
            if(!Boolean.TRUE.equals(ai.get("skipped")) && !Boolean.FALSE.equals(ai.get("ok")) && !ai.containsKey("error")) ModelRouting.verify(root,modelSelection);
        }
        writeWebhookResponse(output, outputMode, response.body(), validated.transcript());
        String timing = timingPart("Modell laden", metadataValue(asr, response, "modelLoadSeconds", "X-ASR-Model-Load-Seconds"))
                + timingPart("Spracherkennung", metadataValue(asr, response, "transcriptionSeconds", "X-ASR-Transcription-Seconds"));
        return new AsrMetadata(actual, timing, structuringNotice(root, outputMode)+("structured".equalsIgnoreCase(outputMode)?"; "+ModelRouting.summary(root):""));
    }

    private record AsrMetadata(String model, String timing, String structuringNotice) {}

    private String structuringNotice(Map<String, Object> root, String outputMode) {
        if (!"structured".equalsIgnoreCase(outputMode)) return "";
        Map<String, Object> ai = SimpleJson.asObject(root.get("ai"));
        Map<String, Object> fidelity = SimpleJson.asObject(ai.get("fidelity"));
        String reason = SimpleJson.asString(ai.get("reason"));
        String preserved = "; vollstaendiges Originaltranskript beibehalten";
        if (Boolean.TRUE.equals(ai.get("skipped"))) {
            return "; Hinweis: Strukturierung ausgelassen"
                    + ("transcript_exceeds_structure_limit".equals(reason) ? " (Transkript ueberschreitet die aktuelle Strukturgrenze)" : "")
                    + preserved;
        }
        if (Boolean.FALSE.equals(ai.get("ok")) || ai.containsKey("error")) {
            return "; Hinweis: Strukturierung fehlgeschlagen" + preserved;
        }
        if (Boolean.FALSE.equals(fidelity.get("accepted"))) {
            String detail = switch (SimpleJson.asString(fidelity.get("reason"))) {
                case "speaker_turns_changed_or_incomplete" -> " (Wortlaut geaendert oder unvollstaendig)";
                case "no_valid_speaker_turns" -> " (keine gueltige Sprecherstruktur)";
                default -> "";
            };
            return "; Hinweis: Strukturierung nicht uebernommen" + detail + preserved;
        }
        return "";
    }

    private String metadataValue(Map<String, Object> asr, HttpResponse<String> response, String key, String header) {
        String value = SimpleJson.asString(asr.get(key));
        return value.isBlank() ? response.headers().firstValue(header).orElse("") : value;
    }

    private String timingPart(String label, String value) {
        try {
            double seconds = Double.parseDouble(value);
            return Double.isFinite(seconds) && seconds >= 0
                    ? String.format(Locale.ROOT, "; %s: %.1f s", label, seconds) : "";
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private Duration transcriptionTimeout(String outputMode) {
        return RequestBudgets.transcription(config.n8nSchreibAiTimeoutSeconds(),outputMode);
    }

    private record ValidatedResponse(Map<String, Object> root, String transcript) {}

    private ValidatedResponse validateResponse(HttpResponse<String> response, String outputMode) throws IOException {
        String body = response.body();
        if (body == null || body.isBlank()) {
            throw new IOException("Die Transkriptionsantwort ist leer. Es wurde keine Ergebnisdatei geschrieben.");
        }
        boolean structured = "structured".equalsIgnoreCase(outputMode);
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        boolean jsonResponse = contentType.contains("application/json") || contentType.contains("+json");
        boolean confirmedPlain = !structured && contentType.startsWith("text/plain")
                && response.headers().firstValue("X-ASR-Model").filter(value -> !value.isBlank()).isPresent();
        if (confirmedPlain) {
            // Spoken JSON is transcript content when the ASR endpoint confirms a plain response.
            return new ValidatedResponse(Map.of(), body);
        }
        Object parsed;
        try {
            parsed = SimpleJson.parse(body);
        } catch (RuntimeException error) {
            if (structured || jsonResponse) {
                throw new IOException("Die Transkriptionsantwort enthaelt kein gueltiges JSON-Ergebnis. Vorhandene Dateien bleiben unveraendert.");
            }
            // Older backends can return a plain transcript without model metadata.
            return new ValidatedResponse(Map.of(), body);
        }
        if (!(parsed instanceof Map<?, ?>)) {
            if (structured || jsonResponse) {
                throw new IOException("Die Transkriptionsantwort enthaelt kein Transkriptobjekt. Vorhandene Dateien bleiben unveraendert.");
            }
            return new ValidatedResponse(Map.of(), body);
        }
        Map<String, Object> root = SimpleJson.asObject(parsed);
        if (Boolean.FALSE.equals(root.get("ok")) || root.get("error") != null) {
            throw new IOException("Der Transkriptionsdienst meldet einen Fehler statt eines gueltigen Ergebnisses. Vorhandene Dateien bleiben unveraendert.");
        }
        String transcript = null;
        for (String field : List.of("text", "plainText", "transcript")) {
            if (root.get(field) instanceof String value) {
                if (transcript == null) transcript = value;
                if (!value.isBlank()) {
                    transcript = value;
                    break;
                }
            }
        }
        if (transcript == null || (transcript.isBlank() && !Boolean.TRUE.equals(root.get("ok")))) {
            throw new IOException("Die Serverantwort enthaelt kein bestaetigtes Transkript. Vorhandene Dateien bleiben unveraendert.");
        }
        return new ValidatedResponse(root, transcript);
    }

    private void writeWebhookResponse(Path output, String outputMode, String responseBody, String transcript) throws IOException {
        if ("structured".equalsIgnoreCase(outputMode)) {
            Files.writeString(output, responseBody, StandardCharsets.UTF_8);
            Files.writeString(structuredTextPath(output), transcript, StandardCharsets.UTF_8);
        } else {
            Files.writeString(output, transcript, StandardCharsets.UTF_8);
        }
    }

    public static Path structuredTextPath(Path output) {
        String fileName = output.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String baseName = dot <= 0 ? fileName : fileName.substring(0, dot);
        return output.getParent().resolve(baseName + ".txt");
    }

    private byte[] multipartBody(
            String boundary,
            Path input,
            String language,
            String asrModel,
            String outputMode,
            String speakerName1,
            String speakerName2,
            String narratorVoice3,
            String modelSelection
    ) throws IOException {
        String fileName = input.getFileName().toString();
        byte[] fileBytes = Files.readAllBytes(input);
        String header = filePartHeader(boundary, fileName);
        String fields = textPart(boundary, "language", language == null || language.isBlank() ? config.transcriptionDefaultLanguage() : language)
                + textPart(boundary, "asrModel", asrModel)
                + ("structured".equalsIgnoreCase(outputMode)?textPart(boundary,"modelSelection",ModelRouting.normalize(modelSelection)):"")
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
