package dev.agentcraft.mp.net;

import dev.agentcraft.mp.*;
import dev.agentcraft.mp.server.StudioRange;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class MpPayloads {
    private static boolean registered;
    private static final Set<UUID> EQUIPPED=ConcurrentHashMap.newKeySet();
    private MpPayloads() {}
    public static synchronized void register() {
        if(registered) return;
        PayloadTypeRegistry.clientboundPlay().register(HelloS2C.TYPE,HelloS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HelloC2S.TYPE,HelloC2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LayoutS2C.TYPE,LayoutS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LayoutRemoveS2C.TYPE,LayoutRemoveS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PublicStateC2S.TYPE,PublicStateC2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StudioStateS2C.TYPE,StudioStateS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StudioEventC2S.TYPE,StudioEventC2S.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StudioEventS2C.TYPE,StudioEventS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PresenceS2C.TYPE,PresenceS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(WorldIntentC2S.TYPE,WorldIntentC2S.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->server.execute(()-> {
            UUID player=handler.getPlayer().getUUID();
            helloFor(player,MpServerConfig.current(),server.isDedicatedServer(),ServerPlayNetworking.canSend(handler.getPlayer(),HelloS2C.TYPE)).ifPresent(hello-> {
                ServerPlayNetworking.send(handler.getPlayer(),hello); logSent(hello);
            });
        }));
        ServerPlayNetworking.registerGlobalReceiver(HelloC2S.TYPE,(hello,context)->context.server().execute(()-> {
            if(acceptHello(context.player().getUUID(),hello,MpServerConfig.current(),context.server().isDedicatedServer()))
                StudioRange.refresh(context.server());
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->EQUIPPED.remove(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server->EQUIPPED.clear());
        registered=true;
    }
    public static Optional<HelloS2C> helloFor(UUID player,MpServerConfig config,boolean dedicated,boolean canSend) {
        if(!dedicated || !config.enabled() || !canSend) return Optional.empty();
        StudioId id=StudioId.of(player); int plot=Plots.directory().plotOf(id).map(Plot::index).orElse(-1);
        return Optional.of(new HelloS2C(MpProtocol.VERSION,id,plot,new ServerInfo(config.plotStride(),config.relayRadiusChunks(),config.publicStatePerSecond(),config.intentsPerSecond())));
    }
    public static void logSent(HelloS2C hello) {
        MpLog.event(MpEvents.HELLO_SENT,"player",hello.you().owner(),"studio",hello.you().owner(),"plot",hello.plotIndex(),"protocol",hello.protocol());
    }
    public static boolean acceptHello(UUID player,HelloC2S hello,MpServerConfig config,boolean dedicated) {
        if(!dedicated || !config.enabled() || hello.protocol()!=MpProtocol.VERSION) return false;
        if(EQUIPPED.add(player)) MpLog.event(MpEvents.HELLO_RECEIVED,"player",player,"studio",player,"protocol",hello.protocol(),"mode","MULTIPLAYER");
        return true;
    }
    public static boolean isModEquipped(UUID player) { return EQUIPPED.contains(player); }
}
