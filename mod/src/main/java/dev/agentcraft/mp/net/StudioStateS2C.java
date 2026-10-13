package dev.agentcraft.mp.net;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors.Layout;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record StudioStateS2C(StudioId studio, PublicStudioState state) implements CustomPacketPayload {
    public static final Type<StudioStateS2C> TYPE = new Type<>(AgentCraft.id("studio_state_s2c"));
    public static final StreamCodec<RegistryFriendlyByteBuf,StudioStateS2C> CODEC = MpCodecs.codec(
        (b,v)-> { b.writeUUID(v.studio().owner()); MpCodecs.state(b,v.state()); }, b->new StudioStateS2C(MpCodecs.studio(b), MpCodecs.state(b)));
    public Type<StudioStateS2C> type() { return TYPE; }
}
