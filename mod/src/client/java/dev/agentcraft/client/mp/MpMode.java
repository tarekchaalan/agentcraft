package dev.agentcraft.client.mp;

import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.*;
import dev.agentcraft.mp.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.loader.api.FabricLoader;

public enum MpMode {
    SINGLEPLAYER, MULTIPLAYER, REMOTE_VANILLA;
    private static MpMode current=SINGLEPLAYER;
    private static final List<Consumer<MpMode>> LISTENERS=new ArrayList<>();
    private static int helloTicks;
    private static String serverHash="none";
    private static ServerInfo acceptedInfo;
    private static boolean rejectedHello;
    private static final byte[] HASH_SALT=new byte[32];
    static { new java.security.SecureRandom().nextBytes(HASH_SALT); }
    public static Optional<ServerInfo> serverInfo() { return current==MULTIPLAYER?Optional.ofNullable(acceptedInfo):Optional.empty(); }
    public static MpMode current() { return current; }
    public static void addListener(Consumer<MpMode> listener) { LISTENERS.add(listener); }
    public static String hashServer(String address) {
        try { var digest=MessageDigest.getInstance("SHA-256"); digest.update(HASH_SALT); return HexFormat.of().formatHex(digest.digest(address.getBytes(StandardCharsets.UTF_8))).substring(0,16); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void change(MpMode mode) {
        if(mode==current) return;
        MpMode before=current; current=mode;
        if(Anchors.self().equals(StudioId.LOCAL))
            MpLog.event(MpEvents.MODE_CHANGED,"from",before,"to",mode,"server",serverHash);
        else
            MpLog.event(MpEvents.MODE_CHANGED,"from",before,"to",mode,"server",serverHash,"player",Anchors.self().owner(),"studio",Anchors.self().owner());
        LISTENERS.forEach(l->l.accept(mode));
    }
    public static void joined(boolean integrated,String hash) {
        disconnected(); serverHash=hash; helloTicks=integrated?0:100;
    }
    public static void tick() { if(helloTicks>0 && --helloTicks==0 && current!=MULTIPLAYER) change(REMOTE_VANILLA); }
    public static void disconnected() {
        helloTicks=0; acceptedInfo=null; rejectedHello=false; change(SINGLEPLAYER);
        for(StudioId id:List.copyOf(Anchors.all().keySet())) if(!id.equals(StudioId.LOCAL)) Anchors.remove(id);
        Studios.reset(); serverHash="none";
    }
    /** Fabric may fire DISCONNECT on Netty. All reset/listener effects use the client executor. */
    public static void disconnectOn(java.util.concurrent.Executor client) { client.execute(MpMode::disconnected); }
    public static String inactiveMessage() {
        return rejectedHello?"AgentCraft hello rejected · local features":"AgentCraft multiplayer inactive · local features";
    }
    /** Pure entry point for protocol/identity/lifecycle tests; the receiver alone sends the reply. */
    public static boolean receiveHello(HelloS2C hello,boolean integrated,UUID player) {
        if(integrated) return false;
        boolean valid=hello.protocol()==MpProtocol.VERSION && hello.you().owner().equals(player);
        try { if(hello.plotIndex()>=0) PlotGrid.originOf(hello.plotIndex(),hello.serverInfo().plotStride()); }
        catch(IllegalArgumentException e) { valid=false; }
        if(!valid) {
            rejectedHello=true;
            MpLog.event(MpEvents.HELLO_RECEIVED,"protocol",hello.protocol(),"mode",current);
            return false;
        }
        acceptedInfo=hello.serverInfo(); rejectedHello=false;
        helloTicks=0; Studios.setOwn(hello.you()); Studios.setPlot(hello.you(),hello.plotIndex(),hello.serverInfo().plotStride());
        change(MULTIPLAYER);
        MpLog.event(MpEvents.HELLO_RECEIVED,"player",player,"studio",hello.you().owner(),"plot",hello.plotIndex(),"protocol",hello.protocol(),"mode",current);
        return true;
    }
    public static void init() {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(dev.agentcraft.AgentCraft.id("hud/mp_mode"),(g,delta)-> {
            if(current()!=REMOTE_VANILLA) return;
            var mc=net.minecraft.client.Minecraft.getInstance();
            if(mc.player==null) return;
            String text=inactiveMessage();
            dev.agentcraft.client.ui.Panels.pill(g,mc.font,text,6,g.guiHeight()-36,dev.agentcraft.client.ui.UiStyle.color("paper.text"));
        });
        ClientPlayConnectionEvents.JOIN.register((handler,sender,mc)->mc.execute(()->joined(mc.getSingleplayerServer()!=null,hashServer(mc.getCurrentServer()==null?"integrated":mc.getCurrentServer().ip))));
        ClientPlayConnectionEvents.DISCONNECT.register((handler,mc)->disconnectOn(mc));
        ClientTickEvents.END_CLIENT_TICK.register(mc->tick());
        ClientPlayNetworking.registerGlobalReceiver(HelloS2C.TYPE,(hello,context)->context.client().execute(()-> {
            if(context.client().player!=null && receiveHello(hello,context.client().getSingleplayerServer()!=null,context.client().player.getUUID()) && ClientPlayNetworking.canSend(HelloC2S.TYPE)) {
                String version=FabricLoader.getInstance().getModContainer("agentcraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
                ClientPlayNetworking.send(HelloC2S.forCurrentVersion(version));
                MpLog.event(MpEvents.HELLO_SENT,"player",context.player().getUUID(),"studio",hello.you().owner(),"plot",hello.plotIndex(),"protocol",MpProtocol.VERSION);
            }
        }));
    }
}
