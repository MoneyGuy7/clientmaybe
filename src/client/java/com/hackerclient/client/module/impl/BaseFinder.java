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
		// natural in some dimensions, so only count where players had to place them
		if (p.equals("glowstone") || p.equals("shroomlight")) return nether ? Kind.NONE : Kind.LIGHT;
		if (p.equals("end_rod")) return end ? Kind.NONE : Kind.LIGHT;

		// light that only counts while lit
		if (p.equals("redstone_lamp") || p.equals("campfire") || p.equals("soul_campfire")
				|| p.endsWith("copper_bulb") || p.equals("candle") || p.endsWith("_candle")) {
			return Kind.LIT_LIGHT;
		}

		// blocks that never generate inside a natural geode
		switch (p) {
			case "hopper", "dropper", "dispenser", "observer", "piston", "sticky_piston",
					"chest", "trapped_chest", "barrel", "ladder", "scaffolding",
					"redstone_wire", "repeater", "comparator", "lever", "redstone_block",
					"slime_block", "honey_block", "target", "redstone_torch", "redstone_wall_torch" -> {
				return Kind.PLAYER;
			}
			default -> {}
		}
		return Kind.NONE;
	}

	// ------------------------------------------------------------------ output

	private void announce(Minecraft mc, ChunkResult r) {
		if (findLights && r.lights >= minLights && r.firstLight != null) {
			ChatUtil.info("\u00a7eMan-placed light \u00a7fx" + r.lights + " \u00a77at " + fmt(mc, r.firstLight));
		}
		if (findAmethyst && r.amethyst > 0 && r.firstAmethyst != null) {
			ChatUtil.info("\u00a7dGrown amethyst \u00a7fx" + r.amethyst + " \u00a77at " + fmt(mc, r.firstAmethyst));
		}
	}

	private static String fmt(Minecraft mc, int[] p) {
		double dx = p[0] + 0.5 - mc.player.getX();
		double dz = p[2] + 0.5 - mc.player.getZ();
		int dist = (int) Math.sqrt(dx * dx + dz * dz);
		return p[0] + ", " + p[1] + ", " + p[2] + " \u00a78(" + dist + "m)";
	}

	// ------------------------------------------------------------------ commands

	@Override
	public boolean onCommand(String[] args) {
		Minecraft mc = Minecraft.getInstance();
		String sub = args.length == 0 ? "help" : args[0].toLowerCase();

		switch (sub) {
			case "help" -> {
				ChatUtil.info("\u00a7f.bf list \u00a77- show flagged spots, nearest first");
				ChatUtil.info("\u00a7f.bf clear \u00a77- forget results and rescan");
				ChatUtil.info("\u00a7f.bf status \u00a77- show settings");
				ChatUtil.info("\u00a7f.bf set radius <2-32> \u00a77- scan radius in chunks");
				ChatUtil.info("\u00a7f.bf set lights <count> \u00a77- lights needed to flag a chunk");
				ChatUtil.info("\u00a7f.bf lights on|off \u00a77/ \u00a7f.bf amethyst on|off");
			}
			case "list" -> list(mc);
			case "clear" -> { reset(); lastLevel = mc.level; ChatUtil.info("Cleared."); }
			case "status" -> ChatUtil.info("radius=" + radiusChunks + " chunks, minLights=" + minLights
					+ ", lights=" + findLights + ", amethyst=" + findAmethyst
					+ ", chunksTracked=" + results.size());
			case "lights" -> { if (args.length > 1) { findLights = args[1].equalsIgnoreCase("on"); ChatUtil.info("lights " + findLights); } }
			case "amethyst" -> { if (args.length > 1) { findAmethyst = args[1].equalsIgnoreCase("on"); ChatUtil.info("amethyst " + findAmethyst); } }
			case "set" -> {
				if (args.length < 3) { ChatUtil.info("Usage: .bf set radius|lights <number>"); return true; }
				int n;
				try { n = Integer.parseInt(args[2]); } catch (NumberFormatException e) { ChatUtil.info("\u00a7cNot a number"); return true; }
				switch (args[1].toLowerCase()) {
					case "radius" -> { radiusChunks = Math.max(2, Math.min(32, n)); ChatUtil.info("radius = " + radiusChunks); }
					case "lights" -> { minLights = Math.max(1, n); alerted.clear(); ChatUtil.info("minLights = " + minLights); }
					default -> ChatUtil.info("\u00a7cUnknown setting");
				}
			}
			default -> { return false; }
		}
		return true;
	}

	private void list(Minecraft mc) {
		if (mc.player == null) return;
		List<Map.Entry<Long, ChunkResult>> flagged = new ArrayList<>();
		for (Map.Entry<Long, ChunkResult> e : results.entrySet()) {
			if (isFlagged(e.getValue())) flagged.add(e);
		}
		if (flagged.isEmpty()) { ChatUtil.info("Nothing flagged yet."); return; }

		double px = mc.player.getX(), pz = mc.player.getZ();
		flagged.sort((a, b) -> Double.compare(dist2(a.getValue(), px, pz), dist2(b.getValue(), px, pz)));

		ChatUtil.info("Flagged spots (" + flagged.size() + "), nearest first:");
		int shown = 0;
		for (Map.Entry<Long, ChunkResult> e : flagged) {
			if (shown++ >= 10) break;
			ChunkResult r = e.getValue();
			StringBuilder sb = new StringBuilder();
			if (findLights && r.lights >= minLights) sb.append("\u00a7elight x").append(r.lights).append(" ");
			if (findAmethyst && r.amethyst > 0) sb.append("\u00a7damethyst x").append(r.amethyst).append(" ");
			int[] where = (r.firstAmethyst != null && findAmethyst && r.amethyst > 0) ? r.firstAmethyst : r.firstLight;
			ChatUtil.info(sb + "\u00a77at " + fmt(mc, where));
		}
	}

	private double dist2(ChunkResult r, double px, double pz) {
		int[] p = r.firstLight != null ? r.firstLight : r.firstAmethyst;
		double dx = p[0] - px, dz = p[2] - pz;
		return dx * dx + dz * dz;
	}
}
