package kioberflaeche.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import kioberflaeche.config.AppConfig;
import kioberflaeche.media.MediaConverter;

import java.io.File;
import java.nio.file.Path;

public class MediaConversionController {
    @FXML private TextField videoSourceField;
    @FXML private TextField audioTargetDirectoryField;
    @FXML private TextField audioTargetNameField;
    @FXML private ComboBox<String> videoInputFormatComboBox;
    @FXML private ComboBox<String> audioOutputFormatComboBox;
    @FXML private Button chooseVideoSourceButton;
    @FXML private Button chooseAudioTargetDirectoryButton;
    @FXML private Button convertVideoToAudioButton;
    @FXML private Label mediaConversionStatusLabel;
    @FXML private TextArea mediaConversionLogArea;

    private AppConfig config;
    private MediaConverter converter;

    @FXML
    private void initialize() {
        config = AppConfig.load();
        converter = new MediaConverter(config.ffmpegPath());
        videoInputFormatComboBox.setItems(FXCollections.observableArrayList("mp4", "mov", "mkv", "avi", "webm", "m4v"));
        videoInputFormatComboBox.setEditable(true);
        videoInputFormatComboBox.getSelectionModel().select("mp4");
        audioOutputFormatComboBox.setItems(FXCollections.observableArrayList("mp3", "wav", "m4a", "flac", "ogg", "opus"));
        audioOutputFormatComboBox.setEditable(true);
        audioOutputFormatComboBox.getSelectionModel().select("mp3");
        chooseVideoSourceButton.setOnAction(event -> chooseVideoSource());
        chooseAudioTargetDirectoryButton.setOnAction(event -> chooseTargetDirectory());
        convertVideoToAudioButton.setOnAction(event -> convertVideoToAudio());
        mediaConversionStatusLabel.setText("Bereit. ffmpeg: " + config.ffmpegPath());
    }

    private void chooseVideoSource() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Videodatei auswaehlen");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Videodateien", "*.mp4", "*.mov", "*.mkv", "*.avi", "*.webm", "*.m4v"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Alle Dateien", "*.*"));
        File selected = chooser.showOpenDialog(ownerWindow());
        if (selected == null) {
            return;
        }
        videoSourceField.setText(selected.toPath().toString());
        audioTargetNameField.setText(baseName(selected.toPath()) + "." + selectedOutputFormat());
        if (audioTargetDirectoryField.getText() == null || audioTargetDirectoryField.getText().isBlank()) {
            File parent = selected.getParentFile();
            if (parent != null) {
                audioTargetDirectoryField.setText(parent.toPath().toString());
            }
        }
    }

    private void chooseTargetDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Zielordner auswaehlen");
        File selected = chooser.showDialog(ownerWindow());
        if (selected != null) {
            audioTargetDirectoryField.setText(selected.toPath().toString());
        }
    }

    private void convertVideoToAudio() {
        Path input = Path.of(videoSourceField.getText() == null ? "" : videoSourceField.getText().trim());
        Path outputDirectory = Path.of(audioTargetDirectoryField.getText() == null ? "" : audioTargetDirectoryField.getText().trim());
        String outputName = audioTargetNameField.getText() == null ? "" : audioTargetNameField.getText().trim();
        if (input.toString().isBlank() || outputDirectory.toString().isBlank() || outputName.isBlank()) {
            setStatus("Bitte Videodatei, Zielordner und Ausgabedatei angeben.", false);
            return;
        }
        Path output = outputDirectory.resolve(ensureExtension(outputName, selectedOutputFormat())).toAbsolutePath().normalize();
        setRunning(true, "Konvertiere Video zu Audio...");
        Thread.startVirtualThread(() -> {
            try {
                converter.extractAudio(input.toAbsolutePath().normalize(), output, selectedOutputFormat());
                Platform.runLater(() -> {
                    mediaConversionLogArea.setText("Erstellt:\n" + output);
                    setRunning(false, "Audio-Datei erstellt.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    mediaConversionLogArea.setText(e.getMessage());
                    setRunning(false, "Konvertierung fehlgeschlagen.");
                });
            }
        });
    }

    private String selectedOutputFormat() {
        return MediaConverter.cleanFormat(audioOutputFormatComboBox.getEditor().getText(), "mp3");
    }

    private String ensureExtension(String fileName, String format) {
        String extension = "." + MediaConverter.cleanFormat(format, "mp3");
        return fileName.toLowerCase().endsWith(extension) ? fileName : fileName + extension;
    }

    private String baseName(Path path) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private void setRunning(boolean running, String message) {
        convertVideoToAudioButton.setDisable(running);
        chooseVideoSourceButton.setDisable(running);
        chooseAudioTargetDirectoryButton.setDisable(running);
        setStatus(message, !running);
    }

    private void setStatus(String message, boolean ready) {
        mediaConversionStatusLabel.setText(message);
        if (!ready) {
            mediaConversionLogArea.appendText("");
        }
    }

    private Window ownerWindow() {
        return videoSourceField.getScene() == null ? null : videoSourceField.getScene().getWindow();
    }
}
