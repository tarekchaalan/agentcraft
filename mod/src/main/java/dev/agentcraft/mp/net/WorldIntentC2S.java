package dev.agentcraft.mp.net;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors.Layout;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record WorldIntentC2S(WorldIntent intent) implements CustomPacketPayload {
    public static final Type<WorldIntentC2S> TYPE = new Type<>(AgentCraft.id("world_intent_c2s"));
    public static final StreamCodec<RegistryFriendlyByteBuf,WorldIntentC2S> CODEC = MpCodecs.codec(
        (b,v)-> { MpCodecs.intent(b,v.intent()); }, b->new WorldIntentC2S(MpCodecs.intent(b)));
    public Type<WorldIntentC2S> type() { return TYPE; }
}
