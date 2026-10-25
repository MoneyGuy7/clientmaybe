package com.hackerclient.client.module.impl;

import com.hackerclient.client.module.Module;
import com.hackerclient.client.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

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
 *  - builds     concrete, glazed terracotta, glass, signs, tnt, jukeboxes, note blocks
 *
 * Results are dropped as soon as their chunk is no longer loaded or is out of range, so after you
 * teleport away nothing from the old area stays on your screen.
 */
public class BaseFinder extends Module {

	private enum Cat {
		LIGHT("lights", "Man-placed light", "\u00a7e", 0xFFE04A, 3),
		AMETHYST("amethyst", "Grown amethyst", "\u00a7d", 0xD05CFF, 1),
		STORAGE("storage", "Storage", "\u00a76", 0xFF9A3C, 3),
		VALUABLE("valuables", "Valuable blocks", "\u00a7b", 0x4AE0FF, 1),
		MACHINE("machines", "Redstone machines", "\u00a7c", 0xFF5050, 6),
		BUILD("builds", "Player building", "\u00a7a", 0x7CFF7C, 12);

		final String key;
		final String label;
		final String code;
		final int rgb;
		int min;          // how many are needed in one chunk before it is flagged
		boolean on = true;

		Cat(String key, String label, String code, int rgb, int min) {
			this.key = key;
			this.label = label;
			this.code = code;
			this.rgb = rgb;
			this.min = min;
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
	private static final int CHUNKS_PER_TICK = 2;
	private static final int RESCAN_TICKS = 600;
	private static final int AMETHYST_PROXIMITY = 4;
	private static final int LIST_CAP = 512;
	private static final int STRUCTURE_LIGHT_BONUS = 6;

	// ---- state ----
	private final Map<Long, ChunkResult> results = new HashMap<>();
	private final Map<Long, Integer> lastScan = new HashMap<>();
	private final Map<Long, Integer> alertMask = new HashMap<>();
	private final ArrayDeque<int[]> queue = new ArrayDeque<>();
	private final Map<Block, Info> infoCache = new IdentityHashMap<>();
	private Level lastLevel;
	private int tick;

	public BaseFinder() {
		super("BaseFinder", "Finds player bases (light, amethyst, storage, machines, builds) and highlights them", "bf");
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

		if (tick % 20 == 0) prune(mc);
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
		alertMask.clear();
		queue.clear();
		infoCache.clear();
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
		int minY = chunk.getMinY(); // negative in the overworld, so deepslate is included

		EnumMap<Cat, List<int[]>> found = new EnumMap<>(Cat.class);
		List<int[]> softLights = new ArrayList<>();
		List<int[]> prox = new ArrayList<>();
		boolean city = false;
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
						else if (info.marker == 2) struct = true;

						if (info.litOnly && !isLit(st)) continue;

						int[] pos = { baseX + x, baseY + y, baseZ + z };
						if (info.prox && prox.size() < LIST_CAP * 2) prox.add(pos);
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

		// Soul lanterns / soul torches / candles fill ancient cities, so only count them outside one.
		if (!city && !softLights.isEmpty()) {
			List<int[]> l = found.computeIfAbsent(Cat.LIGHT, k -> new ArrayList<>());
			for (int[] p : softLights) if (l.size() < LIST_CAP) l.add(p);
		}

		// Amethyst only counts when a player-made block is right next to it.
		List<int[]> candidates = found.remove(Cat.AMETHYST);
		if (candidates != null) {
			List<int[]> hits = new ArrayList<>();
			for (int[] a : candidates) if (nearAny(a, prox)) hits.add(a);
			if (!hits.isEmpty()) found.put(Cat.AMETHYST, hits);
		}

		if (found.isEmpty()) {
			results.remove(key);
			alertMask.remove(key);
			return;
		}

		ChunkResult r = new ChunkResult();
		r.pos.putAll(found);
		r.struct = struct || city;
		results.put(key, r);

		int oldMask = alertMask.getOrDefault(key, 0);
		int newMask = 0;
		for (Cat c : Cat.values()) {
			if (catFlagged(r, c)) {
				newMask |= 1 << c.ordinal();
				if ((oldMask & (1 << c.ordinal())) == 0) announce(mc, r, c);
			}
		}
		if (newMask == 0) alertMask.remove(key); else alertMask.put(key, newMask);
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

	private static int threshold(ChunkResult r, Cat c) {
		return c.min + ((c == Cat.LIGHT && r.struct) ? STRUCTURE_LIGHT_BONUS : 0);
	}

	private static boolean catFlagged(ChunkResult r, Cat c) {
		List<int[]> l = r.pos.get(c);
		return c.on && l != null && !l.isEmpty() && l.size() >= threshold(r, c);
	}

	private static boolean isFlagged(ChunkResult r) {
		for (Cat c : Cat.values()) if (catFlagged(r, c)) return true;
		return false;
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

		// natural structure markers (used to avoid false positives)
		switch (p) {
			case "sculk", "sculk_shrieker", "sculk_catalyst", "sculk_sensor", "calibrated_sculk_sensor",
					"reinforced_deepslate" -> { return new Info(null, false, false, false, 1); }
			case "trial_spawner", "vault", "spawner", "end_portal_frame" -> { return new Info(null, false, false, false, 2); }
			default -> {}
		}

		// man-placed light
		switch (p) {
			case "torch", "wall_torch", "copper_torch", "copper_wall_torch", "lantern", "jack_o_lantern" -> {
				return new Info(Cat.LIGHT, false, true, false, 0);
			}
			case "soul_torch", "soul_wall_torch", "soul_lantern" -> {
				return new Info(Cat.LIGHT, false, true, true, 0);
			}
			default -> {}
		}
		if (p.endsWith("copper_lantern") || p.endsWith("froglight")) return new Info(Cat.LIGHT, false, true, false, 0);
		if (p.equals("glowstone") || p.equals("shroomlight")) {
			return nether ? NONE : new Info(Cat.LIGHT, false, true, false, 0);
		}
		if (p.equals("end_rod")) return end ? NONE : new Info(Cat.LIGHT, false, true, false, 0);
		if (p.equals("redstone_lamp") || p.equals("campfire") || p.equals("soul_campfire") || p.endsWith("copper_bulb")) {
			return new Info(Cat.LIGHT, true, true, false, 0);
		}
		if (p.equals("candle") || p.endsWith("_candle")) return new Info(Cat.LIGHT, true, true, true, 0);

		// valuables
		if (p.endsWith("shulker_box")) return end ? NONE : new Info(Cat.VALUABLE, false, true, false, 0);
		switch (p) {
			case "ender_chest", "beacon", "enchanting_table", "respawn_anchor", "conduit", "netherite_block",
					"diamond_block", "emerald_block", "lodestone", "anvil", "chipped_anvil", "damaged_anvil" -> {
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
			case "hopper", "dropper", "dispenser", "observer", "piston", "sticky_piston", "comparator", "repeater" -> {
				return new Info(Cat.MACHINE, false, true, false, 0);
			}
			default -> {}
		}

		// player building
		if (p.endsWith("_concrete") || p.endsWith("_concrete_powder") || p.endsWith("glazed_terracotta")
				|| p.endsWith("_stained_glass") || p.endsWith("_stained_glass_pane")
				|| p.equals("glass") || p.equals("tinted_glass") || p.endsWith("_sign")
				|| p.equals("tnt") || p.equals("jukebox") || p.equals("note_block")) {
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
		if (!esp || mc.player == null || mc.level == null || mc.options.hideGui || results.isEmpty()) return;

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
		for (ChunkResult r : results.values()) {
			for (Cat c : Cat.values()) {
				if (!catFlagged(r, c)) continue;
				for (int[] p : r.pos.get(c)) {
					double dx = p[0] + 0.5 - cam.ex, dy = p[1] + 0.5 - cam.ey, dz = p[2] + 0.5 - cam.ez;
					targets.add(new double[] { Math.sqrt(dx * dx + dy * dy + dz * dz), p[0], p[1], p[2], c.ordinal() });
				}
			}
		}
		targets.sort((a, b) -> Double.compare(a[0], b[0]));

		int count = Math.min(targets.size(), 200);
		for (int i = 0; i < count; i++) {
			drawTarget(g, mc, cam, targets.get(i), i < 30);
		}
	}

	private static void drawTarget(GuiGraphics g, Minecraft mc, Cam c, double[] t, boolean label) {
		double dist = t[0];
		double bx = t[1], by = t[2], bz = t[3];
		int rgb = Cat.values()[(int) t[4]].rgb;

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

		String text = (int) dist + "m  y" + (int) by;

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
				ChatUtil.info("\u00a7f.bf status \u00a77/ \u00a7f.bf clear");
				ChatUtil.info("\u00a7f.bf set radius <2-32> \u00a77- scan radius in chunks");
				ChatUtil.info("\u00a7f.bf set <type> <n> \u00a77- how many per chunk to flag it");
				ChatUtil.info("\u00a7f.bf <type> on|off \u00a77- toggle a detector");
				ChatUtil.info("\u00a7f.bf esp on|off \u00a77- on-screen highlights");
				ChatUtil.info("\u00a77types: lights, amethyst, storage, valuables, machines, builds");
			}
			case "list" -> list(mc);
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

	private static double dist2(int[] p, double px, double pz) {
		double dx = p[0] - px, dz = p[2] - pz;
		return dx * dx + dz * dz;
	}
}
