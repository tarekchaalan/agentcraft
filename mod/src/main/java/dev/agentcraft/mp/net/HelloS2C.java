package dev.agentcraft.mp.net;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors.Layout;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record HelloS2C(int protocol, StudioId you, int plotIndex, ServerInfo serverInfo) implements CustomPacketPayload {
    public static final Type<HelloS2C> TYPE = new Type<>(AgentCraft.id("hello_s2c"));
    public static final StreamCodec<RegistryFriendlyByteBuf,HelloS2C> CODEC = MpCodecs.codec(
        (b,v)-> { b.writeVarInt(v.protocol()); b.writeUUID(v.you().owner()); b.writeVarInt(v.plotIndex()); MpCodecs.info(b,v.serverInfo()); }, b->MpCodecs.hello(b,
            protocol->new HelloS2C(protocol,StudioId.LOCAL,-1,new ServerInfo(0,0,0,0)),
            // A plot the grid cannot place is not refused here, which would disconnect: the receiver refuses the hello.
            protocol->new HelloS2C(protocol,MpCodecs.studio(b),MpCodecs.integer(b,-1,Integer.MAX_VALUE),MpCodecs.info(b))));
    public Type<HelloS2C> type() { return TYPE; }
}
