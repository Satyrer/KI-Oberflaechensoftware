package kioberflaeche.ai;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/** Separate local preferences: no secrets, no edits to story sources or chat content. */
public final class ModelPreferences {
    private ModelPreferences() {}
    private static Path file() { return Path.of(System.getProperty("ki.modelPreferences","config/model-preferences.properties")).toAbsolutePath().normalize(); }
    private static Properties read(Path file) throws IOException {
        Properties values=new Properties();
        if(Files.exists(file)) try(var input=Files.newBufferedReader(file,StandardCharsets.UTF_8)) { values.load(input); }
        return values;
    }
    public static synchronized String get(String key,String fallback) throws IOException { return read(file()).getProperty(key,fallback); }
    public static synchronized void put(String key,String value) throws IOException {
        Path target=file(); Properties values=read(target); values.setProperty(key,value);
        Files.createDirectories(target.getParent()); Path temporary=Files.createTempFile(target.getParent(),"model-preferences-",".tmp");
        try {
            try(var output=Files.newBufferedWriter(temporary,StandardCharsets.UTF_8)) { values.store(output,"Lokale Modellauswahl; keine Zugangsdaten"); }
            try { Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException unsupported) { Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
