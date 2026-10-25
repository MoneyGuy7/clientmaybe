package com.hackerclient.client.module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public abstract class Module {
	private final String name;
	private final String description;
	private boolean enabled;

	protected Module(String name, String description) {
		this.name = name;
		this.description = description;
	}

	public String getName() { return name; }
	public String getDescription() { return description; }
	public boolean isEnabled() { return enabled; }

	public void setEnabled(boolean enabled) {
		if (this.enabled == enabled) return;
		this.enabled = enabled;
		if (enabled) onEnable(); else onDisable();
	}

	public void toggle() { setEnabled(!enabled); }

	/** Called once per client tick while enabled. */
	public void onTick(Minecraft mc) {}
	/** Called every client tick even when disabled (e.g. to reset state on disconnect). */
	public void onAlwaysTick(Minecraft mc) {}
	/** Draw 2D overlays (ESP markers etc.) every frame while enabled. */
	public void onRenderHud(GuiGraphics graphics) {}
	/** Handle a dot command for this module, e.g. ".bf list". Return true if handled. */
	public boolean onCommand(String[] args) { return false; }

	protected void onEnable() {}
	protected void onDisable() {}
}
