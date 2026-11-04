package com.example.rpgmod.client;

import com.example.rpgmod.Payloads;
import com.example.rpgmod.RpgMod;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Окно древа навыков: ряд на каждую ветку, кнопка на каждый навык. */
public class SkillScreen extends Screen {
	public SkillScreen() { super(Component.literal("Древо навыков")); }

	public static SkillScreen OPEN;

	public void refresh() { rebuildWidgets(); }

	private void centered(int y, Component c) {
		int w = font.width(c);
		addRenderableWidget(new StringWidget((width - w) / 2, y, w, 10, c, font));
	}

	@Override
	public void onClose() {
		OPEN = null;
		super.onClose();
	}

	@Override
	protected void init() {
		int level = RpgClient.level, points = RpgClient.points;
		String xpPart = level >= RpgMod.MAX_LEVEL ? "MAX" : RpgClient.xp + "/" + RpgMod.xpNeeded(level);
		OPEN = this;
		centered(12, Component.literal("Уровень " + level + "    XP " + xpPart + "    Очки навыков: " + points)
			.withStyle(points > 0 ? ChatFormatting.GOLD : ChatFormatting.GREEN, ChatFormatting.BOLD));
		centered(26, Component.literal("Наведи на навык, чтобы увидеть описание. Нажми, чтобы улучшить.")
			.withStyle(ChatFormatting.GRAY));

		Map<String, List<RpgMod.Skill>> branches = new LinkedHashMap<>();
		for (RpgMod.Skill s : RpgMod.SKILLS) branches.computeIfAbsent(s.branch(), k -> new ArrayList<>()).add(s);

		int labelW = 60;
		int btnW = Math.max(70, Math.min(130, (width - labelW - 30) / 4));
		int startX = (width - (labelW + 4 * (btnW + 4))) / 2;
		int row = 0;
		for (Map.Entry<String, List<RpgMod.Skill>> e : branches.entrySet()) {
			int y = 46 + row * 26;
			addRenderableWidget(new StringWidget(startX, y + 6, labelW, 10,
				Component.literal(e.getKey()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), font));
			int col = 0;
			for (RpgMod.Skill s : e.getValue()) {
				int r = RpgClient.ranks.getOrDefault(s.id(), 0);
				boolean unlocked = s.req() == null || RpgClient.ranks.getOrDefault(s.req(), 0) >= s.reqRank();
				boolean can = unlocked && points > 0 && r < s.max();
				ChatFormatting color = r >= s.max() ? ChatFormatting.GREEN : (r > 0 ? ChatFormatting.YELLOW : ChatFormatting.WHITE);
				Button b = Button.builder(Component.literal(s.name() + " " + r + "/" + s.max()).withStyle(color),
						btn -> ClientPlayNetworking.send(new Payloads.Upgrade(s.id())))
					.bounds(startX + labelW + col * (btnW + 4), y, btnW, 20).build();
				b.active = can;
				Component tip = Component.literal(s.name() + " (" + r + "/" + s.max() + ")\n" + s.desc()
					+ (s.req() != null ? "\nНужно: " + RpgMod.SKILL_BY_ID.get(s.req()).name() + " " + s.reqRank()
						+ (unlocked ? " ✔" : " ✘") : ""));
				b.setTooltip(Tooltip.create(tip));
				addRenderableWidget(b);
				col++;
			}
			row++;
		}

		int by = Math.min(height - 28, 46 + row * 26 + 8);
		addRenderableWidget(Button.builder(Component.literal("Сбросить навыки"),
			btn -> ClientPlayNetworking.send(new Payloads.Upgrade("__reset")))
			.bounds(width / 2 - 105, by, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Закрыть"), btn -> onClose())
			.bounds(width / 2 + 5, by, 100, 20).build());
	}

	@Override
	public boolean isPauseScreen() { return false; }
}
