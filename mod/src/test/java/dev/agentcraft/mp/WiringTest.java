package dev.agentcraft.mp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WiringTest {
    @Test void all_feature_seams_are_wired_once() throws Exception {
        for(var group:List.of(
                List.of("main/java/dev/agentcraft/AgentCraft.java","world/ServerWorldFeature","plot/PlotFeature","layout/LayoutSyncFeature","relay/RelayFeature","intent/WorldIntentFeature","protect/PlotProtectionFeature"),
                List.of("client/java/dev/agentcraft/client/ClientFeatures.java","layout/LayoutSyncClient","publish/PublishFeature","remote/RemoteStudiosFeature","visitor/VisitorFeature"))) {
            String wiring=Files.readString(Path.of(System.getProperty("agentcraft.src"),group.getFirst()));
            List<String> own=group.getFirst().startsWith("main")?List.of("MpServerConfig.init()","MpPayloads.register()","StudioRange.init()"):List.of("Studios.init()","MpMode.init()","MpDevFake.init()");
            for(String call:own) assertEquals(1,wiring.split(java.util.regex.Pattern.quote(call),-1).length-1,call);
            if(group.getFirst().startsWith("main")) {
                assertTrue(wiring.indexOf("MpPayloads.register()")<wiring.indexOf("StudioRange.init()"));
                assertTrue(wiring.indexOf("StudioRange.init()")<wiring.indexOf("ServerWorldFeature.init()"));
            }
            for(String stub:group.subList(1,group.size())) {
                String name=stub.substring(stub.lastIndexOf('/')+1);
                assertEquals(1,wiring.split(name+"\\.init\\(\\)",-1).length-1,name);
                Path root=Path.of(System.getProperty("agentcraft.src"),group.getFirst().startsWith("main")?"main/java/dev/agentcraft/mp/server":"client/java/dev/agentcraft/client/mp");
                assertTrue(Files.readString(root.resolve(stub+".java")).contains("public static void init()"));
            }
        }
    }
}
