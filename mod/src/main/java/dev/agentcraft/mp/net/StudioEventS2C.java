package dev.agentcraft.mp.net;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors.Layout;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record StudioEventS2C(StudioId studio, PublicEvent event) implements CustomPacketPayload {
    public static final Type<StudioEventS2C> TYPE = new Type<>(AgentCraft.id("studio_event_s2c"));
    public static final StreamCodec<RegistryFriendlyByteBuf,StudioEventS2C> CODEC = MpCodecs.codec(
        (b,v)-> { b.writeUUID(v.studio().owner()); MpCodecs.event(b,v.event()); }, b->new StudioEventS2C(MpCodecs.studio(b), MpCodecs.event(b)));
    public Type<StudioEventS2C> type() { return TYPE; }
}
