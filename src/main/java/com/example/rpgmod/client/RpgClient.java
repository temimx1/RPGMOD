package com.example.rpgmod.client;

import com.example.rpgmod.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import java.util.HashMap;
import java.util.Map;

public class RpgClient implements ClientModInitializer {
	public static int level = 1, xp = 0, points = 0;
	public static Map<String, Integer> ranks = new HashMap<>();

	/** Открывает экран. Метод setScreen в 26.x переехал, поэтому ищем его сами. */
	static void openScreen(Minecraft mc, Screen screen) {
		try {
			Method m = findSetScreen(mc.getClass());
			if (m != null) { m.invoke(mc, screen); return; }
			for (Field f : mc.getClass().getDeclaredFields()) {
				try {
					f.setAccessible(true);
					Object o = f.get(mc);
					if (o == null) continue;
					Method m2 = findSetScreen(o.getClass());
					if (m2 != null) { m2.invoke(o, screen); return; }
				} catch (Exception ignored) {}
			}
			System.err.println("[rpgmod] setScreen not found");
		} catch (Exception e) { System.err.println("[rpgmod] openScreen failed: " + e); }
	}

	static Method findSetScreen(Class<?> c) {
		for (Method m : c.getMethods())
			if (m.getName().equals("setScreen") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(Screen.class))
				return m;
		return null;
	}

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Sync.TYPE, (payload, ctx) -> {
			level = payload.level();
			xp = payload.xp();
			points = payload.points();
			Map<String, Integer> m = new HashMap<>();
			for (String part : payload.ranks().split(";")) {
				String[] kv = part.split(":");
				if (kv.length == 2) m.put(kv[0], Integer.parseInt(kv[1]));
			}
			ranks = m;
			Minecraft mc = ctx.client();
			if (payload.open()) openScreen(mc, new SkillScreen());
			else if (SkillScreen.OPEN != null) SkillScreen.OPEN.refresh();
		});
	}
}
