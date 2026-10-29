package kioberflaeche.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MediaConverter {
    private final String configuredFfmpegPath;
    private final String ffmpegPath;

    public MediaConverter(String ffmpegPath) {
        this.configuredFfmpegPath = ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath.trim();
        this.ffmpegPath = resolveFfmpegPath(this.configuredFfmpegPath);
    }

    public String ffmpegPath() {
        return ffmpegPath;
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

        ProcessResult result;
        try {
            result = run(command);
        } catch (IOException e) {
            throw new IOException(ffmpegUnavailableMessage(), e);
        }
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
            command.add("-ar");
            command.add("16000");
            command.add("-ac");
            command.add("1");
            command.add("-b:a");
            command.add("64k");
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
            command.add("-ar");
            command.add("16000");
            command.add("-ac");
            command.add("1");
            command.add("-b:a");
            command.add("64k");
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

    private String ffmpegUnavailableMessage() {
        return "ffmpeg konnte nicht gestartet werden.\n"
                + "Konfiguriert: " + configuredFfmpegPath + "\n"
                + "Verwendet: " + ffmpegPath + "\n\n"
                + "Bitte ffmpeg installieren oder in config/local.properties setzen, z.B.:\n"
                + "media.ffmpeg.path=C:\\\\Tools\\\\ffmpeg\\\\bin\\\\ffmpeg.exe";
    }

    private static String resolveFfmpegPath(String configuredPath) {
        if (isExplicitPath(configuredPath)) {
            return configuredPath;
        }

        String onPath = findOnPath(executableName(configuredPath));
        if (!onPath.isBlank()) {
            return onPath;
        }

        for (Path candidate : ffmpegCandidates()) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }

        return configuredPath;
    }

    private static boolean isExplicitPath(String value) {
        String text = value == null ? "" : value.trim();
        return text.contains("\\") || text.contains("/") || Path.of(text).isAbsolute();
    }

    private static String executableName(String configuredPath) {
        String name = configuredPath == null || configuredPath.isBlank() ? "ffmpeg" : configuredPath.trim();
        if (isWindows() && !name.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            return name + ".exe";
        }
        return name;
    }

    private static String findOnPath(String executableName) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return "";
        }
        return Arrays.stream(path.split(java.io.File.pathSeparator))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(directory -> Path.of(directory, executableName))
                .filter(Files::isRegularFile)
                .findFirst()
                .map(Path::toString)
                .orElse("");
    }

    private static List<Path> ffmpegCandidates() {
        List<Path> candidates = new ArrayList<>();
        addIfEnvPresent(candidates, "ProgramFiles", "ffmpeg", "bin", "ffmpeg.exe");
        addIfEnvPresent(candidates, "ProgramFiles", "DownloadHelper CoApp", "ffmpeg.exe");
        addIfEnvPresent(candidates, "ProgramFiles", "net.downloadhelper.coapp", "converter", "build", "win", "64", "ffmpeg.exe");
        addIfEnvPresent(candidates, "ProgramFiles(x86)", "ffmpeg", "bin", "ffmpeg.exe");
        addIfEnvPresent(candidates, "ProgramData", "chocolatey", "bin", "ffmpeg.exe");
        addIfEnvPresent(candidates, "USERPROFILE", "scoop", "shims", "ffmpeg.exe");

        candidates.add(Path.of("tools", "ffmpeg", "bin", isWindows() ? "ffmpeg.exe" : "ffmpeg"));
        candidates.add(Path.of("ffmpeg", "bin", isWindows() ? "ffmpeg.exe" : "ffmpeg"));
        return candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
    }

    private static void addIfEnvPresent(List<Path> candidates, String envName, String... parts) {
        String root = System.getenv(envName);
        if (root == null || root.isBlank()) {
            return;
        }
        Path path = Path.of(root, parts);
        candidates.add(path);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static String cleanFormat(String value, String fallback) {
        String format = value == null ? "" : value.trim().toLowerCase();
        if (format.startsWith(".")) {
            format = format.substring(1);
        }
        return format.isBlank() ? fallback : format;
    }
}
