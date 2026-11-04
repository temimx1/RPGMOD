package com.example.rpgmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class RpgMod implements ModInitializer {
	public static final String MOD_ID = "rpgmod";
	public static final int MAX_LEVEL = 100;

	// ---- настройки баланса ----
	static final double XP_PER_MOB_HP = 1.5;
	static final int ORE_XP = 8, RARE_ORE_XP = 25;
	static final int DRAGON_BONUS_HP = 800;
	static final int DRAGON_HP_PER_EXTRA_PLAYER = 300;
	static final double SHARE_RADIUS = 40;

	public static int xpNeeded(int lvl) { return 30 + 6 * lvl + lvl * lvl / 10; }

	public record Skill(String id, String name, String branch, int max, String req, int reqRank,
						Holder<Attribute> attr, double perRank, AttributeModifier.Operation op, String desc) {}

	public static final AttributeModifier.Operation ADD = AttributeModifier.Operation.ADD_VALUE;
	public static final AttributeModifier.Operation MUL = AttributeModifier.Operation.ADD_MULTIPLIED_BASE;

	public static final List<Skill> SKILLS = List.of(
		new Skill("strength", "Сила", "Воин", 10, null, 0, Attributes.ATTACK_DAMAGE, 0.5, ADD, "+0.5 урона за ранг"),
		new Skill("haste", "Быстрый удар", "Воин", 5, "strength", 3, Attributes.ATTACK_SPEED, 0.1, MUL, "+10% скорости атаки за ранг"),
		new Skill("bloodlust", "Жажда крови", "Воин", 5, "strength", 5, null, 1, ADD, "Лечение при убийстве моба: +0.5 сердца за ранг"),
		new Skill("vitality", "Живучесть", "Страж", 10, null, 0, Attributes.MAX_HEALTH, 2, ADD, "+1 сердце за ранг"),
		new Skill("armor", "Закалка", "Страж", 10, "vitality", 3, Attributes.ARMOR, 1, ADD, "+1 броня за ранг"),
		new Skill("anchor", "Стойкость", "Страж", 5, "armor", 3, Attributes.KNOCKBACK_RESISTANCE, 0.1, ADD, "+10% сопротивления отбрасыванию за ранг"),
		new Skill("regen", "Регенерация", "Страж", 5, "vitality", 5, null, 1, ADD, "Лечение каждые 5 сек: +0.25 сердца за ранг"),
		new Skill("speed", "Проворство", "Ловкач", 10, null, 0, Attributes.MOVEMENT_SPEED, 0.04, MUL, "+4% скорости за ранг"),
		new Skill("jump", "Прыгучесть", "Ловкач", 5, null, 0, Attributes.JUMP_STRENGTH, 0.06, MUL, "+6% высоты прыжка за ранг"),
		new Skill("feather", "Лёгкий шаг", "Ловкач", 5, "jump", 2, Attributes.SAFE_FALL_DISTANCE, 1, ADD, "+1 блок безопасного падения за ранг"),
		new Skill("luck", "Удача", "Ловкач", 5, null, 0, Attributes.LUCK, 1, ADD, "+1 удача за ранг"),
		new Skill("digger", "Копатель", "Шахтёр", 10, null, 0, Attributes.BLOCK_BREAK_SPEED, 0.1, MUL, "+10% скорости добычи за ранг"),
		new Skill("prospector", "Рудознатец", "Шахтёр", 5, "digger", 3, null, 1, ADD, "+20% опыта с руд за ранг"),
		new Skill("spirit", "Командный дух", "Команда", 5, null, 0, null, 1, ADD, "Опыт от убийств друзей: 40% + 12% за ранг"),
		new Skill("aura", "Аура лечения", "Команда", 3, "spirit", 2, null, 1, ADD, "Каждые 10 сек лечит друзей рядом (16 блоков) на 1 сердце за ранг"),
		new Skill("rally", "Боевой клич", "Команда", 3, "aura", 1, null, 1, ADD, "Друзья рядом получают Силу при вашем убийстве моба (5 сек за ранг)")
	);
	public static final Map<String, Skill> SKILL_BY_ID = new HashMap<>();
	static { for (Skill s : SKILLS) SKILL_BY_ID.put(s.id(), s); }

	public static class PlayerData {
		int level = 1;
		int xp = 0;
		int points = 0;
		int bonus = 0; // очки навыков за боссов
		Map<String, Integer> ranks = new HashMap<>();
		int rank(String id) { return ranks.getOrDefault(id, 0); }
	}

	static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	static Map<String, PlayerData> DATA = new HashMap<>();
	static MinecraftServer SERVER;
	static int tick = 0;

	static PlayerData data(ServerPlayer p) { return DATA.computeIfAbsent(p.getUUID().toString(), k -> new PlayerData()); }
	static Path file(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("rpgmod_players.json"); }

	static void load(MinecraftServer s) {
		try {
			Path f = file(s);
			if (Files.exists(f)) {
				Type t = new TypeToken<Map<String, PlayerData>>() {}.getType();
				Map<String, PlayerData> m = GSON.fromJson(Files.readString(f), t);
				DATA = m != null ? m : new HashMap<>();
			}
		} catch (Exception e) { System.err.println("[rpgmod] load failed: " + e); }
	}

	static void save(MinecraftServer s) {
		try { Files.writeString(file(s), GSON.toJson(DATA)); }
		catch (IOException e) { System.err.println("[rpgmod] save failed: " + e); }
	}

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.clientboundPlay().register(Payloads.Sync.TYPE, Payloads.Sync.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Payloads.Upgrade.TYPE, Payloads.Upgrade.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(Payloads.Upgrade.TYPE, (payload, ctx) -> {
			ServerPlayer p = ctx.player();
			if (payload.id().equals("__reset")) doReset(p); else doUpgrade(p, payload.id());
			sync(p, false);
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			SERVER = server;
			load(server);
			run(server, "scoreboard objectives add rpg_lvl dummy {text:\"Ур.\"}");
			run(server, "scoreboard objectives setdisplay below_name rpg_lvl");
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(RpgMod::save);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> refresh(handler.getPlayer()));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldP, newP, alive) -> refresh(newP));

		ServerLivingEntityEvents.AFTER_DEATH.register((victim, source) -> {
			if (Bosses.isDragon(victim)) { onDragonKilled(victim); return; }
			if (!(source.getEntity() instanceof ServerPlayer killer) || victim.getType().getCategory() != MobCategory.MONSTER) return;
			int xp = (int) (victim.getMaxHealth() * XP_PER_MOB_HP) + 5;
			PlayerData kd = data(killer);
			if (victim instanceof Mob m && Bosses.isBoss(m)) Bosses.onDeath(m, killer);
			addXp(killer, xp);
			for (ServerPlayer ally : nearbyAllies(killer, SHARE_RADIUS)) {
				PlayerData ad = data(ally);
				addXp(ally, (int) Math.max(1, xp * (0.4 + 0.12 * ad.rank("spirit"))));
				if (kd.rank("rally") > 0)
					ally.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 20 * 5 * kd.rank("rally"), 0));
			}
			if (kd.rank("bloodlust") > 0) killer.heal(kd.rank("bloodlust"));
		});

		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
			if (!(player instanceof ServerPlayer sp)) return;
			String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
			if (!path.endsWith("_ore") && !path.equals("ancient_debris")) return;
			boolean rare = path.contains("diamond") || path.contains("emerald") || path.contains("ancient_debris");
			addXp(sp, (int) ((rare ? RARE_ORE_XP : ORE_XP) * (1 + 0.2 * data(sp).rank("prospector"))));
		});

		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof Mob m && !Bosses.isDragon(m)) Bosses.tryPromote(m, world);
			if (Bosses.isDragon(entity) && entity instanceof LivingEntity dragon) {
				AttributeInstance hp = dragon.getAttribute(Attributes.MAX_HEALTH);
				Identifier mid = Identifier.fromNamespaceAndPath(MOD_ID, "dragon_boost");
				if (hp == null || hp.getModifier(mid) != null) return;
				int players = Math.max(1, world.getServer().getPlayerList().getPlayerCount());
				hp.addPermanentModifier(new AttributeModifier(mid, DRAGON_BONUS_HP + (players - 1) * DRAGON_HP_PER_EXTRA_PLAYER, ADD));
				dragon.setHealth(dragon.getMaxHealth());
				world.getServer().getPlayerList().broadcastSystemMessage(
					Component.literal("☠ Эндер-дракон пробудился с " + (int) dragon.getMaxHealth() + " HP! Нужен уровень "
						+ MAX_LEVEL + ", чтобы ранить его.").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);
			}
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (Bosses.isDragon(entity) && source.getEntity() instanceof ServerPlayer p && data(p).level < MAX_LEVEL) {
				p.sendSystemMessage(Component.literal("Дракон слишком силён! Нужен " + MAX_LEVEL + " уровень (у тебя "
					+ data(p).level + ")").withStyle(ChatFormatting.RED), true);
				return false;
			}
			return true;
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tick++;
			if (tick % 6000 == 0) save(server);
			if (tick % 20 != 0) return;
			Bosses.tick(tick);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				PlayerData d = data(p);
				String xpPart = d.level >= MAX_LEVEL ? "MAX" : d.xp + "/" + xpNeeded(d.level);
				p.sendSystemMessage(Component.literal("Ур. " + d.level + "  |  XP " + xpPart
					+ (d.points > 0 ? "  |  Очки навыков: " + d.points + " (/skills)" : ""))
					.withStyle(d.points > 0 ? ChatFormatting.GOLD : ChatFormatting.GREEN), true);
				if (tick % 100 == 0 && d.rank("regen") > 0) p.heal(0.5f * d.rank("regen"));
				if (tick % 200 == 0 && d.rank("aura") > 0)
					for (ServerPlayer ally : nearbyAllies(p, 16)) ally.heal(2f * d.rank("aura"));
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> {
			dispatcher.register(Commands.literal("skills")
				.executes(c -> {
					ServerPlayer p = c.getSource().getPlayerOrException();
					if (ServerPlayNetworking.canSend(p, Payloads.Sync.TYPE)) sync(p, true); else showTree(p);
					return 1;
				})
				.then(Commands.literal("reset").executes(c -> {
					ServerPlayer p = c.getSource().getPlayerOrException();
					doReset(p); sync(p, false);
					return 1;
				})));
			dispatcher.register(Commands.literal("skill")
				.then(Commands.literal("up").then(Commands.argument("id", StringArgumentType.word()).executes(c -> {
					ServerPlayer p = c.getSource().getPlayerOrException();
					doUpgrade(p, StringArgumentType.getString(c, "id"));
					sync(p, false);
					if (!ServerPlayNetworking.canSend(p, Payloads.Sync.TYPE)) showTree(p);
					return 1;
				}))));
		});
	}

	// ---- логика ----
	static List<ServerPlayer> nearbyAllies(ServerPlayer p, double r) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer o : SERVER.getPlayerList().getPlayers())
			if (o != p && o.level() == p.level() && o.distanceTo(p) <= r) out.add(o);
		return out;
	}

	static void give(ServerPlayer p, ItemStack stack) {
		run(SERVER, "give " + p.getScoreboardName() + " " + BuiltInRegistries.ITEM.getKey(stack.getItem()) + " " + stack.getCount());
	}

	static void addXp(ServerPlayer p, int amount) {
		PlayerData d = data(p);
		if (d.level >= MAX_LEVEL) return;
		d.xp += amount;
		boolean leveled = false;
		while (d.level < MAX_LEVEL && d.xp >= xpNeeded(d.level)) {
			d.xp -= xpNeeded(d.level);
			d.level++;
			d.points++;
			leveled = true;
			if (d.level % 10 == 0 || d.level == MAX_LEVEL)
				SERVER.getPlayerList().broadcastSystemMessage(Component.literal("★ " + p.getName().getString()
					+ " достиг " + d.level + " уровня!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
		}
		if (d.level >= MAX_LEVEL) d.xp = 0;
		if (leveled) {
			p.sendSystemMessage(Component.literal("⬆ Уровень " + d.level + "! Очков навыков: " + d.points + " - жми /skills")
				.withStyle(ChatFormatting.YELLOW));
			if (d.level >= MAX_LEVEL)
				p.sendSystemMessage(Component.literal("Ты достиг максимума. Теперь Эндер-дракон уязвим для тебя!")
					.withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
			p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1f);
			updateLabel(p);
		}
		sync(p, false);
	}

	static void onDragonKilled(LivingEntity dragon) {
		SERVER.getPlayerList().broadcastSystemMessage(Component.literal("🐉 ЭНДЕР-ДРАКОН ПОВЕРЖЕН! Вы - легенды!")
			.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
		for (ServerPlayer p : SERVER.getPlayerList().getPlayers()) {
			if (p.level() != dragon.level()) continue;
			give(p, new ItemStack(Items.NETHERITE_INGOT, 8));
			p.level().playSound(null, p.blockPosition(), SoundEvents.ENDER_DRAGON_DEATH, SoundSource.PLAYERS, 1f, 1f);
		}
	}

	static void refresh(ServerPlayer p) { applyModifiers(p); updateLabel(p); sync(p, false); }

	static String encodeRanks(PlayerData d) {
		StringBuilder sb = new StringBuilder();
		for (var e : d.ranks.entrySet()) if (e.getValue() > 0) sb.append(e.getKey()).append(':').append(e.getValue()).append(';');
		return sb.toString();
	}

	static void sync(ServerPlayer p, boolean open) {
		if (!ServerPlayNetworking.canSend(p, Payloads.Sync.TYPE)) return;
		PlayerData d = data(p);
		ServerPlayNetworking.send(p, new Payloads.Sync(d.level, d.xp, d.points, encodeRanks(d), open));
	}

	static void applyModifiers(ServerPlayer p) {
		PlayerData d = data(p);
		for (Skill s : SKILLS) {
			if (s.attr() == null) continue;
			AttributeInstance inst = p.getAttribute(s.attr());
			if (inst == null) continue;
			Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, "skill_" + s.id());
			inst.removeModifier(id);
			int r = d.rank(s.id());
			if (r > 0) inst.addPermanentModifier(new AttributeModifier(id, s.perRank() * r, s.op()));
		}
		if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
	}

	static void updateLabel(ServerPlayer p) {
		run(SERVER, "scoreboard players set " + p.getScoreboardName() + " rpg_lvl " + data(p).level);
	}

	static void run(MinecraftServer s, String cmd) {
		s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), cmd);
	}

	static boolean unlocked(PlayerData d, Skill s) { return s.req() == null || d.rank(s.req()) >= s.reqRank(); }

	static void doUpgrade(ServerPlayer p, String id) {
		PlayerData d = data(p);
		Skill s = SKILL_BY_ID.get(id);
		if (s == null || d.points <= 0 || d.rank(id) >= s.max() || !unlocked(d, s)) {
			p.sendSystemMessage(Component.literal("Нельзя улучшить этот навык сейчас").withStyle(ChatFormatting.RED));
			return;
		}
		d.points--;
		d.ranks.merge(id, 1, Integer::sum);
		applyModifiers(p);
		p.level().playSound(null, p.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1f, 1.2f);
	}

	static void doReset(ServerPlayer p) {
		PlayerData d = data(p);
		d.ranks.clear();
		d.points = d.level - 1 + d.bonus;
		applyModifiers(p);
		p.sendSystemMessage(Component.literal("Навыки сброшены, очки возвращены.").withStyle(ChatFormatting.YELLOW));
	}

	/** Запасной вариант для игроков без клиентского мода: дерево в чате. */
	static void showTree(ServerPlayer p) {
		PlayerData d = data(p);
		p.sendSystemMessage(Component.literal("══ Древо навыков ══  Ур. " + d.level + "  Очки: " + d.points)
			.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		String last = "";
		for (Skill s : SKILLS) {
			if (!s.branch().equals(last)) {
				p.sendSystemMessage(Component.literal("▶ " + s.branch()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
				last = s.branch();
			}
			int r = d.rank(s.id());
			boolean ok = unlocked(d, s);
			boolean can = ok && d.points > 0 && r < s.max();
			Component line = Component.literal(can ? " [+] " : (ok ? " [ ] " : " [x] "))
				.withStyle(can ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)
				.append(Component.literal(s.name() + " " + r + "/" + s.max()).withStyle(r > 0 ? ChatFormatting.YELLOW : ChatFormatting.GRAY))
				.append(Component.literal(" - " + s.desc()).withStyle(ChatFormatting.DARK_GRAY));
			if (can) line = line.copy().withStyle(st -> st
				.withClickEvent(new ClickEvent.RunCommand("/skill up " + s.id()))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Нажми, чтобы улучшить"))));
			p.sendSystemMessage(line);
		}
	}
}
