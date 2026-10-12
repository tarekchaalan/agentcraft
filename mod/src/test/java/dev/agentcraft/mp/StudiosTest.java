package dev.agentcraft.mp;

import static org.junit.jupiter.api.Assertions.*;
import dev.agentcraft.client.mp.*;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.net.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StudiosTest {
    @AfterEach void reset() { MpMode.disconnected(); Anchors.publish(Anchors.Layout.EMPTY); }
    @Test void slots_boundaries_layout_fallback_overlay_and_listeners() {
        Studios.reset();
        var layout=Anchors.builder("local").bounds(-46,60,-36,46,100,54).build(); Anchors.publish(layout);
        StudioId remote=StudioId.of(UUID.randomUUID()), later=StudioId.of(UUID.randomUUID());
        List<StudioView> notices=new ArrayList<>(); Studios.addListener((id,view)-> { if(id.equals(remote)) notices.add(view); });
        Studios.put(new StudioView(remote,false,"Bob",true,layout,CodecTest.state(),999));
        Studios.setPlot(remote,1,128);
        assertEquals(-10000,Studios.entityIdBase(StudioId.LOCAL)); assertEquals(-11000,Studios.entityIdBase(remote));
        assertEquals(remote,Studios.at(new BlockPos(128,66,0)).orElseThrow().id());
        assertTrue(Studios.at(new BlockPos(0,66,0)).orElseThrow().own());
        Studios.setOverlay(remote); assertFalse(Studios.at(new BlockPos(0,66,0)).orElseThrow().own());
        Studios.updateState(remote,CodecTest.state()); assertEquals(2,notices.size());
        Studios.remove(remote); assertTrue(Studios.at(new BlockPos(0,66,0)).orElseThrow().own());
        assertEquals(-12000,Studios.entityIdBase(later)); assertEquals(-11000,Studios.entityIdBase(remote));
        var far=Anchors.builder("far").bounds(1000,60,1000,1010,100,1010).build();
        Studios.put(new StudioView(later,false,"Ann",false,far,null,2));
        assertEquals(later,Studios.at(new BlockPos(1005,66,1005)).orElseThrow().id());
        assertTrue(Studios.at(new BlockPos(500,66,500)).isEmpty());
    }
    @Test void only_matching_remote_hello_changes_mode_and_singleplayer_never_sends() {
        UUID player=UUID.randomUUID(); StudioId id=StudioId.of(player);
        var yes=new MpServerConfig(true,128,true,true,true,true,true,12,4,10);
        assertTrue(MpPayloads.helloFor(player,yes,false,true).isEmpty());
        assertTrue(MpPayloads.helloFor(player,MpServerConfig.DEFAULT,true,true).isEmpty());
        assertTrue(MpPayloads.helloFor(player,yes,true,false).isEmpty());
        var hello=MpPayloads.helloFor(player,yes,true,true).orElseThrow();
        assertEquals(-1,hello.plotIndex()); assertEquals(id,hello.you());
        try(var capture=MpLog.capture()) {
            MpMode.joined(true,"integrated"); assertFalse(MpMode.receiveHello(hello,true,player));
            assertEquals(MpMode.SINGLEPLAYER,MpMode.current()); assertEquals(StudioId.LOCAL,Anchors.self());
            MpMode.joined(false,MpMode.hashServer("example.invalid:25600"));
            assertFalse(MpMode.receiveHello(new HelloS2C(2,id,-1,new ServerInfo(128,12,4,10)),false,player));
            assertFalse(MpMode.receiveHello(hello,false,UUID.randomUUID()));
            for(int i=0;i<100;i++) MpMode.tick(); assertEquals(MpMode.REMOTE_VANILLA,MpMode.current());
            assertTrue(MpMode.receiveHello(hello,false,player)); assertEquals(MpMode.MULTIPLAYER,MpMode.current());
            assertEquals(id,Anchors.self()); assertEquals(0,Studios.own().slot());
            assertFalse(MpPayloads.acceptHello(player,new HelloC2S(2,"secret"),yes,true));
            assertFalse(MpPayloads.acceptHello(player,new HelloC2S(1,"secret"),yes,false));
            assertTrue(MpPayloads.acceptHello(player,new HelloC2S(1,"secret"),yes,true)); assertTrue(MpPayloads.isModEquipped(player));
            MpPayloads.logSent(hello);
            assertTrue(capture.lines().stream().anyMatch(l->l.startsWith("event=hello_sent ")));
            assertTrue(capture.lines().stream().anyMatch(l->l.startsWith("event=hello_received ")));
            assertTrue(capture.lines().stream().anyMatch(l->l.startsWith("event=mode_changed from=REMOTE_VANILLA to=MULTIPLAYER server=")));
            assertFalse(capture.lines().toString().contains("example.invalid")); assertFalse(capture.lines().toString().contains("secret"));
        }
        MpMode.disconnected(); assertEquals(MpMode.SINGLEPLAYER,MpMode.current()); assertEquals(StudioId.LOCAL,Studios.own().id());
    }
    @Test void retained_hello_plot_and_disconnect_are_client_executor_owned() throws Exception {
        UUID player=UUID.randomUUID(); StudioId id=StudioId.of(player), remote=StudioId.of(UUID.randomUUID());
        var info=new ServerInfo(256,24,7,19);
        MpMode.joined(false,"test"); assertTrue(MpMode.serverInfo().isEmpty());
        assertTrue(MpMode.receiveHello(new HelloS2C(1,id,3,info),false,player));
        assertEquals(Optional.of(info),MpMode.serverInfo());
        assertEquals(PlotGrid.originOf(3,256),Studios.plot(id).orElseThrow().origin());
        assertTrue(Studios.plot(remote).isEmpty());
        List<Runnable> queue=new ArrayList<>();
        boolean[] bridgeActive={true};
        Anchors.addStudioListener((studio,layout)-> { if(bridgeActive[0]) Studios.anchorsChanged(queue::add,studio,layout); });
        List<Thread> mutations=new ArrayList<>();
        MpMode.addListener(mode->{ if(bridgeActive[0]) mutations.add(Thread.currentThread()); });
        Studios.addListener((studio,view)-> { if(bridgeActive[0]) mutations.add(Thread.currentThread()); });
        try {
            var layout=Anchors.builder("remote").bounds(1000,60,1000,1010,100,1010).build();
            Anchors.publish(remote,layout); drain(queue);
            assertEquals(layout,Studios.view(remote).orElseThrow().layout());
            var updated=Anchors.builder("updated").spot("desk",1000,66,1000,0).build();
            Anchors.publish(remote,updated); drain(queue);
            assertEquals(updated,Studios.view(remote).orElseThrow().layout());
            Anchors.publish(remote,Anchors.Layout.EMPTY); drain(queue); assertTrue(Studios.view(remote).isPresent());
            Anchors.remove(remote); drain(queue); assertTrue(Studios.view(remote).isEmpty());
            // Leave a publication queued when the network disconnect arrives.
            Anchors.publish(remote,layout);
            Thread network=new Thread(()->MpMode.disconnectOn(queue::add)); network.start(); network.join();
            assertEquals(MpMode.MULTIPLAYER,MpMode.current()); assertEquals(Optional.of(info),MpMode.serverInfo());
            drain(queue);
            assertEquals(MpMode.SINGLEPLAYER,MpMode.current()); assertTrue(MpMode.serverInfo().isEmpty());
            assertEquals(StudioId.LOCAL,Anchors.self()); assertTrue(Studios.plot(id).isEmpty());
            assertTrue(Studios.view(remote).isEmpty()); assertEquals(1,Studios.all().size());
            assertTrue(mutations.stream().allMatch(thread->thread==Thread.currentThread()));
            MpMode.joined(false,"test"); assertTrue(MpMode.serverInfo().isEmpty());
        } finally { bridgeActive[0]=false; }
    }
    private static void drain(List<Runnable> queue) { while(!queue.isEmpty()) queue.removeFirst().run(); }
    @Test void singleplayer_never_times_out_remote_waits_exactly_100_ticks_and_rejections_are_visible() {
        MpMode.disconnected();
        try(var capture=MpLog.capture()) {
            MpMode.joined(true,"integrated"); for(int i=0;i<200;i++) MpMode.tick();
            assertEquals(MpMode.SINGLEPLAYER,MpMode.current()); assertTrue(capture.lines().isEmpty());
            MpMode.joined(false,"test"); for(int i=0;i<99;i++) MpMode.tick();
            assertEquals(MpMode.SINGLEPLAYER,MpMode.current()); MpMode.tick(); assertEquals(MpMode.REMOTE_VANILLA,MpMode.current());
        }
        MpMode.joined(false,"test"); UUID player=UUID.randomUUID(); StudioId id=StudioId.of(player);
        try(var capture=MpLog.capture()) {
            assertFalse(MpMode.receiveHello(new HelloS2C(2,id,-1,new ServerInfo(128,12,4,10)),false,player));
            assertTrue(capture.lines().stream().anyMatch(line->line.contains("event=hello_received protocol=2 mode=SINGLEPLAYER")));
            assertTrue(MpMode.inactiveMessage().contains("hello rejected"));
            assertFalse(MpMode.receiveHello(new HelloS2C(1,id,Integer.MAX_VALUE,new ServerInfo(1048576,12,4,10)),false,player));
            assertEquals(StudioId.LOCAL,Anchors.self()); assertTrue(MpMode.serverInfo().isEmpty());
            for(int i=0;i<100;i++) MpMode.tick(); assertEquals(MpMode.REMOTE_VANILLA,MpMode.current());
        }
        MpMode.disconnected(); assertFalse(MpMode.inactiveMessage().contains("rejected"));
    }
    @Test void address_hash_is_salted_and_registry_lookup_reuses_immutable_views() throws Exception {
        String address="example.invalid:25600";
        String plain=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(address.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,16);
        assertNotEquals(plain,MpMode.hashServer(address)); assertEquals(MpMode.hashServer(address),MpMode.hashServer(address));
        Studios.reset(); BlockPos pos=new BlockPos(0,66,0);
        assertSame(Studios.own(),Studios.own()); assertSame(Studios.at(pos),Studios.at(pos));
        StudioView before=Studios.own(); Studios.updatePresence(StudioId.LOCAL,"owner",false);
        assertNotSame(before,Studios.own()); assertFalse(Studios.own().online());
        var layout=Anchors.builder("new").spot("desk",0,66,0,0).build(); Anchors.publish(layout);
        assertSame(layout,Studios.own().layout());
    }
}
