package kioberflaeche.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import kioberflaeche.config.AppConfig;
import kioberflaeche.media.MediaConverter;
import kioberflaeche.media.TranscriptionService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class TranscriptionController {
    private static final Path TRANSCRIPTION_ROOT = Path.of("F:\\Virtuelle Maschinenen\\SchreibAI\\Transkripte");
    private static final Path DEFAULT_SOUND_DIRECTORY = TRANSCRIPTION_ROOT.resolve("Sounddatei");
    private static final Path DEFAULT_VIDEO_DIRECTORY = TRANSCRIPTION_ROOT.resolve("Video");
    private static final Path DEFAULT_TRANSCRIPT_DIRECTORY = TRANSCRIPTION_ROOT.resolve("Transkript");
    private static final String[] AUDIO_EXTENSIONS = {".mp3", ".wav", ".m4a", ".flac", ".ogg", ".opus", ".aac"};
    private static final String[] VIDEO_EXTENSIONS = {".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v"};

    @FXML private TextField audioSourceField;
    @FXML private TextField videoTranscriptSourceField;
    @FXML private TextField transcriptTargetDirectoryField;
    @FXML private TextField transcriptTargetNameField;
    @FXML private ComboBox<String> transcriptLanguageComboBox;
    @FXML private ComboBox<String> transcriptOutputModeComboBox;
    @FXML private TextField speakerName1Field;
    @FXML private TextField speakerName2Field;
    @FXML private TextField narratorVoice3Field;
    @FXML private CheckBox fullTranscriptCheckBox;
    @FXML private Button chooseAudioSourceButton;
    @FXML private Button chooseAudioSourceDirectoryButton;
    @FXML private Button chooseVideoTranscriptSourceButton;
    @FXML private Button chooseTranscriptTargetDirectoryButton;
    @FXML private Button transcribeAudioButton;
    @FXML private Button transcribeVideoButton;
    @FXML private Label transcriptionStatusLabel;
    @FXML private TextArea transcriptionResultArea;

    private AppConfig config;
    private MediaConverter mediaConverter;
    private TranscriptionService transcriptionService;

    private record TranscriptionRequest(
            Path input,
            Path output,
            String language,
            String outputMode,
            String speakerName1,
            String speakerName2,
            String narratorVoice3,
            boolean extractAudioFirst
    ) {
        boolean structured() {
            return "structured".equalsIgnoreCase(outputMode);
        }
    }

    private record BatchTranscriptionRequest(
            List<TranscriptionRequest> requests,
            Path combinedOutput
    ) {
        boolean batch() {
            return requests.size() > 1;
        }
    }

    @FXML
    private void initialize() {
        config = AppConfig.load();
        mediaConverter = new MediaConverter(config.ffmpegPath());
        transcriptionService = new TranscriptionService(config);
        transcriptLanguageComboBox.getItems().setAll(
                "auto", "de", "en", "fr", "es", "it", "pt", "nl", "pl", "tr", "ru", "uk", "ja", "ko", "zh"
        );
        String defaultLanguage = config.transcriptionDefaultLanguage();
        transcriptLanguageComboBox.setValue(transcriptLanguageComboBox.getItems().contains(defaultLanguage) ? defaultLanguage : "de");
        transcriptOutputModeComboBox.getItems().setAll("plain", "structured");
        transcriptOutputModeComboBox.setValue("plain");
        transcriptOutputModeComboBox.valueProperty().addListener((observable, oldValue, newValue) -> updateTargetExtension());
        speakerName1Field.setText("Sprechername1");
        speakerName2Field.setText("Sprechername2");
        narratorVoice3Field.setText("Erzaehlstimme 3");
        fullTranscriptCheckBox.setSelected(true);
        chooseAudioSourceButton.setOnAction(event -> chooseAudioSource());
        chooseAudioSourceDirectoryButton.setOnAction(event -> chooseAudioSourceDirectory());
        chooseVideoTranscriptSourceButton.setOnAction(event -> chooseVideoSource());
        chooseTranscriptTargetDirectoryButton.setOnAction(event -> chooseTargetDirectory());
        transcribeAudioButton.setOnAction(event -> transcribeAudio());
        transcribeVideoButton.setOnAction(event -> transcribeVideo());
        initializeDefaultPaths();
        transcriptionStatusLabel.setText(transcriptionBackendLabel());
    }

    private void chooseAudioSource() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Sounddatei auswaehlen");
        setInitialDirectory(chooser, DEFAULT_SOUND_DIRECTORY);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Audiodateien", "*.mp3", "*.wav", "*.m4a", "*.flac", "*.ogg", "*.opus", "*.aac"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Alle Dateien", "*.*"));
        File selected = chooser.showOpenDialog(ownerWindow());
        if (selected != null) {
            audioSourceField.setText(selected.toPath().toString());
            prepareTargetFrom(selected.toPath());
        }
    }

    private void chooseAudioSourceDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Soundordner auswaehlen");
        File initial = DEFAULT_SOUND_DIRECTORY.toFile();
        if (initial.isDirectory()) {
            chooser.setInitialDirectory(initial);
        }
        File selected = chooser.showDialog(ownerWindow());
        if (selected != null) {
            audioSourceField.setText(selected.toPath().toString());
            prepareTargetFrom(selected.toPath());
        }
    }

    private void chooseVideoSource() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Videodatei fuer Direkt-Transkript auswaehlen");
        setInitialDirectory(chooser, DEFAULT_VIDEO_DIRECTORY);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Videodateien", "*.mp4", "*.mov", "*.mkv", "*.avi", "*.webm", "*.m4v"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Alle Dateien", "*.*"));
        File selected = chooser.showOpenDialog(ownerWindow());
        if (selected != null) {
            videoTranscriptSourceField.setText(selected.toPath().toString());
            prepareTargetFrom(selected.toPath());
        }
    }

    private void chooseTargetDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Zielordner fuer Transkriptdateien auswaehlen");
        File current = currentTargetDirectory();
        if (current.isDirectory()) {
            chooser.setInitialDirectory(current);
        }
        File selected = chooser.showDialog(ownerWindow());
        if (selected != null) {
            transcriptTargetDirectoryField.setText(selected.toPath().toString());
        }
    }

    private void transcribeAudio() {
        String inputText = cleanText(audioSourceField.getText());
        if (inputText.isBlank()) {
            setStatus("Bitte zuerst eine Sounddatei auswaehlen.");
            return;
        }
        try {
            BatchTranscriptionRequest request = audioTranscriptionRequest(Path.of(inputText));
            if (request.requests().isEmpty()) {
                setStatus("Im Quellordner wurden keine Sounddateien gefunden.");
                return;
            }
            runTranscriptions(request);
        } catch (IOException e) {
            transcriptionResultArea.setText(e.getMessage());
            setStatus("Sounddateien konnten nicht gelesen werden.");
        }
    }

    private void transcribeVideo() {
        String inputText = cleanText(videoTranscriptSourceField.getText());
        if (inputText.isBlank()) {
            setStatus("Bitte zuerst eine Videodatei auswaehlen.");
            return;
        }
        TranscriptionRequest request = transcriptionRequest(Path.of(inputText), transcriptTargetPath(Path.of(inputText)), true);
        runTranscription(request);
    }

    private BatchTranscriptionRequest audioTranscriptionRequest(Path source) throws IOException {
        if (!Files.isDirectory(source)) {
            return new BatchTranscriptionRequest(
                    List.of(transcriptionRequest(source, transcriptTargetPath(source), false)),
                    null
            );
        }

        Path targetDirectory = targetDirectoryPath();
        if (targetDirectory == null) {
            return new BatchTranscriptionRequest(List.of(), null);
        }

        List<TranscriptionRequest> requests = new ArrayList<>();
        for (Path audioFile : audioFilesIn(source)) {
            Path output = targetDirectory.resolve(defaultTranscriptFileName(audioFile)).toAbsolutePath().normalize();
            requests.add(transcriptionRequest(audioFile, output, false));
        }

        return new BatchTranscriptionRequest(requests, null);
    }

    private TranscriptionRequest transcriptionRequest(Path input, Path output, boolean extractAudioFirst) {
        String language = cleanText(transcriptLanguageComboBox.getValue());
        String outputMode = cleanText(transcriptOutputModeComboBox.getValue());
        return new TranscriptionRequest(
                input,
                output,
                language.isBlank() ? config.transcriptionDefaultLanguage() : language,
                outputMode.isBlank() ? "plain" : outputMode,
                optionalVoiceName(speakerName1Field.getText(), "Sprechername1"),
                optionalVoiceName(speakerName2Field.getText(), "Sprechername2"),
                optionalVoiceName(narratorVoice3Field.getText(), "Erzaehlstimme 3"),
                extractAudioFirst
        );
    }

    private void runTranscriptions(BatchTranscriptionRequest batch) {
        if (!batch.batch()) {
            runTranscription(batch.requests().get(0));
            return;
        }
        if (batch.requests().stream().anyMatch(request -> request.output() == null)) {
            setStatus("Bitte Zielordner angeben.");
            return;
        }

        setRunning(true, "Erstelle Transkripte fuer " + batch.requests().size() + " Sounddateien...");
        Thread.startVirtualThread(() -> {
            List<String> combinedSections = new ArrayList<>();
            int completed = 0;
            try {
                for (TranscriptionRequest request : batch.requests()) {
                    completed++;
                    int current = completed;
                    Platform.runLater(() -> setStatus("Transkribiere " + current + "/" + batch.requests().size() + ": " + request.input().getFileName()));
                    transcriptionService.transcribe(
                            request.input().toAbsolutePath().normalize(),
                            request.output(),
                            request.language(),
                            request.outputMode(),
                            request.speakerName1(),
                            request.speakerName2(),
                            request.narratorVoice3()
                    );
                    Path previewPath = request.structured() ? TranscriptionService.structuredTextPath(request.output()) : request.output();
                    String transcript = Files.readString(Files.isRegularFile(previewPath) ? previewPath : request.output());
                    combinedSections.add("## " + request.input().getFileName() + System.lineSeparator() + System.lineSeparator() + transcript.strip());
                }

                String combinedTranscript = String.join(System.lineSeparator() + System.lineSeparator(), combinedSections).strip();
                if (batch.combinedOutput() != null) {
                    Files.createDirectories(batch.combinedOutput().getParent());
                    Files.writeString(batch.combinedOutput(), combinedTranscript + System.lineSeparator());
                }
                Platform.runLater(() -> {
                    transcriptionResultArea.setText(combinedTranscript);
                    String message = batch.requests().size() + " Transkripte erstellt.";
                    if (batch.combinedOutput() != null) {
                        message += " Gesamtdatei: " + batch.combinedOutput();
                    }
                    setRunning(false, message);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    transcriptionResultArea.setText(e.getMessage());
                    setRunning(false, "Batch-Transkription fehlgeschlagen.");
                });
            }
        });
    }

    private void runTranscription(TranscriptionRequest request) {
        if (request.output() == null) {
            setStatus("Bitte Zielordner und Dateinamen angeben.");
            return;
        }
        setRunning(true, request.extractAudioFirst() ? "Extrahiere Audio und erstelle Transkript..." : "Erstelle Transkript...");
        Thread.startVirtualThread(() -> {
            Path audioInput = request.input().toAbsolutePath().normalize();
            Path tempAudio = null;
            try {
                if (request.extractAudioFirst()) {
                    tempAudio = mediaConverter.extractAudioToTemp(audioInput, "wav");
                    audioInput = tempAudio;
                }
                transcriptionService.transcribe(
                        audioInput,
                        request.output(),
                        request.language(),
                        request.outputMode(),
                        request.speakerName1(),
                        request.speakerName2(),
                        request.narratorVoice3()
                );
                Path previewPath = request.structured() ? TranscriptionService.structuredTextPath(request.output()) : request.output();
                String transcript = Files.readString(Files.isRegularFile(previewPath) ? previewPath : request.output());
                Platform.runLater(() -> {
                    transcriptionResultArea.setText(transcript);
                    if (request.structured()) {
                        setRunning(false, "JSON und TXT erstellt: " + request.output() + " / " + previewPath);
                    } else {
                        setRunning(false, "TXT-Datei erstellt: " + request.output());
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    transcriptionResultArea.setText(e.getMessage());
                    setRunning(false, "Transkription fehlgeschlagen.");
                });
            } finally {
                if (tempAudio != null) {
                    try {
                        Files.deleteIfExists(tempAudio);
                    } catch (Exception ignored) {
                        // Temporary audio cleanup must not hide the real transcription result.
                    }
                }
            }
        });
    }

    private Path transcriptTargetPath(Path source) {
        Path targetDirectory = targetDirectoryPath();
        String fileName = cleanText(transcriptTargetNameField.getText());
        if (targetDirectory == null || fileName.isBlank()) {
            return null;
        }
        String extension = transcriptExtension();
        if (!fileName.toLowerCase().endsWith(extension)) {
            fileName = stripKnownTranscriptExtension(fileName) + extension;
        }
        return targetDirectory.resolve(fileName).toAbsolutePath().normalize();
    }

    private Path targetDirectoryPath() {
        String directory = cleanText(transcriptTargetDirectoryField.getText());
        return directory.isBlank() ? null : Path.of(directory);
    }

    private void prepareTargetFrom(Path source) {
        transcriptTargetNameField.setText(Files.isDirectory(source) ? "" : defaultTranscriptFileName(source));
        if (transcriptTargetDirectoryField.getText() == null || transcriptTargetDirectoryField.getText().isBlank()) {
            transcriptTargetDirectoryField.setText(defaultTranscriptDirectory(source).toString());
        }
    }

    private Path defaultTranscriptDirectory(Path source) {
        Path parent = source.getParent();
        if (parent == null) {
            return Path.of("Transkript").toAbsolutePath().normalize();
        }
        if (Files.isDirectory(source) && "Sounddatei".equalsIgnoreCase(source.getFileName().toString())) {
            return parent.resolve("Transkript").toAbsolutePath().normalize();
        }
        if (Files.isDirectory(source) && "Video".equalsIgnoreCase(source.getFileName().toString())) {
            return parent.resolve("Transkript").toAbsolutePath().normalize();
        }
        if ("Sounddatei".equalsIgnoreCase(parent.getFileName().toString()) && parent.getParent() != null) {
            return parent.getParent().resolve("Transkript").toAbsolutePath().normalize();
        }
        if ("Video".equalsIgnoreCase(parent.getFileName().toString()) && parent.getParent() != null) {
            return parent.getParent().resolve("Transkript").toAbsolutePath().normalize();
        }
        return parent.toAbsolutePath().normalize();
    }

    private void initializeDefaultPaths() {
        transcriptTargetDirectoryField.setText(DEFAULT_TRANSCRIPT_DIRECTORY.toString());
        Path defaultAudio = firstExistingFile(DEFAULT_SOUND_DIRECTORY, AUDIO_EXTENSIONS);
        if (defaultAudio != null) {
            audioSourceField.setText(defaultAudio.toString());
            prepareTargetFrom(defaultAudio);
        }
    }

    private Path firstExistingFile(Path directory, String[] extensions) {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> hasExtension(path, extensions))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private List<Path> audioFilesIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> hasExtension(path, AUDIO_EXTENSIONS))
                    .sorted()
                    .toList();
        }
    }

    private boolean hasExtension(Path path, String[] extensions) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (fileName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private String transcriptExtension() {
        return "structured".equals(transcriptOutputModeComboBox.getValue()) ? ".json" : ".txt";
    }

    private void updateTargetExtension() {
        String fileName = transcriptTargetNameField.getText();
        Path source = sourcePathFromAudioField();
        if (source != null && Files.isDirectory(source)) {
            transcriptTargetNameField.clear();
            return;
        }
        if (fileName == null || fileName.isBlank()) {
            if (source != null) {
                transcriptTargetNameField.setText(defaultTranscriptFileName(source));
            }
            return;
        }
        transcriptTargetNameField.setText(stripKnownTranscriptExtension(fileName) + modeSuffix() + transcriptExtension());
    }

    private void setInitialDirectory(FileChooser chooser, Path directory) {
        File initial = directory.toFile();
        if (initial.isDirectory()) {
            chooser.setInitialDirectory(initial);
        }
    }

    private File currentTargetDirectory() {
        String configured = cleanText(transcriptTargetDirectoryField.getText());
        if (!configured.isBlank()) {
            return Path.of(configured).toFile();
        }
        return DEFAULT_TRANSCRIPT_DIRECTORY.toFile();
    }

    private String stripKnownTranscriptExtension(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".txt") || lower.endsWith(".json")) {
            fileName = fileName.substring(0, fileName.lastIndexOf('.'));
        }
        return stripModeSuffix(fileName);
    }

    private String transcriptionBackendLabel() {
        if (!config.transcriptionN8nWebhookUrl().isBlank()) {
            return "Bereit ueber n8n-Webhook: " + config.transcriptionN8nWebhookUrl();
        }
        if (!config.transcriptionCommand().isBlank()) {
            return "Bereit ueber lokales Transkriptionskommando.";
        }
        return "Bereit. Standardkommando: whisper aus dem PATH.";
    }

    private String baseName(Path path) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private String defaultTranscriptFileName(Path source) {
        return baseName(source) + modeSuffix() + transcriptExtension();
    }

    private String modeSuffix() {
        return " (" + transcriptMode() + ")";
    }

    private String transcriptMode() {
        String mode = cleanText(transcriptOutputModeComboBox.getValue());
        return mode.isBlank() ? "plain" : mode;
    }

    private String stripModeSuffix(String value) {
        String text = value == null ? "" : value.trim();
        boolean combined = text.toLowerCase(Locale.ROOT).endsWith("-gesamt");
        if (combined) {
            text = text.substring(0, text.length() - "-gesamt".length()).trim();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.endsWith(" (plain)") || lower.endsWith(" (structured)")) {
            text = text.substring(0, text.lastIndexOf(" (")).trim();
        }
        return combined ? text + "-gesamt" : text;
    }

    private Path sourcePathFromAudioField() {
        String sourceText = cleanText(audioSourceField.getText());
        return sourceText.isBlank() ? null : Path.of(sourceText);
    }

    private void setRunning(boolean running, String message) {
        chooseAudioSourceButton.setDisable(running);
        chooseAudioSourceDirectoryButton.setDisable(running);
        chooseVideoTranscriptSourceButton.setDisable(running);
        chooseTranscriptTargetDirectoryButton.setDisable(running);
        transcribeAudioButton.setDisable(running);
        transcribeVideoButton.setDisable(running);
        setStatus(message);
    }

    private void setStatus(String message) {
        transcriptionStatusLabel.setText(message);
    }

    private String cleanText(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    private String optionalVoiceName(String value, String placeholder) {
        String text = cleanText(value);
        return text.equalsIgnoreCase(placeholder) ? "" : text;
    }

    private Window ownerWindow() {
        return audioSourceField.getScene() == null ? null : audioSourceField.getScene().getWindow();
    }
}
