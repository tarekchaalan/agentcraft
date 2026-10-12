package dev.agentcraft.mp.net;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors.Layout;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record StudioEventC2S(PublicEvent event) implements CustomPacketPayload {
    public static final Type<StudioEventC2S> TYPE = new Type<>(AgentCraft.id("studio_event_c2s"));
    public static final StreamCodec<RegistryFriendlyByteBuf,StudioEventC2S> CODEC = MpCodecs.codec(
        (b,v)-> { MpCodecs.event(b,v.event()); }, b->new StudioEventC2S(MpCodecs.event(b)));
    public Type<StudioEventC2S> type() { return TYPE; }
}
