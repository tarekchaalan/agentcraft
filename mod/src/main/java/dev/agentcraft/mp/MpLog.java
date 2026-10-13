package dev.agentcraft.mp;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Structured metadata only: callers must never pass Foreman text or addresses. */
public final class MpLog {
    private static final Logger LOGGER = LoggerFactory.getLogger("agentcraft.mp");
    private static final List<Capture> CAPTURES = new CopyOnWriteArrayList<>();
    private MpLog() {}
    public static void event(String event, Object... fields) {
        String level = MpEvents.CATALOG.get(event);
        if (level == null || fields.length % 2 != 0) throw new IllegalArgumentException("uncatalogued event or unpaired fields");
        StringBuilder line = new StringBuilder("event=").append(event);
        for (int i=0; i<fields.length; i+=2) {
            String key=String.valueOf(fields[i]);
            if (!key.matches("[a-z_]+")) throw new IllegalArgumentException("invalid telemetry key");
            // \s and \p{Cntrl} are ASCII-only here: Cc adds NEL, Zl/Zp the line and paragraph separators, Cf the bidi and zero-width controls.
            line.append(' ').append(key).append('=').append(String.valueOf(fields[i+1]).replaceAll("[\\s\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}§=]", "_"));
        }
        String message=line.toString();
        CAPTURES.forEach(c -> c.lines.add(message));
        switch (level) {
            case "warn" -> LOGGER.warn(message);
            case "error" -> LOGGER.error(message);
            case "debug" -> LOGGER.debug(message);
            default -> LOGGER.info(message);
        }
    }
    public static Capture capture() { Capture c=new Capture(); CAPTURES.add(c); return c; }
    public static final class Capture implements AutoCloseable {
        private final List<String> lines=new CopyOnWriteArrayList<>();
        public List<String> lines() { return List.copyOf(lines); }
        public void close() { CAPTURES.remove(this); }
    }
}
