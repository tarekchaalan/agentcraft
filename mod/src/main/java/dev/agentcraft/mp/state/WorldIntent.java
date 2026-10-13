package dev.agentcraft.mp.state;

import java.util.*;
import org.jspecify.annotations.Nullable;

public record WorldIntent(int rev, Map<String, LampStatusWire> lamps, boolean podiumOpen, boolean mergeActive, Set<String> litMonitors) {
    public WorldIntent {
        MpText.cap("lamps",lamps.keySet(),64); MpText.cap("litMonitors",litMonitors,64);
        if(lamps.keySet().stream().anyMatch(key->!isBinding(key))) throw new IllegalArgumentException("invalid binding");
        if(litMonitors.stream().anyMatch(id->!isAgentId(id))) throw new IllegalArgumentException("invalid monitor");
        lamps = Map.copyOf(lamps); litMonitors = Set.copyOf(litMonitors);
    }
    private static final Set<String> FIXED_BINDINGS=Set.of("goal","goal:atrium","decisions","merge","beacon");
    public static boolean isBinding(String key) {
        if(key==null) return false;
        if(FIXED_BINDINGS.contains(key)) return true;
        if(key.length()==5 && key.startsWith("ci:#")) return key.charAt(4)>='1' && key.charAt(4)<='8';
        return key.startsWith("agent:") && isAgentId(key.substring(6));
    }
    private static boolean isAgentId(String id) {
        return id!=null && !id.isEmpty() && id.length()<=16 && id.equals(MpText.sanitize(id,16));
    }
}
