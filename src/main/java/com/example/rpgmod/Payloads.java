package com.example.rpgmod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class Payloads {
	private Payloads() {}

	/** Сервер -> клиент: данные прокачки. open=true открывает окно навыков. */
	public record Sync(int level, int xp, int points, String ranks, boolean open) implements CustomPacketPayload {
		public static final Type<Sync> TYPE = new Type<>(Identifier.fromNamespaceAndPath(RpgMod.MOD_ID, "sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Sync> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Sync::level,
			ByteBufCodecs.VAR_INT, Sync::xp,
			ByteBufCodecs.VAR_INT, Sync::points,
			ByteBufCodecs.STRING_UTF8, Sync::ranks,
			ByteBufCodecs.BOOL, Sync::open,
			Sync::new);

		@Override
		public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Клиент -> сервер: улучшить навык (id) или "__reset". */
	public record Upgrade(String id) implements CustomPacketPayload {
		public static final Type<Upgrade> TYPE = new Type<>(Identifier.fromNamespaceAndPath(RpgMod.MOD_ID, "upgrade"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Upgrade> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, Upgrade::id,
			Upgrade::new);

		@Override
		public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}
