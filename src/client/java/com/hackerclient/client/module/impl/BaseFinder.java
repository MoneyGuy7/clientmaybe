package com.hackerclient.client.module.impl;

import com.hackerclient.client.module.Module;
import com.hackerclient.client.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BaseFinder: scans loaded chunks for signs of player activity.
 *
 *  - Man-placed light: torches, lanterns, lit redstone lamps, campfires, glowstone, froglights, etc.
 *    A chunk is flagged once it holds at least {@link #minLights} of them (mineshafts, villages and
 *    strongholds also place a few torches, so a single torch is not enough to flag a chunk).
 *  - Grown amethyst: amethyst buds/clusters/budding blocks that sit next to player-made blocks
 *    (hoppers, pistons, observers, chests, torches...). Natural geodes have none of those, so a farm stands out.
 *
 * Scanning is spread over several ticks so it never causes a lag spike.
 */
public class BaseFinder extends Module {
	private enum Kind { NONE, LIGHT, LIT_LIGHT, AMETHYST, PLAYER }

	private static final class ChunkResult {
		int lights;
		int amethyst;
		int[] firstLight;    // {x, y, z}
		int[] firstAmethyst; // {x, y, z}
	}

	// ---- settings (change with .bf set ...) ----
	private int radiusChunks = 10;
	private int minLights = 3;
	private boolean findLights = true;
	private boolean findAmethyst = true;

	// ---- tuning ----
	private static final int CHUNKS_PER_TICK = 2;
	private static final int RESCAN_TICKS = 600;
	private static final int AMETHYST_PROXIMITY = 4;
	private static final int LIST_CAP = 2048;

	// ---- state ----
	private final Map<Long, ChunkResult> results = new HashMap<>();
	private final Map<Long, Integer> lastScan = new HashMap<>();
	private final Set<Long> alerted = new HashSet<>();
	private final ArrayDeque<int[]> queue = new ArrayDeque<>();
	private final Map<Block, Kind> kindCache = new IdentityHashMap<>();
	private Level lastLevel;
	private int tick;

	public BaseFinder() {
		super("BaseFinder", "Finds player bases via man-placed light and grown amethyst");
	}

	@Override
	protected void onEnable() {
		ChatUtil.info("BaseFinder \u00a7aon\u00a7r - scanning loaded chunks (.bf help)");
	}

	@Override
	protected void onDisable() {
		queue.clear();
	}

	@Override
	public void onAlwaysTick(Minecraft mc) {
		// Wipe everything when leaving the world / switching dimension.
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

		if (queue.isEmpty() && tick % 20 == 0) refillQueue(mc);

		for (int i = 0; i < CHUNKS_PER_TICK && !queue.isEmpty(); i++) {
			int[] c = queue.poll();
			scanChunk(mc, c[0], c[1]);
		}
	}

	// ------------------------------------------------------------------ scanning

	private static long chunkKey(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	private void reset() {
		results.clear();
		lastScan.clear();
		alerted.clear();
		queue.clear();
		kindCache.clear();
		lastLevel = null;
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
				Integer last = lastScan.get(chunkKey(cx, cz));
				if (last != null && tick - last < RESCAN_TICKS) continue;
				todo.add(new int[] { dist2, cx, cz });
			}
		}
		todo.sort((a, b) -> Integer.compare(a[0], b[0]));
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
		int minY = chunk.getMinY();

		List<int[]> lights = new ArrayList<>();
		List<int[]> amethyst = new ArrayList<>();

		// Pass 1: lights + amethyst
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			if (s == null || s.hasOnlyAir()) continue;
			if (!s.maybeHas(st -> {
				Kind k = kindOf(st.getBlock(), nether, end);
				return k == Kind.LIGHT || k == Kind.LIT_LIGHT || k == Kind.AMETHYST;
			})) continue;

			int baseX = cx << 4, baseY = minY + (i << 4), baseZ = cz << 4;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState st = s.getBlockState(x, y, z);
						Kind k = kindOf(st.getBlock(), nether, end);
						if (k == Kind.NONE || k == Kind.PLAYER) continue;
						if (k == Kind.LIT_LIGHT && !isLit(st)) continue;
						int[] pos = { baseX + x, baseY + y, baseZ + z };
						if (k == Kind.AMETHYST) {
							if (amethyst.size() < LIST_CAP) amethyst.add(pos);
						} else if (lights.size() < LIST_CAP) {
							lights.add(pos);
						}
					}
				}
			}
		}

		ChunkResult r = new ChunkResult();

		if (findLights) {
			r.lights = lights.size();
			if (!lights.isEmpty()) r.firstLight = lights.get(0);
		}

		// Pass 2: only if amethyst exists, collect player-made blocks and check proximity.
		if (findAmethyst && !amethyst.isEmpty()) {
			List<int[]> player = new ArrayList<>(lights); // placed light next to amethyst also counts
			for (int i = 0; i < sections.length; i++) {
				LevelChunkSection s = sections[i];
				if (s == null || s.hasOnlyAir()) continue;
				if (!s.maybeHas(st -> kindOf(st.getBlock(), nether, end) == Kind.PLAYER)) continue;
				int baseX = cx << 4, baseY = minY + (i << 4), baseZ = cz << 4;
				for (int y = 0; y < 16; y++) {
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							BlockState st = s.getBlockState(x, y, z);
							if (kindOf(st.getBlock(), nether, end) == Kind.PLAYER && player.size() < LIST_CAP * 2) {
								player.add(new int[] { baseX + x, baseY + y, baseZ + z });
							}
						}
					}
				}
			}
			for (int[] a : amethyst) {
				if (nearAny(a, player)) {
					r.amethyst++;
					if (r.firstAmethyst == null) r.firstAmethyst = a;
				}
			}
		}

		if (r.lights == 0 && r.amethyst == 0) {
			results.remove(key);
			alerted.remove(key);
			return;
		}
		results.put(key, r);

		if (isFlagged(r)) {
			if (alerted.add(key)) announce(mc, r);
		} else {
			alerted.remove(key);
		}
	}

	private static boolean nearAny(int[] a, List<int[]> others) {
		for (int[] o : others) {
			if (Math.abs(a[0] - o[0]) <= AMETHYST_PROXIMITY
					&& Math.abs(a[1] - o[1]) <= AMETHYST_PROXIMITY
					&& Math.abs(a[2] - o[2]) <= AMETHYST_PROXIMITY) {
				return true;
			}
		}
		return false;
	}

	private boolean isFlagged(ChunkResult r) {
		return (findLights && r.lights >= minLights) || (findAmethyst && r.amethyst > 0);
	}

	private static boolean isLit(BlockState st) {
		return st.hasProperty(BlockStateProperties.LIT) && st.getValue(BlockStateProperties.LIT);
	}

	// ------------------------------------------------------------------ block classification

	private Kind kindOf(Block block, boolean nether, boolean end) {
		return kindCache.computeIfAbsent(block, b -> classify(b, nether, end));
	}

	private static Kind classify(Block block, boolean nether, boolean end) {
		String p = BuiltInRegistries.BLOCK.getKey(block).getPath();

		// grown amethyst
		switch (p) {
			case "small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud",
					"amethyst_cluster", "budding_amethyst" -> { return Kind.AMETHYST; }
			default -> {}
		}

		// man-placed light (always lit)
		switch (p) {
			case "torch", "wall_torch", "soul_torch", "soul_wall_torch",
					"copper_torch", "copper_wall_torch",
					"lantern", "soul_lantern", "jack_o_lantern" -> { return Kind.LIGHT; }
			default -> {}
		}
		if (p.endsWith("copper_lantern") || p.endsWith("froglight")) return Kind.LIGHT;
		// natural in some dimensions, so only count where players had to place them
