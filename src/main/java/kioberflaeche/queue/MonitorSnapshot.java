package kioberflaeche.queue;

import kioberflaeche.util.SimpleJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Read-only, bounded projection of the shared worker monitor contract. */
public record MonitorSnapshot(String sourceId, String scope, Instant generatedAt, List<Row> rows) {
    public record Row(String id, String parentId, String area, String title, String status, String phase,
                      String project, String series, String queueScope, Integer position,
                      Long completed, Long total, String unit, Instant created, Instant updated, long failedSteps) {}

    public static MonitorSnapshot read(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
            if (bytes.length > 2 * 1024 * 1024) throw new IOException("Statusdatei ist zu gross");
            return parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException error) { throw new IOException("Statusdatei hat ein ungueltiges Format", error); }
    }
    public static MonitorSnapshot parse(String json) {
        var root = SimpleJson.asObject(SimpleJson.parse(json));
        if (!"schreibai.work-monitor.v1".equals(root.get("schemaVersion"))) throw new IllegalArgumentException("schemaVersion");
        var source = SimpleJson.asObject(root.get("source"));
        if (!Boolean.TRUE.equals(source.get("readOnly"))) throw new IllegalArgumentException("readOnly");
        String sourceId = required(source, "id");
        Instant generatedAt = instant(root.get("generatedAt"));
        Object raw = root.get("records");
        if (!(raw instanceof List<?> records) || records.size() > 5000) throw new IllegalArgumentException("records");
        List<Row> rows = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object item : records) {
            var row = SimpleJson.asObject(item);
            String id = required(row, "id");
            if (!ids.add(id)) throw new IllegalArgumentException("duplicate id");
            var progress = SimpleJson.asObject(row.get("progress"));
            Long done = number(progress.get("completed")), total = number(progress.get("total"));
            if (done != null && (total == null || done < 0 || total < done)) throw new IllegalArgumentException("progress");
            Long position = number(row.get("queuePosition"));
            if (position != null && (position < 1 || position > Integer.MAX_VALUE)) throw new IllegalArgumentException("position");
            Long failed = number(row.get("failedSteps"));
            rows.add(new Row(id, text(row, "parentId"), text(row, "area"), required(row, "title"),
                    required(row, "status"), text(row, "phase"), text(row, "project"), text(row, "series"),
                    text(row, "queueScope"), position == null ? null : position.intValue(), done, total,
                    text(progress, "unit"), instant(row.get("createdAt")), instant(row.get("updatedAt")), failed == null ? 0 : failed));
        }
        return new MonitorSnapshot(sourceId, text(source, "scope"), generatedAt, List.copyOf(rows));
    }
    private static Long number(Object value) {
        if (value == null) return null;
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.longValue())
            throw new IllegalArgumentException("number");
        return n.longValue();
    }
    private static Instant instant(Object value) {
        if (value instanceof Number n) return Instant.ofEpochMilli((long) (n.doubleValue() * 1000));
        return Instant.parse(Objects.toString(value, ""));
    }
    private static String text(Map<String,Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return "";
        if (!(value instanceof String s) || s.length() > 4000) throw new IllegalArgumentException(key);
        return s;
    }
    private static String required(Map<String,Object> map, String key) {
        String value = text(map, key);
        if (value.isBlank()) throw new IllegalArgumentException(key);
        return value;
    }
    public boolean stale(Instant now) {
        return generatedAt.isBefore(now.minusSeconds(120)) || generatedAt.isAfter(now.plusSeconds(30));
    }
}
