package com.hackerclient.client.module.impl;

import com.hackerclient.client.module.Module;
import com.hackerclient.client.util.ChatUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * BaseFinder: scans loaded chunks (full height, down through deepslate to the bottom of the world)
 * for signs of player activity and highlights them on screen.
 *
 * Categories (each can be toggled, and each has a per-chunk threshold):
 *  - lights     man-placed light: torches, lanterns, lit lamps, campfires, glowstone, froglights...
 *  - amethyst   grown amethyst: buds/clusters/budding blocks next to player-made blocks
 *  - storage    chests, trapped chests, barrels
 *  - valuables  ender chests, shulker boxes, beacons, enchanting tables, anvils, netherite/diamond/emerald blocks...
 *  - machines   hoppers, droppers, dispensers, observers, pistons, comparators, repeaters
 *  - builds     concrete, glazed terracotta, glass, signs, tnt, jukeboxes, note blocks, bricks, quartz...
 *  - living     beds, crafting tables, furnaces, brewing stands, smithing tables, lecterns...
 *  - farms      farmland, composters, beehives
 *  - portals    lit nether portals
 *  - frames     item frames and armor stands (entities)
 *
 * A chunk with 3+ different flagged categories is called out as a LIKELY BASE.
 *
 * Results are dropped as soon as their chunk is no longer loaded or is out of range, so after you
 * teleport away nothing from the old area stays on your screen.
 */
public class BaseFinder extends Module {

	private enum Cat {
		//   key        label                     colour    rgb       min  bonus-near-natural-structure
		LIGHT("lights", "Man-placed light", "\u00a7e", 0xFFE04A, 3, 6),
		AMETHYST("amethyst", "Grown amethyst", "\u00a7d", 0xD05CFF, 1, 0),
		STORAGE("storage", "Storage", "\u00a76", 0xFF9A3C, 3, 4),
		VALUABLE("valuables", "Valuable blocks", "\u00a7b", 0x4AE0FF, 1, 0),
		MACHINE("machines", "Redstone machines", "\u00a7c", 0xFF5050, 6, 3),
		BUILD("builds", "Player building", "\u00a7a", 0x7CFF7C, 12, 0),
		LIVING("living", "Beds & workstations", "\u00a79", 0x6C8CFF, 2, 4),
		FARM("farms", "Farmland & farms", "\u00a72", 0x3CB043, 4, 8),
		PORTAL("portals", "Nether portal", "\u00a75", 0xB04CFF, 1, 0),
		ENTITY("frames", "Item frames / armor stands", "\u00a73", 0x40E0D0, 2, 0),
		CROP("autofarms", "Automated farm (kelp/cane/crops...)", "\u00a7a", 0xC8F032, 4, 6),
		STASH("stash", "Stash link (chest-hopper-chest)", "\u00a7d", 0xFF2D95, 1, 0);

		final String key;
		final String label;
		final String code;
		final int rgb;
		final int bonus;  // extra needed when the chunk also holds a natural structure (village, stronghold...)
		int min;          // how many are needed in one chunk before it is flagged
		boolean on = true;

		Cat(String key, String label, String code, int rgb, int min, int bonus) {
			this.key = key;
			this.label = label;
			this.code = code;
			this.rgb = rgb;
			this.min = min;
			this.bonus = bonus;
		}

		static Cat byKey(String k) {
			for (Cat c : values()) if (c.key.equalsIgnoreCase(k)) return c;
			return null;
		}
	}

	/** What a block means to us. marker: 0 none, 1 ancient city, 2 other natural structure. */
	private static final class Info {
		final Cat cat;
		final boolean litOnly;
		final boolean prox;
		final boolean soft;
		final int marker;

		Info(Cat cat, boolean litOnly, boolean prox, boolean soft, int marker) {
			this.cat = cat;
			this.litOnly = litOnly;
			this.prox = prox;
			this.soft = soft;
			this.marker = marker;
		}
	}

	private static final Info NONE = new Info(null, false, false, false, 0);

	private static final class ChunkResult {
		final EnumMap<Cat, List<int[]>> pos = new EnumMap<>(Cat.class);
		boolean struct;
	}

	// ---- settings ----
	private int radiusChunks = 10;
	private boolean esp = true;

	// ---- tuning ----
	private static final int SCAN_BUDGET_NANOS = 3_000_000; // max time spent scanning per tick (about 3 ms)
	private static final int REFILL_TICKS = 5;              // rebuild the scan queue every 0.25 s
	private static final int PRUNE_TICKS = 10;
	private static final int ENTITY_TICKS = 10;
	private static final int NEAR_CHUNKS = 4;               // chunks this close are rescanned more often
	private static final int RESCAN_NEAR_TICKS = 100;       // 5 s
	private static final int RESCAN_FAR_TICKS = 400;        // 20 s
	private static final int AMETHYST_PROXIMITY = 4;
	private static final int FARM_PROXIMITY = 6;   // how close a hopper/observer/piston must be to a plant
	private static final int LIST_CAP = 512;
	private static final int LIKELY_BASE_CATS = 3;

	// ---- state ----
	private final Map<Long, ChunkResult> results = new HashMap<>();
	private final Map<Long, Integer> lastScan = new HashMap<>();
	private final Map<Long, Integer> alertMask = new HashMap<>();
	private final ArrayDeque<int[]> queue = new ArrayDeque<>();
	private final Map<Block, Info> infoCache = new IdentityHashMap<>();
	private Map<Long, List<int[]>> entityPos = new HashMap<>();
	private Level lastLevel;
	private int tick;
	private boolean chunkArrived;
	private final java.util.Set<Long> trialChunks = new java.util.HashSet<>();
	private final List<ChunkResult> testResults = new ArrayList<>();
	private int testUntil;
	private boolean log = true;
	private boolean badge = true;
	private int baseFlashUntil;

	public BaseFinder() {
		super("BaseFinder", "Finds player bases (light, amethyst, storage, machines, builds, farms, portals...) and highlights them", "bf");
		// New chunk from the server: rebuild the queue next tick so it is scanned right away instead of waiting.
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> chunkArrived = true);
	}

	@Override
	protected void onEnable() {
		ChatUtil.info("BaseFinder \u00a7aon\u00a7r - type .bf help for commands");
	}

	@Override
	protected void onDisable() {
		queue.clear();
	}

	@Override
	public void onAlwaysTick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			if (lastLevel != null) reset();
			return;
		}
		if (mc.level != lastLevel) {
			reset();
			lastLevel = mc.level;
		}
	}

	@Override
	public void onTick(Minecraft mc) {
		if (mc.level == null || mc.player == null) return;
		tick++;

		if (tick % PRUNE_TICKS == 0) prune(mc);
		if (tick % ENTITY_TICKS == 0) scanEntities(mc);

		// The queue is rebuilt (nearest + never-scanned first) every few ticks, or immediately when a chunk
		// arrives, so it can never go stale after you move and never has to drain before new chunks are seen.
		if (chunkArrived || tick % REFILL_TICKS == 0) {
			chunkArrived = false;
			refillQueue(mc);
		}

		// Scan by time budget instead of a fixed chunk count: fast when there is a lot to do, cheap when idle.
		long start = System.nanoTime();
		int done = 0;
		while (!queue.isEmpty()) {
			if (done >= 1 && System.nanoTime() - start > SCAN_BUDGET_NANOS) break;
			int[] c = queue.poll();
			scanChunk(mc, c[0], c[1]);
			done++;
		}
	}

	// ------------------------------------------------------------------ scanning

	private static long chunkKey(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	private void reset() {
		results.clear();
		lastScan.clear();
		alertMask.clear();
		queue.clear();
		infoCache.clear();
		entityPos = new HashMap<>();
		trialChunks.clear();
		testResults.clear();
		chunkArrived = false;
		lastLevel = null;
	}

	/** Forget anything whose chunk is unloaded or out of range (e.g. after a teleport). */
	private void prune(Minecraft mc) {
		int pcx = mc.player.blockPosition().getX() >> 4;
		int pcz = mc.player.blockPosition().getZ() >> 4;
		int lim = (radiusChunks + 2) * (radiusChunks + 2);
		results.keySet().removeIf(k -> stale(mc, k, pcx, pcz, lim));
		lastScan.keySet().removeIf(k -> stale(mc, k, pcx, pcz, lim));
		alertMask.keySet().removeIf(k -> !results.containsKey(k));
		trialChunks.removeIf(k -> stale(mc, k, pcx, pcz, lim));
		if (!testResults.isEmpty() && tick > testUntil) { testResults.clear(); ChatUtil.info("Test markers expired."); }
	}

	private static boolean stale(Minecraft mc, long key, int pcx, int pcz, int lim) {
		int cx = (int) (key >> 32);
		int cz = (int) key;
		int dx = cx - pcx, dz = cz - pcz;
		if ((long) dx * dx + (long) dz * dz > lim) return true;
		return mc.level.getChunkSource().getChunkNow(cx, cz) == null;
	}

	private void refillQueue(Minecraft mc) {
		int pcx = mc.player.blockPosition().getX() >> 4;
		int pcz = mc.player.blockPosition().getZ() >> 4;
		List<int[]> todo = new ArrayList<>();
		for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
			for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
				int dist2 = dx * dx + dz * dz;
				if (dist2 > radiusChunks * radiusChunks) continue;
				int cx = pcx + dx, cz = pcz + dz;
				if (mc.level.getChunkSource().getChunkNow(cx, cz) == null) continue;
				Integer last = lastScan.get(chunkKey(cx, cz));
				if (last != null) {
					int every = dist2 <= NEAR_CHUNKS * NEAR_CHUNKS ? RESCAN_NEAR_TICKS : RESCAN_FAR_TICKS;
					if (tick - last < every) continue;
				}
				// chunks we have never looked at go first, then nearest first
				todo.add(new int[] { (last == null ? -100000 : 0) + dist2, cx, cz });
			}
		}
		todo.sort((a, b) -> Integer.compare(a[0], b[0]));
		queue.clear();
		for (int[] t : todo) queue.add(new int[] { t[1], t[2] });
	}

	private void scanChunk(Minecraft mc, int cx, int cz) {
		LevelChunk chunk = mc.level.getChunkSource().getChunkNow(cx, cz);
		if (chunk == null) return;

		long key = chunkKey(cx, cz);
		lastScan.put(key, tick);

		boolean nether = mc.level.dimension() == Level.NETHER;
		boolean end = mc.level.dimension() == Level.END;

		LevelChunkSection[] sections = chunk.getSections();
		int minY = chunk.getMinY(); // negative in the overworld, so deepslate is included

		EnumMap<Cat, List<int[]>> found = new EnumMap<>(Cat.class);
		List<int[]> softLights = new ArrayList<>();
		List<int[]> prox = new ArrayList<>();
		List<int[]> hoppers = new ArrayList<>();
		List<int[]> infra = new ArrayList<>(); // hoppers, observers, pistons, dispensers... (not farmland: villages have it)
		boolean city = false;
		boolean trial = false;
		boolean struct = false;

		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			if (s == null || s.hasOnlyAir()) continue;
			if (!s.maybeHas(st -> infoOf(st.getBlock(), nether, end) != NONE)) continue;

			int baseX = cx << 4, baseY = minY + (i << 4), baseZ = cz << 4;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState st = s.getBlockState(x, y, z);
						Info info = infoOf(st.getBlock(), nether, end);
						if (info == NONE) continue;

						if (info.marker == 1) city = true;
						else if (info.marker == 3) trial = true;
						else if (info.marker == 2) struct = true;

						if (info.litOnly && !isLit(st)) continue;

						int[] pos = { baseX + x, baseY + y, baseZ + z };
						if (info.marker == 4 && hoppers.size() < LIST_CAP * 2) hoppers.add(pos);
						if (info.prox && prox.size() < LIST_CAP * 2) prox.add(pos);
						if (info.cat == Cat.MACHINE && infra.size() < LIST_CAP * 2) infra.add(pos);
						if (info.cat == null) continue;

						if (info.soft) {
							if (softLights.size() < LIST_CAP) softLights.add(pos);
						} else {
							List<int[]> l = found.computeIfAbsent(info.cat, k -> new ArrayList<>());
							if (l.size() < LIST_CAP) l.add(pos);
						}
					}
				}
			}
		}

		// Trial chambers (copper bulbs, copper lanterns/torches...) are natural too, and a chamber spans several
		// chunks, so also treat chunks next to a known trial-chamber chunk as part of it.
		if (trial) trialChunks.add(key);
		boolean nearTrial = trial;
		for (int ox = -1; ox <= 1 && !nearTrial; ox++) {
			for (int oz = -1; oz <= 1; oz++) {
				if (trialChunks.contains(chunkKey(cx + ox, cz + oz))) { nearTrial = true; break; }
			}
		}

		// Soul lanterns / soul torches / candles / copper lights fill ancient cities and trial chambers,
		// so only count them outside one.
		if (!city && !nearTrial && !softLights.isEmpty()) {
			List<int[]> l = found.computeIfAbsent(Cat.LIGHT, k -> new ArrayList<>());
			for (int[] p : softLights) if (l.size() < LIST_CAP) l.add(p);
		}

		List<int[]> ents = entityPos.get(key);
		if (ents != null && !ents.isEmpty()) found.put(Cat.ENTITY, ents);

		// Amethyst only counts when a player-made block is right next to it.
		List<int[]> candidates = found.remove(Cat.AMETHYST);
		if (candidates != null) {
			List<int[]> hits = new ArrayList<>();
			for (int[] a : candidates) if (nearAny(a, prox)) hits.add(a);
			if (!hits.isEmpty()) found.put(Cat.AMETHYST, hits);
		}

		// Stash links: a hopper touching two or more chests/barrels (chest -> hopper -> chest, stacked or side by side).
		if (!hoppers.isEmpty()) {
			List<int[]> links = new ArrayList<>();
			BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
			for (int[] h : hoppers) {
				int n = 0;
				for (Direction d : Direction.values()) {
					mp.set(h[0] + d.getStepX(), h[1] + d.getStepY(), h[2] + d.getStepZ());
					if (isContainer(mc.level.getBlockState(mp))) n++;
				}
				if (n >= 2) links.add(h);
			}
			if (!links.isEmpty()) found.put(Cat.STASH, links);
		}

		// Kelp, sugar cane, bamboo, cactus, crops, saplings... only count when farm machinery is right next to them,
		// which is what separates an automated farm (observers, pistons, hoppers, dispensers for bone meal)
		// from natural oceans, deserts, jungles and villages.
		List<int[]> plants = found.remove(Cat.CROP);
		if (plants != null) {
			List<int[]> hits = new ArrayList<>();
			for (int[] a : plants) if (nearAny(a, infra, FARM_PROXIMITY)) hits.add(a);
			if (!hits.isEmpty()) found.put(Cat.CROP, hits);
		}

		if (found.isEmpty()) {
			results.remove(key);
			alertMask.remove(key);
			return;
		}

		ChunkResult r = new ChunkResult();
		r.pos.putAll(found);
		r.struct = struct || city || trial;
		results.put(key, r);
		reflag(mc, key, r);
	}

	/** Re-evaluate which categories a chunk trips and announce anything newly flagged. */
	private void reflag(Minecraft mc, long key, ChunkResult r) {
		int oldMask = alertMask.getOrDefault(key, 0);
		int newMask = 0;
		for (Cat c : Cat.values()) {
			if (catFlagged(r, c)) {
				newMask |= 1 << c.ordinal();
				if ((oldMask & (1 << c.ordinal())) == 0) announce(mc, r, c);
			}
		}
		if (Integer.bitCount(newMask) >= LIKELY_BASE_CATS && Integer.bitCount(oldMask) < LIKELY_BASE_CATS) {
			int[] a = anchor(r);
			String where = a == null ? "?" : fmt(mc, a);
			baseFlashUntil = tick + 100;
			ChatUtil.info("\u00a74\u00a7lLIKELY BASE \u00a7r\u00a77(" + Integer.bitCount(newMask) + " signs) at " + where);
			logLine("LIKELY BASE (" + Integer.bitCount(newMask) + " signs)", a);
		}
		if (newMask == 0) alertMask.remove(key); else alertMask.put(key, newMask);
	}

	private static boolean likelyBase(ChunkResult r) {
		int n = 0;
		for (Cat c : Cat.values()) if (catFlagged(r, c)) n++;
		return n >= LIKELY_BASE_CATS;
	}

	// ------------------------------------------------------------------ entities (item frames, armor stands)

	private void scanEntities(Minecraft mc) {
		Map<Long, List<int[]>> m = new HashMap<>();
		if (mc.level.dimension() != Level.END) {
			double r = radiusChunks * 16.0;
			AABB box = mc.player.getBoundingBox().inflate(r, 400, r);
			for (Entity e : mc.level.getEntities((Entity) null, box)) {
				if (!(e instanceof ItemFrame) && !(e instanceof ArmorStand)) continue;
				int x = e.blockPosition().getX(), y = e.blockPosition().getY(), z = e.blockPosition().getZ();
				List<int[]> l = m.computeIfAbsent(chunkKey(x >> 4, z >> 4), k -> new ArrayList<>());
				if (l.size() < LIST_CAP) l.add(new int[] { x, y, z });
			}
		}
		entityPos = m;

		// fold into the per-chunk results
		for (ChunkResult r : results.values()) r.pos.remove(Cat.ENTITY);
		for (Map.Entry<Long, List<int[]>> en : m.entrySet()) {
			results.computeIfAbsent(en.getKey(), k -> new ChunkResult()).pos.put(Cat.ENTITY, en.getValue());
		}
		results.entrySet().removeIf(en -> en.getValue().pos.isEmpty());
		alertMask.keySet().removeIf(k -> !results.containsKey(k));
		for (Map.Entry<Long, ChunkResult> en : new ArrayList<>(results.entrySet())) {
			reflag(mc, en.getKey(), en.getValue());
		}
	}

	private static boolean nearAny(int[] a, List<int[]> others) {
		return nearAny(a, others, AMETHYST_PROXIMITY);
	}

	private static boolean nearAny(int[] a, List<int[]> others, int r) {
		for (int[] o : others) {
			if (Math.abs(a[0] - o[0]) <= r
					&& Math.abs(a[1] - o[1]) <= r
					&& Math.abs(a[2] - o[2]) <= r) {
				return true;
			}
		}
		return false;
	}

	private static int threshold(ChunkResult r, Cat c) {
		return c.min + (r.struct ? c.bonus : 0);
	}

	private static boolean catFlagged(ChunkResult r, Cat c) {
		List<int[]> l = r.pos.get(c);
		return c.on && l != null && !l.isEmpty() && l.size() >= threshold(r, c);
	}

	private static boolean isFlagged(ChunkResult r) {
		for (Cat c : Cat.values()) if (catFlagged(r, c)) return true;
		return false;
	}

	private static boolean isContainer(BlockState st) {
		return st.is(Blocks.CHEST) || st.is(Blocks.TRAPPED_CHEST) || st.is(Blocks.BARREL);
	}

	private static boolean isLit(BlockState st) {
		return st.hasProperty(BlockStateProperties.LIT) && st.getValue(BlockStateProperties.LIT);
	}

	// ------------------------------------------------------------------ block classification

	private Info infoOf(Block block, boolean nether, boolean end) {
		return infoCache.computeIfAbsent(block, b -> classify(b, nether, end));
	}

	private static Info classify(Block block, boolean nether, boolean end) {
		String p = BuiltInRegistries.BLOCK.getKey(block).getPath();

		// grown amethyst
		switch (p) {
			case "small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud",
					"amethyst_cluster", "budding_amethyst" -> { return new Info(Cat.AMETHYST, false, false, false, 0); }
			default -> {}
		}

		// trial chamber markers: copper grates, chiseled copper, copper doors/trapdoors (incl. waxed/weathered)
		if (p.endsWith("copper_grate") || p.contains("chiseled_copper") || p.endsWith("copper_door")
				|| p.endsWith("copper_trapdoor")) {
			return new Info(null, false, false, false, 3);
		}

		// natural structure markers (used to avoid false positives)
		switch (p) {
			case "trial_spawner", "vault" -> { return new Info(null, false, false, false, 3); }
			case "sculk", "sculk_shrieker", "sculk_catalyst", "sculk_sensor", "calibrated_sculk_sensor",
					"reinforced_deepslate" -> { return new Info(null, false, false, false, 1); }
			case "spawner", "end_portal_frame", "bell" -> { return new Info(null, false, false, false, 2); }
			default -> {}
		}

		// man-placed light
		switch (p) {
			case "torch", "wall_torch", "lantern", "jack_o_lantern" -> {
				return new Info(Cat.LIGHT, false, true, false, 0);
			}
			case "soul_torch", "soul_wall_torch", "soul_lantern" -> {
				return new Info(Cat.LIGHT, false, true, true, 0);
			}
			default -> {}
		}
		// copper lights are all over trial chambers, so they count as "soft" (ignored in/next to one)
		if (p.endsWith("copper_torch") || p.endsWith("copper_wall_torch") || p.endsWith("copper_lantern")) {
			return new Info(Cat.LIGHT, false, true, true, 0);
		}
		if (p.endsWith("copper_bulb")) return new Info(Cat.LIGHT, true, true, true, 0);
		if (p.endsWith("froglight")) return new Info(Cat.LIGHT, false, true, false, 0);
		if (p.equals("glowstone") || p.equals("shroomlight")) {
			return nether ? NONE : new Info(Cat.LIGHT, false, true, false, 0);
		}
		if (p.equals("end_rod")) return end ? NONE : new Info(Cat.LIGHT, false, true, false, 0);
		if (p.equals("redstone_lamp") || p.equals("campfire") || p.equals("soul_campfire")) {
			return new Info(Cat.LIGHT, true, true, false, 0);
		}
		if (p.equals("candle") || p.endsWith("_candle")) return new Info(Cat.LIGHT, true, true, true, 0);

		// plants that farms are made of (only flagged next to farm machinery, see scanChunk)
		switch (p) {
			case "kelp", "kelp_plant", "sugar_cane", "bamboo", "cactus", "melon", "pumpkin", "wheat", "carrots",
					"potatoes", "beetroots", "nether_wart", "sweet_berry_bush", "cocoa", "chorus_plant", "chorus_flower",
					"twisting_vines", "twisting_vines_plant", "weeping_vines", "weeping_vines_plant", "mangrove_propagule" -> {
				return new Info(Cat.CROP, false, false, false, 0);
			}
			default -> {}
		}
		if (p.endsWith("_sapling")) return new Info(Cat.CROP, false, false, false, 0);

		// nether portal (active portal blocks are basically always player-made)
		if (p.equals("nether_portal")) return new Info(Cat.PORTAL, false, true, false, 0);

		// beds and workstations (villages have these too, so a village raises the threshold)
		if (p.endsWith("_bed")) return new Info(Cat.LIVING, false, true, false, 0);
		switch (p) {
			case "crafting_table", "furnace", "blast_furnace", "smoker", "brewing_stand", "smithing_table",
					"grindstone", "stonecutter", "loom", "cartography_table", "fletching_table", "lectern" -> {
				return new Info(Cat.LIVING, false, true, false, 0);
			}
			case "farmland", "composter", "beehive" -> { return new Info(Cat.FARM, false, false, false, 0); }
			default -> {}
		}

		// valuables
		if (p.endsWith("shulker_box")) return end ? NONE : new Info(Cat.VALUABLE, false, true, false, 0);
		switch (p) {
			case "ender_chest", "beacon", "enchanting_table", "respawn_anchor", "conduit", "netherite_block",
					"diamond_block", "emerald_block", "gold_block", "iron_block", "lapis_block", "lodestone", "anvil", "chipped_anvil", "damaged_anvil" -> {
				return new Info(Cat.VALUABLE, false, true, false, 0);
			}
			default -> {}
		}

		// storage
		switch (p) {
			case "chest", "trapped_chest", "barrel" -> { return new Info(Cat.STORAGE, false, true, false, 0); }
			default -> {}
		}

		// redstone machines
		switch (p) {
			case "hopper" -> { return new Info(Cat.MACHINE, false, true, false, 4); }
			case "dropper", "dispenser", "observer", "piston", "sticky_piston", "comparator", "repeater",
					"daylight_detector", "powered_rail", "activator_rail", "detector_rail" -> {
				return new Info(Cat.MACHINE, false, true, false, 0);
			}
			default -> {}
		}

		// player building
		if (p.endsWith("_concrete") || p.endsWith("_concrete_powder") || p.endsWith("glazed_terracotta")
				|| p.endsWith("_stained_glass") || p.endsWith("_stained_glass_pane")
				|| p.equals("glass") || p.equals("tinted_glass") || p.endsWith("_sign")
				|| p.equals("tnt") || p.equals("jukebox") || p.equals("note_block")
				|| p.equals("bricks") || p.equals("smooth_stone") || p.equals("quartz_block") || p.equals("quartz_bricks")
				|| p.equals("quartz_pillar") || p.equals("chiseled_quartz_block") || p.equals("smooth_quartz")) {
			return new Info(Cat.BUILD, false, false, false, 0);
		}

		// other player-made blocks that only matter next to amethyst
		switch (p) {
			case "ladder", "scaffolding", "redstone_wire", "lever", "redstone_block", "slime_block",
					"honey_block", "target", "redstone_torch", "redstone_wall_torch" -> {
				return new Info(null, false, true, false, 0);
			}
			default -> {}
		}
		return NONE;
	}

	// ------------------------------------------------------------------ output

	private void announce(Minecraft mc, ChunkResult r, Cat c) {
		List<int[]> l = r.pos.get(c);
		ChatUtil.info(c.code + c.label + " \u00a7fx" + l.size() + " \u00a77at " + fmt(mc, l.get(0)));
		logLine(c.label + " x" + l.size(), l.get(0));
	}

	/** Appends a finding to .minecraft/config/hackerclient/basefinder-<server>.txt */
	private void logLine(String what, int[] p) {
		if (!log || p == null) return;
		try {
			Minecraft mc = Minecraft.getInstance();
			String server = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "singleplayer";
			server = server.replaceAll("[^A-Za-z0-9._-]", "_");
			Path dir = FabricLoader.getInstance().getConfigDir().resolve("hackerclient");
			Files.createDirectories(dir);
			String dim = mc.level.dimension() == Level.NETHER ? "nether" : mc.level.dimension() == Level.END ? "end" : "overworld";
			String line = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
					+ "  [" + dim + "]  " + what + "  at " + p[0] + ", " + p[1] + ", " + p[2] + "\n";
			Files.writeString(dir.resolve("basefinder-" + server + ".txt"), line,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException | RuntimeException ignored) {
		}
	}

	private static String fmt(Minecraft mc, int[] p) {
		double dx = p[0] + 0.5 - mc.player.getX();
		double dz = p[2] + 0.5 - mc.player.getZ();
		int dist = (int) Math.sqrt(dx * dx + dz * dz);
		return p[0] + ", " + p[1] + ", " + p[2] + " \u00a78(" + dist + "m)";
	}

	// ------------------------------------------------------------------ ESP (on-screen highlights)

	private record Cam(double ex, double ey, double ez,
			double fx, double fy, double fz,
			double rx, double ry, double rz,
			double ux, double uy, double uz,
			double tanHalf, double aspect, int w, int h) {}

	private static int argb(int alpha, int rgb) {
		return ((alpha & 0xFF) << 24) | (rgb & 0xFFFFFF);
	}

	/** Returns {screenX, screenY, depth, visible(1/0)}. When behind the camera, x/y hold camera-space x/y instead. */
	private static double[] project(Cam c, double x, double y, double z) {
		double dx = x - c.ex, dy = y - c.ey, dz = z - c.ez;
		double depth = dx * c.fx + dy * c.fy + dz * c.fz;
		double cx = dx * c.rx + dy * c.ry + dz * c.rz;
		double cy = dx * c.ux + dy * c.uy + dz * c.uz;
		if (depth < 0.05) return new double[] { cx, cy, depth, 0 };
		double nx = cx / (depth * c.tanHalf * c.aspect);
		double ny = cy / (depth * c.tanHalf);
		return new double[] { c.w / 2.0 + nx * c.w / 2.0, c.h / 2.0 - ny * c.h / 2.0, depth, 1 };
	}

	@Override
	public void onRenderHud(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || mc.options.hideGui) return;
		if (badge) drawBadge(g, mc);
		if (!esp || (results.isEmpty() && testResults.isEmpty())) return;

		double yaw = Math.toRadians(mc.player.getYRot());
		double pitch = Math.toRadians(mc.player.getXRot());
		double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
		double rx = -Math.cos(yaw), ry = 0, rz = -Math.sin(yaw);
		double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();
		double tanHalf = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
		Cam cam = new Cam(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(),
				fx, fy, fz, rx, ry, rz, ux, uy, uz, tanHalf, (double) w / h, w, h);

		// {distance, x, y, z, category}
		List<double[]> targets = new ArrayList<>();
		List<ChunkResult> all = new ArrayList<>(results.values());
		all.addAll(testResults);
		for (ChunkResult r : all) {
			for (Cat c : Cat.values()) {
				if (!catFlagged(r, c)) continue;
				for (int[] p : r.pos.get(c)) {
					double dx = p[0] + 0.5 - cam.ex, dy = p[1] + 0.5 - cam.ey, dz = p[2] + 0.5 - cam.ez;
					targets.add(new double[] { Math.sqrt(dx * dx + dy * dy + dz * dz), p[0], p[1], p[2], c.ordinal(), likelyBase(r) ? 1 : 0 });
				}
			}
		}
		targets.sort((a, b) -> Double.compare(a[0], b[0]));

		int count = Math.min(targets.size(), 200);
		for (int i = 0; i < count; i++) {
			drawTarget(g, mc, cam, targets.get(i), i < 30);
		}

		// One always-labelled BASE marker per likely-base chunk, drawn last (on top). These ignore the
		// 30-label / 200-box caps, so they stay visible when you are high up and a lot is in range.
		for (ChunkResult r : all) {
			if (!likelyBase(r)) continue;
			int[] a = anchor(r);
			if (a == null) continue;
			double dx = a[0] + 0.5 - cam.ex, dy = a[1] + 0.5 - cam.ey, dz = a[2] + 0.5 - cam.ez;
			drawTarget(g, mc, cam, new double[] { Math.sqrt(dx * dx + dy * dy + dz * dz), a[0], a[1], a[2], -1, 1 }, true);
		}
	}

	/** Corner badge: red "BASE FOUND" (flashing for 5 s when a new one shows up), grey when nothing is found. */
	private void drawBadge(GuiGraphics g, Minecraft mc) {
		List<ChunkResult> all = new ArrayList<>(results.values());
		all.addAll(testResults);
		int count = 0;
		int[] best = null;
		double bestD = Double.MAX_VALUE;
		for (ChunkResult r : all) {
			if (!likelyBase(r)) continue;
			int[] a = anchor(r);
			if (a == null) continue;
			count++;
			double d = dist2(a, mc.player.getX(), mc.player.getZ());
			if (d < bestD) { bestD = d; best = a; }
		}

		String text;
		int bg, border, fg;
		if (best != null) {
			double dx = best[0] + 0.5 - mc.player.getX(), dz = best[2] + 0.5 - mc.player.getZ();
			text = "BASE FOUND" + (count > 1 ? " x" + count : "") + "  " + (int) Math.sqrt(bestD) + "m " + dirName(dx, dz);
			boolean flash = tick < baseFlashUntil && (tick / 5) % 2 == 0;
			bg = flash ? 0xFFFF2020 : 0xE0A00000;
			border = 0xFFFF6060;
			fg = 0xFFFFFFFF;
		} else {
			text = "BaseFinder: no base";
			bg = 0x70000000;
			border = 0xFF555555;
			fg = 0xFFAAAAAA;
		}

		int w = mc.font.width(text) + 10, h = 14;
		int x = mc.getWindow().getGuiScaledWidth() - w - 4, y = 4;
		g.fill(x, y, x + w, y + h, bg);
		g.fill(x, y, x + w, y + 1, border);
		g.fill(x, y + h - 1, x + w, y + h, border);
		g.fill(x, y, x + 1, y + h, border);
		g.fill(x + w - 1, y, x + w, y + h, border);
		g.drawString(mc.font, text, x + 5, y + 3, fg);
	}

	/** Compass direction for an offset (+x is east, +z is south). */
	private static String dirName(double dx, double dz) {
		String[] names = { "E", "SE", "S", "SW", "W", "NW", "N", "NE" };
		double ang = Math.toDegrees(Math.atan2(dz, dx));
		return names[(int) Math.round(((ang % 360) + 360) % 360 / 45.0) % 8];
	}

	private static void drawTarget(GuiGraphics g, Minecraft mc, Cam c, double[] t, boolean label) {
		double dist = t[0];
		double bx = t[1], by = t[2], bz = t[3];
		int rgb = t[4] < 0 ? 0xFF3030 : Cat.values()[(int) t[4]].rgb;

		// The further away the target, the stronger the highlight: thicker outline, bigger minimum size, more fill.
		double far = Math.max(0, Math.min(1, (dist - 12) / 100.0));
		int thickness = 1 + (int) Math.round(far * 2);
		int minSize = 6 + (int) (far * 10);

		double[] center = project(c, bx + 0.5, by + 0.5, bz + 0.5);

		double minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;
		boolean allFront = true;
		for (int i = 0; i < 8; i++) {
			double[] p = project(c, bx + (i & 1), by + ((i >> 1) & 1), bz + ((i >> 2) & 1));
			if (p[3] == 0) { allFront = false; break; }
			minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
			minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
		}
		if (!allFront && center[3] == 1) {
			minX = maxX = center[0];
			minY = maxY = center[1];
			allFront = true;
		}

		String text = (t[5] == 1 ? "BASE  " : "") + (int) dist + "m  y" + (int) by;

		if (allFront && maxX >= 0 && minX <= c.w && maxY >= 0 && minY <= c.h) {
			int x1 = (int) Math.floor(minX), x2 = (int) Math.ceil(maxX);
			int y1 = (int) Math.floor(minY), y2 = (int) Math.ceil(maxY);
			int cxp = (x1 + x2) / 2, cyp = (y1 + y2) / 2;
			if (x2 - x1 < minSize) { x1 = cxp - minSize / 2; x2 = x1 + minSize; }
			if (y2 - y1 < minSize) { y1 = cyp - minSize / 2; y2 = y1 + minSize; }

			g.fill(x1, y1, x2, y2, argb(0x28 + (int) (far * 0x60), rgb));
			g.fill(x1, y1, x2, y1 + thickness, argb(0xFF, rgb));
			g.fill(x1, y2 - thickness, x2, y2, argb(0xFF, rgb));
			g.fill(x1, y1, x1 + thickness, y2, argb(0xFF, rgb));
			g.fill(x2 - thickness, y1, x2, y2, argb(0xFF, rgb));
			if (label) drawLabel(g, mc, text, (x1 + x2) / 2, Math.max(1, y1 - 10), argb(0xFF, rgb));
			return;
		}

		// Off-screen (or behind you): pin a marker to the screen edge in that direction.
		double dx, dy;
		if (center[3] == 1) { dx = center[0] - c.w / 2.0; dy = center[1] - c.h / 2.0; }
		else { dx = center[0]; dy = -center[1]; }
		double len = Math.sqrt(dx * dx + dy * dy);
		if (len < 1e-4) { dx = 0; dy = 1; len = 1; }
		dx /= len; dy /= len;

		int margin = 14;
		double hx = c.w / 2.0 - margin, hy = c.h / 2.0 - margin;
		double sx = Math.abs(dx) < 1e-6 ? 1e9 : hx / Math.abs(dx);
		double sy = Math.abs(dy) < 1e-6 ? 1e9 : hy / Math.abs(dy);
		double s = Math.min(sx, sy);
		int px = (int) (c.w / 2.0 + dx * s), py = (int) (c.h / 2.0 + dy * s);
		int size = 6 + (int) (far * 6);
		g.fill(px - size / 2, py - size / 2, px + size / 2, py + size / 2, argb(0xFF, rgb));
		if (label) drawLabel(g, mc, text, px, Math.max(1, py - size / 2 - 10), argb(0xFF, rgb));
	}

	private static void drawLabel(GuiGraphics g, Minecraft mc, String text, int centerX, int y, int color) {
		g.drawString(mc.font, text, centerX - mc.font.width(text) / 2, y, color);
	}

	// ------------------------------------------------------------------ commands

	@Override
	public boolean onCommand(String[] args) {
		Minecraft mc = Minecraft.getInstance();
		String sub = args.length == 0 ? "help" : args[0].toLowerCase();

		switch (sub) {
			case "help" -> {
				ChatUtil.info("\u00a7f.bf list \u00a77- flagged spots, nearest first");
				ChatUtil.info("\u00a7f.bf test \u00a77- drop fake markers in front of you to check the ESP (30 s) | \u00a7f.bf test clear");
				ChatUtil.info("\u00a7f.bf badge on|off \u00a77- corner \"BASE FOUND\" indicator");
				ChatUtil.info("\u00a7f.bf nearest \u00a77- direction + distance to the closest flagged spot");
				ChatUtil.info("\u00a7f.bf log on|off \u00a77- save finds to config/hackerclient/");
				ChatUtil.info("\u00a7f.bf status \u00a77/ \u00a7f.bf clear");
				ChatUtil.info("\u00a7f.bf set radius <2-32> \u00a77- scan radius in chunks");
				ChatUtil.info("\u00a7f.bf set <type> <n> \u00a77- how many per chunk to flag it");
				ChatUtil.info("\u00a7f.bf <type> on|off \u00a77- toggle a detector");
				ChatUtil.info("\u00a7f.bf esp on|off \u00a77- on-screen highlights");
				ChatUtil.info("\u00a77types: lights, amethyst, storage, valuables, machines, builds, living, farms, portals, frames, autofarms, stash");
			}
			case "list" -> list(mc);
			case "nearest" -> nearest(mc);
			case "badge" -> {
				if (args.length > 1) badge = args[1].equalsIgnoreCase("on");
				ChatUtil.info("badge " + (badge ? "\u00a7aon" : "\u00a7coff"));
			}
			case "test" -> test(mc, args.length > 1 && args[1].equalsIgnoreCase("clear"));
			case "log" -> {
				if (args.length > 1) log = args[1].equalsIgnoreCase("on");
				ChatUtil.info("log " + (log ? "\u00a7aon" : "\u00a7coff"));
			}
			case "clear" -> { reset(); lastLevel = mc.level; ChatUtil.info("Cleared."); }
			case "status" -> status(mc);
			case "esp" -> {
				if (args.length > 1) esp = args[1].equalsIgnoreCase("on");
				ChatUtil.info("esp " + (esp ? "\u00a7aon" : "\u00a7coff"));
			}
			case "set" -> {
				if (args.length < 3) { ChatUtil.info("Usage: .bf set radius|<type> <number>"); return true; }
				int n;
				try { n = Integer.parseInt(args[2]); } catch (NumberFormatException e) { ChatUtil.info("\u00a7cNot a number"); return true; }
				if (args[1].equalsIgnoreCase("radius")) {
					radiusChunks = Math.max(2, Math.min(32, n));
					ChatUtil.info("radius = " + radiusChunks + " chunks (" + (radiusChunks * 16) + " blocks)."
							+ " You can only scan chunks the server has sent you (render distance "
							+ mc.options.renderDistance().get() + ").");
				} else {
					Cat c = Cat.byKey(args[1]);
					if (c == null) { ChatUtil.info("\u00a7cUnknown setting " + args[1]); return true; }
					c.min = Math.max(1, n);
					alertMask.clear();
					ChatUtil.info(c.key + " needs " + c.min + " per chunk");
				}
			}
			default -> {
				Cat c = Cat.byKey(sub);
				if (c == null) return false;
				if (args.length > 1) c.on = args[1].equalsIgnoreCase("on");
				ChatUtil.info(c.key + (c.on ? " \u00a7aon" : " \u00a7coff"));
			}
		}
		return true;
	}

	private void status(Minecraft mc) {
		ChatUtil.info("radius " + radiusChunks + " chunks, esp " + (esp ? "on" : "off")
				+ ", render distance " + mc.options.renderDistance().get()
				+ ", scanned " + lastScan.size() + " chunks, flagged " + countFlagged() + " chunks");
		for (Cat c : Cat.values()) {
			ChatUtil.info(c.code + c.key + "\u00a77: " + (c.on ? "on" : "off") + ", needs " + c.min + " per chunk");
		}
	}

	private int countFlagged() {
		int n = 0;
		for (ChunkResult r : results.values()) if (isFlagged(r)) n++;
		return n;
	}

	private static int[] anchor(ChunkResult r) {
		for (Cat c : Cat.values()) {
			if (catFlagged(r, c)) return r.pos.get(c).get(0);
		}
		return null;
	}

	private void list(Minecraft mc) {
		if (mc.player == null) return;
		List<ChunkResult> flagged = new ArrayList<>();
		for (ChunkResult r : results.values()) if (isFlagged(r)) flagged.add(r);
		if (flagged.isEmpty()) { ChatUtil.info("Nothing flagged yet."); return; }

		double px = mc.player.getX(), pz = mc.player.getZ();
		flagged.sort((a, b) -> Double.compare(dist2(anchor(a), px, pz), dist2(anchor(b), px, pz)));

		ChatUtil.info("Flagged spots (" + flagged.size() + "), nearest first:");
		int shown = 0;
		for (ChunkResult r : flagged) {
			if (shown++ >= 10) break;
			StringBuilder sb = new StringBuilder();
			for (Cat c : Cat.values()) {
				if (catFlagged(r, c)) sb.append(c.code).append(c.key).append(" x").append(r.pos.get(c).size()).append(" ");
			}
			ChatUtil.info(sb + "\u00a77at " + fmt(mc, anchor(r)));
		}
	}

	/** Spawns fake markers around you (not real finds, not logged) so you can check the ESP, labels and BASE tag. */
	private void test(Minecraft mc, boolean clear) {
		if (mc.player == null) return;
		testResults.clear();
		if (clear) { ChatUtil.info("Test markers cleared."); return; }

		double yaw = Math.toRadians(mc.player.getYRot());
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // forward
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw);  // right
		int px = (int) Math.floor(mc.player.getX()), py = (int) Math.floor(mc.player.getY()), pz = (int) Math.floor(mc.player.getZ());

		// 1) a "likely base" 25 blocks ahead: lights + storage + machines + living + frames
		ChunkResult base = new ChunkResult();
		base.pos.put(Cat.LIGHT, testPoints(px, py, pz, fx, fz, rx, rz, 25, 0, 3));
		base.pos.put(Cat.STORAGE, testPoints(px, py, pz, fx, fz, rx, rz, 27, 3, 3));
		base.pos.put(Cat.MACHINE, testPoints(px, py, pz, fx, fz, rx, rz, 29, -3, 6));
		base.pos.put(Cat.LIVING, testPoints(px, py, pz, fx, fz, rx, rz, 26, -5, 2));
		base.pos.put(Cat.ENTITY, testPoints(px, py, pz, fx, fz, rx, rz, 28, 5, 2));
		base.pos.put(Cat.STASH, testPoints(px, py, pz, fx, fz, rx, rz, 27, -8, 1));
		testResults.add(base);

		// 2) a lone portal far ahead and to the right (checks the "far = stronger highlight")
		ChunkResult portal = new ChunkResult();
		portal.pos.put(Cat.PORTAL, testPoints(px, py, pz, fx, fz, rx, rz, 90, 30, 1));
		testResults.add(portal);

		// 3) valuables behind you (checks the screen-edge arrow)
		ChunkResult behind = new ChunkResult();
		behind.pos.put(Cat.VALUABLE, testPoints(px, py, pz, fx, fz, rx, rz, -40, 0, 1));
		testResults.add(behind);

		// 4) a farm to the left, deep underground
		ChunkResult farm = new ChunkResult();
		farm.pos.put(Cat.FARM, testPoints(px, Math.max(py - 40, -60), pz, fx, fz, rx, rz, 15, -35, 4));
		farm.pos.put(Cat.CROP, testPoints(px, Math.max(py - 40, -60), pz, fx, fz, rx, rz, 18, -35, 6));
		testResults.add(farm);

		testUntil = tick + 600;
		baseFlashUntil = tick + 100;
		ChatUtil.info("\u00a7eTEST\u00a7r placed " + testResults.size() + " fake spots. Look ahead: the cluster should say BASE.");
		ChatUtil.info("\u00a77Portal far ahead-right, valuables behind you (edge arrow), farm left and below. Gone in 30 s.");
		ChatUtil.info("\u00a74\u00a7lLIKELY BASE \u00a7r\u00a77(6 signs) at " + fmt(mc, base.pos.get(Cat.LIGHT).get(0)) + " \u00a78[test]");
	}

	private static List<int[]> testPoints(int px, int py, int pz, double fx, double fz, double rx, double rz,
			double ahead, double right, int n) {
		List<int[]> l = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int x = (int) Math.floor(px + fx * ahead + rx * (right + i));
			int z = (int) Math.floor(pz + fz * ahead + rz * (right + i));
			l.add(new int[] { x, py + (i % 2), z });
		}
		return l;
	}

	private void nearest(Minecraft mc) {
		if (mc.player == null) return;
		int[] best = null;
		double bestD = Double.MAX_VALUE;
		boolean bestLikely = false;
		for (ChunkResult r : results.values()) {
			if (!isFlagged(r)) continue;
			int[] a = anchor(r);
			double d = dist2(a, mc.player.getX(), mc.player.getZ());
			if (d < bestD) { bestD = d; best = a; bestLikely = likelyBase(r); }
		}
		if (best == null) { ChatUtil.info("Nothing flagged yet."); return; }
		double dx = best[0] + 0.5 - mc.player.getX(), dz = best[2] + 0.5 - mc.player.getZ();
		// +x is east, +z is south
		String[] names = { "east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east" };
		double ang = Math.toDegrees(Math.atan2(dz, dx));
		int idx = (int) Math.round(((ang % 360) + 360) % 360 / 45.0) % 8;
		ChatUtil.info("Nearest" + (bestLikely ? " \u00a74LIKELY BASE" : "") + " \u00a77\u2192 \u00a7f" + names[idx] + " \u00a77at " + fmt(mc, best));
	}

	private static double dist2(int[] p, double px, double pz) {
		double dx = p[0] - px, dz = p[2] - pz;
		return dx * dx + dz * dz;
	}
}
