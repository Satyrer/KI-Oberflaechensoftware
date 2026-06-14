package kioberflaeche.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MediaConverter {
    private final String ffmpegPath;

    public MediaConverter(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath;
    }

    public void extractAudio(Path input, Path output, String audioFormat) throws IOException, InterruptedException {
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-y");
        command.add("-i");
        command.add(input.toString());
        command.add("-vn");
        addAudioOptions(command, audioFormat);
        command.add(output.toString());

        ProcessResult result = run(command);
        if (!result.success()) {
            throw new IOException("ffmpeg wurde mit Code " + result.exitCode() + " beendet:\n" + result.output());
        }
    }

    public Path extractAudioToTemp(Path input, String audioFormat) throws IOException, InterruptedException {
        String suffix = "." + cleanFormat(audioFormat, "mp3");
        Path tempFile = Files.createTempFile("kioberflaeche-audio-", suffix);
        extractAudio(input, tempFile, audioFormat);
        return tempFile;
    }

    private void addAudioOptions(List<String> command, String audioFormat) {
        String format = cleanFormat(audioFormat, "mp3");
        if ("mp3".equals(format)) {
            command.add("-codec:a");
            command.add("libmp3lame");
            command.add("-q:a");
            command.add("2");
        } else if ("wav".equals(format)) {
            command.add("-acodec");
            command.add("pcm_s16le");
            command.add("-ar");
            command.add("16000");
            command.add("-ac");
            command.add("1");
        } else if ("m4a".equals(format) || "aac".equals(format)) {
            command.add("-codec:a");
            command.add("aac");
            command.add("-b:a");
            command.add("192k");
        } else if ("flac".equals(format)) {
            command.add("-codec:a");
            command.add("flac");
        } else if ("ogg".equals(format) || "opus".equals(format)) {
            command.add("-codec:a");
            command.add("libopus");
        }
    }

    private ProcessResult run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new ProcessResult(exitCode, output);
    }

    public static String cleanFormat(String value, String fallback) {
        String format = value == null ? "" : value.trim().toLowerCase();
        if (format.startsWith(".")) {
            format = format.substring(1);
        }
        return format.isBlank() ? fallback : format;
    }
}
