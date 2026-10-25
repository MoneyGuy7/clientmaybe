package com.hackerclient.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class ChatUtil {
	private ChatUtil() {}

	public static final String PREFIX = "\u00a78[\u00a7aHC\u00a78]\u00a7r ";

	/** Prints a client-side-only chat line (never sent to the server). */
	public static void info(String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal(PREFIX + message));
		}
	}
}
