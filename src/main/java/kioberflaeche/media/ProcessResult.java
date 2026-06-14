package kioberflaeche.media;

record ProcessResult(int exitCode, String output) {
    boolean success() {
        return exitCode == 0;
    }
}
