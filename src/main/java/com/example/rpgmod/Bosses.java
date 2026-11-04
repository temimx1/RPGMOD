package com.example.rpgmod;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** Мини-боссы: обычные мобы иногда превращаются в усиленных боссов со способностями. */
public final class Bosses {
	private Bosses() {}

	static final double CHANCE = 0.03;   // шанс, что подходящий моб станет боссом
	static final int MAX_ACTIVE = 4;     // не больше стольких боссов одновременно

	record Kind(String key, String type, String name, ChatFormatting color,
				double hp, double dmg, double scale, Item drop, int dropCount) {}

	static final List<Kind> KINDS = List.of(
		new Kind("colossus", "zombie", "Гниющий Колосс", ChatFormatting.DARK_GREEN, 80, 5, 0.6, Items.DIAMOND, 3),
		new Kind("archer", "skeleton", "Тень-лучник", ChatFormatting.GRAY, 45, 0, 0.1, Items.SPECTRAL_ARROW, 32),
		new Kind("queen", "spider", "Королева Пауков", ChatFormatting.DARK_RED, 55, 3, 0.8, Items.ENDER_PEARL, 4),
		new Kind("storm", "creeper", "Грозовой Подрывник", ChatFormatting.AQUA, 60, 0, 0.3, Items.FIREWORK_ROCKET, 16),
		new Kind("void", "enderman", "Страж Бездны", ChatFormatting.DARK_PURPLE, 80, 4, 0.2, Items.ENDER_EYE, 3),
		new Kind("inferno", "blaze", "Адский Вихрь", ChatFormatting.GOLD, 50, 0, 0.4, Items.BLAZE_ROD, 8),
		new Kind("witch", "witch", "Старая Ведьма", ChatFormatting.LIGHT_PURPLE, 60, 0, 0.2, Items.GOLDEN_APPLE, 3),
		new Kind("knight", "wither_skeleton", "Рыцарь Тлена", ChatFormatting.DARK_GRAY, 60, 6, 0.4, Items.NETHERITE_SCRAP, 2)
	);

	static final List<Mob> ACTIVE = new ArrayList<>();

	static Identifier id(String s) { return Identifier.fromNamespaceAndPath(RpgMod.MOD_ID, s); }

	static String typeKey(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
	}

	static boolean isDragon(Entity e) { return typeKey(e).equals("ender_dragon"); }

	static Kind kindOf(Entity e) {
		String t = typeKey(e);
		for (Kind k : KINDS) if (k.type().equals(t)) return k;
		return null;
	}

	static boolean isBoss(Entity e) {
		return e instanceof Mob m && m.getAttribute(Attributes.MAX_HEALTH) != null
			&& m.getAttribute(Attributes.MAX_HEALTH).getModifier(id("boss_hp")) != null;
	}

	/** Вызывается при загрузке моба в мир: регистрирует существующего босса или с шансом создаёт нового. */
	static void tryPromote(Mob m, ServerLevel world) {
		Kind k = kindOf(m);
		if (k == null) return;
		AttributeInstance hp = m.getAttribute(Attributes.MAX_HEALTH);
		AttributeInstance marker = m.getAttribute(Attributes.FOLLOW_RANGE);
		if (hp == null || marker == null) return;
		if (hp.getModifier(id("boss_hp")) != null) { if (!ACTIVE.contains(m)) ACTIVE.add(m); return; }
		if (marker.getModifier(id("rolled")) != null) return;
		marker.addPermanentModifier(new AttributeModifier(id("rolled"), 0.0, RpgMod.ADD));
		if (ACTIVE.size() >= MAX_ACTIVE || world.getRandom().nextDouble() > CHANCE) return;

		// сила босса зависит от среднего уровня игроков рядом
		double sum = 0; int n = 0;
		for (ServerPlayer p : world.players()) if (p.distanceTo(m) <= 128) { sum += RpgMod.data(p).level; n++; }
		double avg = n == 0 ? 1 : sum / n;
		double mult = 1 + avg / 40.0;

		hp.addPermanentModifier(new AttributeModifier(id("boss_hp"), k.hp() * mult, RpgMod.ADD));
		AttributeInstance dmg = m.getAttribute(Attributes.ATTACK_DAMAGE);
		if (dmg != null && k.dmg() > 0) dmg.addPermanentModifier(new AttributeModifier(id("boss_dmg"), k.dmg() * mult, RpgMod.ADD));
		AttributeInstance scale = m.getAttribute(Attributes.SCALE);
		if (scale != null) scale.addPermanentModifier(new AttributeModifier(id("boss_scale"), k.scale(), RpgMod.MUL));
		m.setHealth(m.getMaxHealth());
		m.setCustomName(Component.literal("☠ " + k.name() + " [Ур. " + (int) (5 + avg) + "]").withStyle(k.color(), ChatFormatting.BOLD));
		m.setCustomNameVisible(true);
		m.setPersistenceRequired();
		m.addEffect(new MobEffectInstance(MobEffects.GLOWING, MobEffectInstance.INFINITE_DURATION, 0, false, false));
		ACTIVE.add(m);

		for (ServerPlayer p : world.players()) if (p.distanceTo(m) <= 64)
			p.sendSystemMessage(Component.literal("⚔ Появился мини-босс: " + k.name() + "!").withStyle(k.color(), ChatFormatting.BOLD));
	}

	/** Награда за убийство босса. */
	static void onDeath(Mob boss, ServerPlayer killer) {
		Kind k = kindOf(boss);
		if (k == null) return;
		RpgMod.PlayerData d = RpgMod.data(killer);
		d.bonus++;
		d.points++;
		RpgMod.give(killer, new ItemStack(k.drop(), k.dropCount()));
		if (killer.level().getRandom().nextDouble() < 0.25) RpgMod.give(killer, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1));
		RpgMod.addXp(killer, 150);
		RpgMod.SERVER.getPlayerList().broadcastSystemMessage(Component.literal("★ " + killer.getName().getString()
			+ " победил босса «" + k.name() + "» и получил очко навыков!").withStyle(ChatFormatting.GOLD), false);
		RpgMod.sync(killer, false);
	}

	/** Каждый тик сервера: способности боссов раз в ~7 секунд. */
	static void tick(int tick) {
		ACTIVE.removeIf(m -> m.isRemoved() || !m.isAlive());
		for (Mob b : ACTIVE) {
			if (!(b.level() instanceof ServerLevel lvl)) continue;
			if ((tick + b.getId() * 20) % 140 != 0) continue;
			Kind k = kindOf(b);
			if (k != null) ability(b, k, lvl);
		}
	}

	static List<ServerPlayer> near(Mob b, ServerLevel lvl, double r) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : lvl.players()) if (p.isAlive() && !p.isSpectator() && p.distanceTo(b) <= r) out.add(p);
		return out;
	}

	static void fx(Mob b, ServerLevel lvl, ParticleOptions particle) {
		lvl.sendParticles(particle, b.getX(), b.getY() + b.getBbHeight() / 2, b.getZ(), 30, 1.2, 0.6, 1.2, 0.05);
	}

	static void hit(ServerLevel lvl, ServerPlayer p, float amount) { p.hurtServer(lvl, lvl.damageSources().magic(), amount); }

	static void eff(ServerPlayer p, Holder<MobEffect> e, int ticks, int amp) { p.addEffect(new MobEffectInstance(e, ticks, amp)); }

	static void ability(Mob b, Kind k, ServerLevel lvl) {
		switch (k.key()) {
			case "colossus" -> { // удар по земле
				for (ServerPlayer p : near(b, lvl, 6)) { hit(lvl, p, 6); eff(p, MobEffects.SLOWNESS, 80, 1); eff(p, MobEffects.WEAKNESS, 80, 0); }
				fx(b, lvl, ParticleTypes.EXPLOSION);
				lvl.playSound(null, b.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 1.5f, 0.5f);
			}
			case "archer" -> { // телепорт к ближайшему игроку
				b.addEffect(new MobEffectInstance(MobEffects.SPEED, 120, 1));
				List<ServerPlayer> ps = near(b, lvl, 24);
				if (!ps.isEmpty()) {
					ServerPlayer t = ps.get(0);
					BlockPos pos = t.blockPosition().offset(lvl.getRandom().nextInt(9) - 4, 0, lvl.getRandom().nextInt(9) - 4);
					if (lvl.getBlockState(pos).isAir() && lvl.getBlockState(pos.above()).isAir()) {
						fx(b, lvl, ParticleTypes.PORTAL);
						b.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
					}
				}
			}
			case "queen" -> { // яд и регенерация
				for (ServerPlayer p : near(b, lvl, 10)) { eff(p, MobEffects.POISON, 100, 1); eff(p, MobEffects.SLOWNESS, 60, 0); }
				b.heal(10f);
				fx(b, lvl, ParticleTypes.ITEM_SLIME);
			}
			case "storm" -> { // статический разряд
				for (ServerPlayer p : near(b, lvl, 8)) { hit(lvl, p, 7); eff(p, MobEffects.GLOWING, 100, 0); }
				fx(b, lvl, ParticleTypes.ELECTRIC_SPARK);
				lvl.playSound(null, b.blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 1.5f, 1f);
			}
			case "void" -> { // тьма и тошнота
				for (ServerPlayer p : near(b, lvl, 14)) { eff(p, MobEffects.DARKNESS, 120, 0); eff(p, MobEffects.NAUSEA, 120, 0); }
				fx(b, lvl, ParticleTypes.PORTAL);
			}
			case "inferno" -> { // огненная волна
				for (ServerPlayer p : near(b, lvl, 9)) p.igniteForSeconds(5);
				fx(b, lvl, ParticleTypes.FLAME);
			}
			case "witch" -> { // случайное проклятие
				List<Holder<MobEffect>> curses = List.of(MobEffects.WITHER, MobEffects.HUNGER, MobEffects.WEAKNESS, MobEffects.LEVITATION);
				for (ServerPlayer p : near(b, lvl, 12)) eff(p, curses.get(lvl.getRandom().nextInt(curses.size())), 100, 0);
				b.heal(8f);
				fx(b, lvl, ParticleTypes.WITCH);
			}
			case "knight" -> { // иссушение
				for (ServerPlayer p : near(b, lvl, 7)) { hit(lvl, p, 4); eff(p, MobEffects.WITHER, 100, 1); }
				fx(b, lvl, ParticleTypes.SOUL);
			}
			default -> {}
		}
	}
}
