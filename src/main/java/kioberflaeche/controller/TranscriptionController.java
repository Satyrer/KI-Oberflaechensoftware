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
    @FXML private Button chooseVideoTranscriptSourceButton;
    @FXML private Button chooseTranscriptTargetDirectoryButton;
    @FXML private Button transcribeAudioButton;
    @FXML private Button transcribeVideoButton;
    @FXML private Label transcriptionStatusLabel;
    @FXML private TextArea transcriptionResultArea;

    private AppConfig config;
    private MediaConverter mediaConverter;
    private TranscriptionService transcriptionService;

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
        chooser.setTitle("Zielordner fuer TXT auswaehlen");
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
        Path input = Path.of(audioSourceField.getText() == null ? "" : audioSourceField.getText().trim());
        if (input.toString().isBlank()) {
            setStatus("Bitte zuerst eine Sounddatei auswaehlen.");
            return;
        }
        Path output = transcriptTargetPath(input);
        runTranscription(input, output, false);
    }

    private void transcribeVideo() {
        Path input = Path.of(videoTranscriptSourceField.getText() == null ? "" : videoTranscriptSourceField.getText().trim());
        if (input.toString().isBlank()) {
            setStatus("Bitte zuerst eine Videodatei auswaehlen.");
            return;
        }
        Path output = transcriptTargetPath(input);
        runTranscription(input, output, true);
    }

    private void runTranscription(Path input, Path output, boolean extractAudioFirst) {
        if (output == null) {
            setStatus("Bitte Zielordner und Dateinamen angeben.");
            return;
        }
        setRunning(true, extractAudioFirst ? "Extrahiere Audio und erstelle Transkript..." : "Erstelle Transkript...");
        Thread.startVirtualThread(() -> {
            Path audioInput = input.toAbsolutePath().normalize();
            Path tempAudio = null;
            try {
                if (extractAudioFirst) {
                    tempAudio = mediaConverter.extractAudioToTemp(audioInput, "wav");
                    audioInput = tempAudio;
                }
                transcriptionService.transcribe(
                        audioInput,
                        output,
                        transcriptLanguageComboBox.getValue(),
                        transcriptOutputModeComboBox.getValue(),
                        speakerName1Field.getText(),
                        speakerName2Field.getText(),
                        narratorVoice3Field.getText()
                );
                String transcript = Files.readString(output);
                Platform.runLater(() -> {
                    transcriptionResultArea.setText(transcript);
                    setRunning(false, "TXT-Datei erstellt: " + output);
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
        String directory = transcriptTargetDirectoryField.getText() == null ? "" : transcriptTargetDirectoryField.getText().trim();
        String fileName = transcriptTargetNameField.getText() == null ? "" : transcriptTargetNameField.getText().trim();
        if (directory.isBlank() || fileName.isBlank()) {
            return null;
        }
        String extension = transcriptExtension();
        if (!fileName.toLowerCase().endsWith(extension)) {
            fileName = stripKnownTranscriptExtension(fileName) + extension;
        }
        return Path.of(directory).resolve(fileName).toAbsolutePath().normalize();
    }

    private void prepareTargetFrom(Path source) {
        String extension = transcriptExtension();
        transcriptTargetNameField.setText(baseName(source) + extension);
        if (transcriptTargetDirectoryField.getText() == null || transcriptTargetDirectoryField.getText().isBlank()) {
            transcriptTargetDirectoryField.setText(defaultTranscriptDirectory(source).toString());
        }
    }

    private Path defaultTranscriptDirectory(Path source) {
        Path parent = source.getParent();
        if (parent == null) {
            return Path.of("Transkript").toAbsolutePath().normalize();
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
        if (fileName == null || fileName.isBlank()) {
            String sourceText = audioSourceField.getText() == null ? "" : audioSourceField.getText().trim();
            Path source = !sourceText.isBlank()
                    ? Path.of(sourceText)
                    : null;
            if (source != null) {
                transcriptTargetNameField.setText(baseName(source) + transcriptExtension());
            }
            return;
        }
        transcriptTargetNameField.setText(stripKnownTranscriptExtension(fileName) + transcriptExtension());
    }

    private void setInitialDirectory(FileChooser chooser, Path directory) {
        File initial = directory.toFile();
        if (initial.isDirectory()) {
            chooser.setInitialDirectory(initial);
        }
    }

    private File currentTargetDirectory() {
        String configured = transcriptTargetDirectoryField.getText() == null ? "" : transcriptTargetDirectoryField.getText().trim();
        if (!configured.isBlank()) {
            return Path.of(configured).toFile();
        }
        return DEFAULT_TRANSCRIPT_DIRECTORY.toFile();
    }

    private String stripKnownTranscriptExtension(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".txt") || lower.endsWith(".json")) {
            return fileName.substring(0, fileName.lastIndexOf('.'));
        }
        return fileName;
    }

    private String transcriptionBackendLabel() {
        if (!config.transcriptionN8nWebhookUrl().isBlank()) {
            return "Bereit ueber n8n-Webhook.";
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

    private void setRunning(boolean running, String message) {
        chooseAudioSourceButton.setDisable(running);
        chooseVideoTranscriptSourceButton.setDisable(running);
        chooseTranscriptTargetDirectoryButton.setDisable(running);
        transcribeAudioButton.setDisable(running);
        transcribeVideoButton.setDisable(running);
        setStatus(message);
    }

    private void setStatus(String message) {
        transcriptionStatusLabel.setText(message);
    }

    private Window ownerWindow() {
        return audioSourceField.getScene() == null ? null : audioSourceField.getScene().getWindow();
    }
}
