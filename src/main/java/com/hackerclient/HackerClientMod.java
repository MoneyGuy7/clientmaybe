package com.hackerclient;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HackerClientMod implements ModInitializer {
	public static final String MOD_ID = "hackerclient";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Hacker Client loaded");
	}
}
