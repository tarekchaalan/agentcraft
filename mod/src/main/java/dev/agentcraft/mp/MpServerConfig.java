package dev.agentcraft.mp;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

public record MpServerConfig(boolean enabled, int plotStride, boolean autoAllocate, boolean autoBuild,
        boolean forceCreative, boolean worldRules, boolean protectPlots, int relayRadiusChunks,
        int publicStatePerSecond, int intentsPerSecond) {
    public static final MpServerConfig DEFAULT = new MpServerConfig(false,128,true,true,true,true,true,12,4,10);
    private static volatile MpServerConfig current = DEFAULT;
    public static MpServerConfig current() { return current; }
    /** Scoped test/config override; restore the returned value in finally. */
    public static MpServerConfig install(MpServerConfig config) {
        MpServerConfig previous=current; current=Objects.requireNonNull(config); return previous;
    }
    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            current = server.isDedicatedServer() ? load(FabricLoader.getInstance().getConfigDir().resolve("agentcraft-server.json")) : DEFAULT;
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> current=DEFAULT);
    }
    public static MpServerConfig load(Path file) {
        JsonObject root = new JsonObject();
        if (Files.exists(file)) {
            try { root=JsonParser.parseString(Files.readString(file)).getAsJsonObject(); }
            catch (Exception e) { invalid("file", "malformed"); }
        }
        Set<String> known=Set.of("enabled","plotStride","autoAllocate","autoBuild","forceCreative","worldRules","protectPlots","relayRadiusChunks","publicStatePerSecond","intentsPerSecond");
        for (String key:root.keySet()) if (!known.contains(key)) invalid("unknown", "unknown");
        MpServerConfig config=new MpServerConfig(bool(root,"enabled",false), number(root,"plotStride",128,96,1048576,true),
                bool(root,"autoAllocate",true),bool(root,"autoBuild",true),bool(root,"forceCreative",true),
                bool(root,"worldRules",true),bool(root,"protectPlots",true), number(root,"relayRadiusChunks",12,0,1024,false),
                number(root,"publicStatePerSecond",4,1,1000,false),number(root,"intentsPerSecond",10,1,1000,false));
        MpLog.event(MpEvents.CONFIG_LOADED,"enabled",config.enabled(),"file",Files.exists(file)?"config/agentcraft-server.json":"none");
        return config;
    }
    private static void invalid(String key, String value) { MpLog.event(MpEvents.CONFIG_INVALID,"key",key,"value",value); }
    private static boolean bool(JsonObject o,String key,boolean def) {
        if (!o.has(key)) return def;
        JsonElement e=o.get(key);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
        invalid(key,"invalid_type"); return def;
    }
    private static int number(JsonObject o,String key,int def,int min,int max,boolean aligned) {
        if (!o.has(key)) return def;
        try {
            JsonElement e=o.get(key);
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
            int n=e.getAsBigDecimal().intValueExact();
            if (n<min || n>max || (aligned && n%16!=0)) throw new IllegalArgumentException();
            return n;
        } catch (RuntimeException e) { invalid(key,"invalid_number"); return def; }
    }
}
