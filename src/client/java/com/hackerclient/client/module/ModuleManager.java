package com.hackerclient.client.module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModuleManager {
	private static final List<Module> MODULES = new ArrayList<>();

	private ModuleManager() {}

	public static void register(Module module) { MODULES.add(module); }

	public static List<Module> all() { return Collections.unmodifiableList(MODULES); }

	public static Module get(String name) {
		for (Module m : MODULES) {
			if (m.getName().equalsIgnoreCase(name)) return m;
		}
		return null;
	}

	public static void tick(Minecraft mc) {
		for (Module m : MODULES) {
			m.onAlwaysTick(mc);
			if (m.isEnabled()) m.onTick(mc);
		}
	}

	public static void renderHud(GuiGraphics graphics) {
		for (Module m : MODULES) {
			if (m.isEnabled()) m.onRenderHud(graphics);
		}
	}
}
