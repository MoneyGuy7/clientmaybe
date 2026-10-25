package com.hackerclient.client.command;

import com.hackerclient.client.module.Module;
import com.hackerclient.client.module.ModuleManager;
import com.hackerclient.client.util.ChatUtil;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

import java.util.Arrays;

/**
 * Hacked-client style commands typed in chat with a "." prefix. They are swallowed
 * client-side and never sent to the server.
 *
 *   .help
 *   .modules
 *   .toggle <module>
 *   .<module> ...        (module specific, e.g. ".basefinder list")
 */
public final class DotCommands {
	private DotCommands() {}

	public static void init() {
		ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
			if (!message.startsWith(".") || message.startsWith("..")) return true;
			handle(message.substring(1).trim());
			return false;
		});
	}

	private static void handle(String line) {
		if (line.isEmpty()) { help(); return; }
		String[] parts = line.split("\\s+");
		String cmd = parts[0].toLowerCase();
		String[] rest = Arrays.copyOfRange(parts, 1, parts.length);

		switch (cmd) {
			case "help" -> help();
			case "modules" -> {
				for (Module m : ModuleManager.all()) {
					ChatUtil.info((m.isEnabled() ? "\u00a7a[ON] " : "\u00a7c[OFF] ") + "\u00a7f" + m.getName()
							+ " \u00a77- " + m.getDescription());
				}
			}
			case "toggle", "t" -> {
				if (rest.length < 1) { ChatUtil.info("Usage: .toggle <module>"); return; }
				Module m = ModuleManager.get(rest[0]);
				if (m == null) { ChatUtil.info("\u00a7cNo module named " + rest[0]); return; }
				m.toggle();
				ChatUtil.info(m.getName() + (m.isEnabled() ? " \u00a7aenabled" : " \u00a7cdisabled"));
			}
			default -> {
				Module m = ModuleManager.get(cmd);
				if (m == null || !m.onCommand(rest)) {
					ChatUtil.info("\u00a7cUnknown command. Try .help");
				}
			}
		}
	}

	private static void help() {
		ChatUtil.info("\u00a7f.modules \u00a77- list modules");
		ChatUtil.info("\u00a7f.toggle <module> \u00a77- turn a module on/off");
		ChatUtil.info("\u00a7f.basefinder help \u00a77- BaseFinder commands (alias: .bf)");
	}
}
