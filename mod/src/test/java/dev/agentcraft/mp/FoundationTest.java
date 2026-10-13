package dev.agentcraft.mp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FoundationTest {
    @Test void spiral_round_trips_and_sites_do_not_overlap() {
        Set<BlockPos> seen=new HashSet<>(); List<Plot> plots=new ArrayList<>();
        for(int i=0;i<=200;i++) {
            BlockPos p=PlotGrid.originOf(i,128);
            assertTrue(seen.add(p)); assertEquals(0,p.getX()%16); assertEquals(0,p.getZ()%16); assertEquals(0,p.getY());
            assertEquals(i,PlotGrid.indexAt(p.getX(),p.getZ(),128).orElseThrow());
            Plot plot=new Plot(i,StudioId.LOCAL,p);
            for(Plot old:plots) assertFalse(old.box().intersects(plot.box()));
            plots.add(plot);
        }
        assertEquals(BlockPos.ZERO,PlotGrid.originOf(0,128));
        assertTrue(PlotGrid.indexAt(64,0,128).isEmpty());
        assertThrows(IllegalArgumentException.class,()->PlotGrid.originOf(-1,128));
        assertThrows(IllegalArgumentException.class,()->PlotGrid.originOf(1,127));
        for(int i:List.of(1000000,Integer.MAX_VALUE)) {
            BlockPos p=PlotGrid.originOf(i,128); assertEquals(i,PlotGrid.indexAt(p.getX(),p.getZ(),128).orElseThrow());
        }
    }
    @Test void local_directory_and_inclusive_site() {
        Plot p=Plots.directory().plotOf(StudioId.LOCAL).orElseThrow();
        assertTrue(p.contains(new BlockPos(46,100,54))); assertFalse(p.contains(new BlockPos(47,100,54)));
        assertEquals(List.of(p),Plots.directory().all());
    }
    @Test void config_defaults_and_invalid_values_warn(@TempDir Path dir) throws Exception {
        Path f=dir.resolve("server.json");
        try(var capture=MpLog.capture()) {
            assertEquals(MpServerConfig.DEFAULT,MpServerConfig.load(f));
            assertEquals(List.of("event=config_loaded enabled=false file=none"),capture.lines());
            Files.writeString(f,"{\"enabled\":\"true\",\"plotStride\":127,\"autoBuild\":false,\"private\":\"secret text\"}");
            var c=MpServerConfig.load(f); assertFalse(c.enabled()); assertEquals(128,c.plotStride()); assertFalse(c.autoBuild());
            assertEquals(3,capture.lines().stream().filter(l->l.startsWith("event=config_invalid ")).count());
            assertTrue(capture.lines().stream().anyMatch(l->l.startsWith("event=config_loaded enabled=false file=")));
            assertEquals("warn",MpEvents.CATALOG.get(MpEvents.CONFIG_INVALID));
            assertFalse(capture.lines().toString().contains("secret"));
        }
    }
    static int checkEvents(String source) throws Exception {
        var matcher=Pattern.compile("MpLog\\.event\\(\\s*([^,)]+)").matcher(source);
        int found=0;
        while(matcher.find()) {
            String expression=matcher.group(1).trim(), value;
            if(expression.startsWith("MpEvents.")) value=(String)MpEvents.class.getField(expression.substring(9)).get(null);
            else if(expression.startsWith("\"") && expression.endsWith("\"")) value=expression.substring(1,expression.length()-1);
            else throw new AssertionError("unresolved event: "+expression);
            assertTrue(MpEvents.CATALOG.containsKey(value),value); found++;
        }
        return found;
    }
    @Test void catalog_covers_every_mp_event_in_the_sources() throws Exception {
        int found=0;
        for(String sourceSet:List.of("main","client")) {
            try(var paths=Files.walk(Path.of(System.getProperty("agentcraft.src"),sourceSet))) {
                for(Path p:paths.filter(p->p.toString().endsWith(".java")).toList()) found+=checkEvents(Files.readString(p));
            }
        }
        assertTrue(found>0,"scan must inspect real event calls");
        assertThrows(AssertionError.class,()->checkEvents("MpLog.event(\"not_in_catalog\", \"count\", 1)"));
        for(var field:MpEvents.class.getFields()) if(field.getType()==String.class) assertTrue(MpEvents.CATALOG.containsKey(field.get(null)));
    }
    @Test void install_restores_previous_config_and_preserves_dedicated_gate() {
        var original=MpServerConfig.current();
        var enabled=new MpServerConfig(true,256,false,false,false,false,false,24,7,19);
        try {
            assertEquals(original,MpServerConfig.install(enabled));
            assertSame(enabled,MpServerConfig.current());
            UUID player=UUID.randomUUID();
            assertTrue(dev.agentcraft.mp.net.MpPayloads.helloFor(player,MpServerConfig.current(),false,true).isEmpty());
            var hello=dev.agentcraft.mp.net.MpPayloads.helloFor(player,MpServerConfig.current(),true,true).orElseThrow();
            assertEquals(new dev.agentcraft.mp.net.ServerInfo(256,24,7,19),hello.serverInfo());
            assertEquals(enabled,MpServerConfig.install(MpServerConfig.DEFAULT));
            assertTrue(dev.agentcraft.mp.net.MpPayloads.helloFor(player,MpServerConfig.current(),true,true).isEmpty());
            assertThrows(NullPointerException.class,()->MpServerConfig.install(null));
            assertEquals(new MpServerConfig(false,128,true,true,true,true,true,12,4,10),MpServerConfig.DEFAULT);
        } finally { MpServerConfig.install(original); }
    }
    @Test void plot_box_agrees_at_every_boundary_and_extreme_origins_fail_closed() {
        Plot plot=new Plot(1,StudioId.LOCAL,PlotGrid.originOf(1,128));
        for(int x:List.of(-47,-46,46,47)) for(int y:List.of(59,60,100,101)) for(int z:List.of(-37,-36,54,55)) {
            BlockPos p=plot.origin().offset(x,y,z);
            assertEquals(plot.contains(p),plot.box().contains(p.getX(),p.getY(),p.getZ()));
            assertEquals(plot.contains(p),plot.box().contains(p.getX()+.5,p.getY()+.5,p.getZ()+.5));
        }
        assertThrows(IllegalArgumentException.class,()->PlotGrid.originOf(Integer.MAX_VALUE,1048576));
        assertThrows(IllegalArgumentException.class,()->PlotGrid.originOf(4000,1048576));
    }
    @Test void malformed_and_out_of_range_config_fall_back_without_content_leaks(@TempDir Path dir) throws Exception {
        Path file=dir.resolve("server.json");
        for(String json:List.of("{broken", "{\"publicStatePerSecond\":1001,\"intentsPerSecond\":0,\"relayRadiusChunks\":-1,\"plotStride\":1048592}")) {
            Files.writeString(file,json);
            try(var capture=MpLog.capture()) {
                assertEquals(MpServerConfig.DEFAULT,MpServerConfig.load(file));
                assertTrue(capture.lines().stream().anyMatch(l->l.startsWith("event=config_invalid ")));
                assertFalse(capture.lines().toString().contains("broken"));
            }
        }
    }
    @Test void wave_one_reason_names_and_studio_event_catalog_entry_are_fixed() {
        assertEquals("multiplayer_screen",MpReasons.MULTIPLAYER_SCREEN);
        assertEquals("bad_origin",MpReasons.BAD_ORIGIN);
        assertEquals("build_error",MpReasons.BUILD_ERROR);
        assertEquals("unknown_agent",MpReasons.UNKNOWN_AGENT);
        assertEquals("studio_event_rejected",MpEvents.STUDIO_EVENT_REJECTED);
        assertEquals("warn",MpEvents.CATALOG.get(MpEvents.STUDIO_EVENT_REJECTED));
        try(var capture=MpLog.capture()) {
            MpLog.event(MpEvents.STUDIO_EVENT_REJECTED,"reason",MpReasons.UNKNOWN_AGENT);
            assertEquals(List.of("event=studio_event_rejected reason=unknown_agent"),capture.lines());
        }
    }
    @Test void telemetry_values_neutralise_unicode_separators_and_format_controls() {
        // NEL, line and paragraph separators, then format controls: soft hyphen, zero-width space, bidi override and isolate, BOM, a supplementary tag.
        List<String> leaked=new ArrayList<>();
        for(int cp:List.of(0x85,0x2028,0x2029,0xAD,0x200B,0x202E,0x2066,0xFEFF,0xE0001)) {
            try(var capture=MpLog.capture()) {
                MpLog.event(MpEvents.STUDIO_EVENT_REJECTED,"reason","a"+Character.toString(cp)+"b");
                if(!capture.lines().equals(List.of("event=studio_event_rejected reason=a_b"))) leaked.add("U+"+Integer.toHexString(cp).toUpperCase());
            }
        }
        assertEquals(List.of(),leaked);
        try(var capture=MpLog.capture()) {
            MpLog.event(MpEvents.STUDIO_EVENT_REJECTED,"reason","config/agentcraft-server.json","count",-1,"studio",new UUID(1,2));
            assertEquals(List.of("event=studio_event_rejected reason=config/agentcraft-server.json count=-1 studio=00000000-0000-0001-0000-000000000002"),capture.lines());
        }
    }
}
