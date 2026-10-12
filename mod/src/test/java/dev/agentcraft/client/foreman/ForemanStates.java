package dev.agentcraft.client.foreman;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Fresh, connected state fixtures for packet tests; no client or Foreman process is needed. */
public final class ForemanStates {
    private ForemanStates() {}
    public static ForemanState showcase() { return load("showcase.json"); }
    public static ForemanState showcaseLate() { return load("showcase-late.json"); }
    public static ForemanState fromSnapshot(JsonObject snapshot) {
        ForemanState state=new ForemanState(new LinkStatus(LinkStatus.Phase.SYNCED,"",0,null,0,0,true));
        state.receive("snapshot",snapshot.deepCopy());
        return state;
    }
    private static ForemanState load(String name) {
        try(var input=Objects.requireNonNull(ForemanStates.class.getResourceAsStream("/dev/agentcraft/fixtures/"+name),"missing snapshot fixture");
            var reader=new InputStreamReader(input,StandardCharsets.UTF_8)) {
            return fromSnapshot(JsonParser.parseReader(reader).getAsJsonObject());
        } catch(IOException e) { throw new IllegalStateException("cannot read snapshot fixture",e); }
    }
}
