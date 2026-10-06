package com.example.subnauticalink;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * One line of the link's text, carried over Minecraft's own networking.
 *
 * <p>In multiplayer, each player's Subnautica talks only to that player's own Minecraft. But
 * much of what the link does is decided on the server (health, hunger, tools, where the player
 * is put). So lines travel one more hop: from a player's game to the server ("my Subnautica
 * said HEALTH 0.5") and from the server to a player's game ("tell your Subnautica FOOD 0.8").
 * This is the envelope they travel in. The same kind is used in both directions.
 */
public record LinePayload(String line) implements CustomPayload {
	public static final CustomPayload.Id<LinePayload> ID = new CustomPayload.Id<>(Identifier.of(SubnauticaLink.MOD_ID, "line"));

	/** Lines describing a model can be long, so the limit is well above Minecraft's usual one. */
	public static final PacketCodec<RegistryByteBuf, LinePayload> CODEC =
			PacketCodec.tuple(PacketCodecs.string(1 << 20), LinePayload::line, LinePayload::new);

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
