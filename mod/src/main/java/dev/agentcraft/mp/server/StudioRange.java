package dev.agentcraft.mp.server;

import dev.agentcraft.mp.*;
import dev.agentcraft.mp.net.MpPayloads;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Shared layout/state visibility. Server shell and its queries run on the server thread. */
public final class StudioRange {
    private static final Map<UUID,List<Plot>> VISIBLE=new LinkedHashMap<>();
    private static final List<Listener> LISTENERS=new ArrayList<>();
    private static MinecraftServer activeServer;
    private static boolean initialized;
    private StudioRange() {}

    /** Horizontal chunk distance to the inclusive site footprint used by Plot.contains. */
    public static int chunkDistance(Plot plot,int chunkX,int chunkZ) {
        long minX=Math.floorDiv((long)plot.origin().getX()-46,16), maxX=Math.floorDiv((long)plot.origin().getX()+46,16);
        long minZ=Math.floorDiv((long)plot.origin().getZ()-36,16), maxZ=Math.floorDiv((long)plot.origin().getZ()+54,16);
        long dx=Math.max(0,Math.max(minX-chunkX,chunkX-maxX));
        long dz=Math.max(0,Math.max(minZ-chunkZ,chunkZ-maxZ));
        return (int)Math.min(Integer.MAX_VALUE,Math.max(dx,dz));
    }
    public static List<Plot> visiblePlots(Collection<Plot> plots,StudioId own,boolean overworld,int chunkX,int chunkZ,int radius) {
        if(radius<0) throw new IllegalArgumentException("negative radius");
        return plots.stream().filter(p->p.owner().equals(own) || (overworld && chunkDistance(p,chunkX,chunkZ)<=radius)).toList();
    }
    public record Change(List<Plot> left,List<Plot> entered) {
        public Change { left=List.copyOf(left); entered=List.copyOf(entered); }
    }
    public static Change diff(Collection<Plot> previous,Collection<Plot> next) {
        Set<Plot> before=new HashSet<>(previous), after=new HashSet<>(next);
        return new Change(previous.stream().filter(p->!after.contains(p)).toList(),next.stream().filter(p->!before.contains(p)).toList());
    }
    public interface Listener {
        void entered(ServerPlayer viewer,Plot plot);
        void left(ServerPlayer viewer,Plot plot);
    }
    public static void addListener(Listener listener) { LISTENERS.add(Objects.requireNonNull(listener)); }
    public static void init() {
        if(initialized) return;
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(enabled(server) && server.getTickCount()%20==0) refresh(server);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->
            server.execute(()->VISIBLE.remove(handler.getPlayer().getUUID())));
        // Feature listeners are registered once at mod init and survive server restarts.
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{ VISIBLE.clear(); activeServer=null; });
        initialized=true;
    }
    private static boolean enabled(MinecraftServer server) {
        return server.isDedicatedServer() && MpServerConfig.current().enabled();
    }
    public static void refresh(MinecraftServer server) {
        if(!enabled(server)) return;
        if(activeServer!=server) { VISIBLE.clear(); activeServer=server; }
        Collection<Plot> plots=Plots.directory().all();
        Set<UUID> online=new HashSet<>();
        for(ServerPlayer viewer:server.getPlayerList().getPlayers()) {
            UUID id=viewer.getUUID();
            if(!MpPayloads.isModEquipped(id)) continue;
            online.add(id);
            var chunk=viewer.chunkPosition();
            List<Plot> next=visiblePlots(plots,StudioId.of(id),viewer.level().dimension().equals(Level.OVERWORLD),
                chunk.x(),chunk.z(),MpServerConfig.current().relayRadiusChunks());
            Change change=diff(VISIBLE.getOrDefault(id,List.of()),next);
            VISIBLE.put(id,next);
            for(Plot plot:change.left()) for(Listener listener:LISTENERS) listener.left(viewer,plot);
            for(Plot plot:change.entered()) for(Listener listener:LISTENERS) listener.entered(viewer,plot);
        }
        VISIBLE.keySet().retainAll(online);
    }
    public static List<ServerPlayer> viewersOf(MinecraftServer server,StudioId studio) {
        if(!enabled(server) || activeServer!=server) return List.of();
        return server.getPlayerList().getPlayers().stream()
            .filter(p->MpPayloads.isModEquipped(p.getUUID()) && sees(p.getUUID(),studio)).toList();
    }
    public static boolean sees(UUID viewer,StudioId studio) {
        return activeServer!=null && enabled(activeServer) && MpPayloads.isModEquipped(viewer) && VISIBLE.getOrDefault(viewer,List.of()).stream().anyMatch(p->p.owner().equals(studio));
    }
}
