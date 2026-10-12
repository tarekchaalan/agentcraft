package dev.agentcraft.mp.net;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors.Layout;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record HelloC2S(int protocol, String modVersion) implements CustomPacketPayload {
    public HelloC2S { MpText.cap("modVersion",modVersion,32); }
    public static final Type<HelloC2S> TYPE = new Type<>(AgentCraft.id("hello_c2s"));
    public static final StreamCodec<RegistryFriendlyByteBuf,HelloC2S> CODEC = MpCodecs.codec(
        (b,v)-> { b.writeVarInt(v.protocol()); b.writeUtf(v.modVersion()); }, b->MpCodecs.hello(b,
            protocol->new HelloC2S(protocol,""), protocol->new HelloC2S(protocol,MpCodecs.text(b,32))));
    /** Bound the local build's version before constructing the handshake reply. */
    public static HelloC2S forCurrentVersion(String version) {
        return new HelloC2S(MpProtocol.VERSION,MpText.sanitize(version.substring(0,Math.min(32,version.length())),32));
    }
    public Type<HelloC2S> type() { return TYPE; }
}
