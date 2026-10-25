package com.hackerclient.client;

import com.hackerclient.client.command.DotCommands;
import com.hackerclient.client.module.ModuleManager;
import com.hackerclient.client.module.impl.BaseFinder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class HackerClientInit implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModuleManager.register(new BaseFinder());

		DotCommands.init();
		ClientTickEvents.END_CLIENT_TICK.register(ModuleManager::tick);
	}
}
