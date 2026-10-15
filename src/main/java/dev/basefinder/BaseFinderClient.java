package dev.basefinder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.particle.ParticleTypes;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Properties;

/** Client-side Base Finder for Minecraft 1.21.11 + Fabric. */
public final class BaseFinderClient implements ClientModInitializer {
    private static KeyBinding toggleKey, rescanKey, increaseRadiusKey, decreaseRadiusKey, configKey;
    private static boolean enabled = false;
    private static boolean chunkFinderEnabled = true;
    private static int tickCounter = 0;
    private static int highlightTick = 0;
    private static boolean highlightsEnabled = true;
    private static int radius = 24;
    private static int sensitivity = 3;
    private static int simulationDistance = 4;
    private static final int MIN_RADIUS = 8, MAX_RADIUS = 64, MAX_RESULTS = 12;
    private static final List<FoundBlock> found = new ArrayList<>();
    private static final Map<Block, Boolean> blockFilters = new LinkedHashMap<>();
    private static final List<Block> filterBlocks = List.of(Blocks.SPAWNER, Blocks.ANCIENT_DEBRIS, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.ENDER_CHEST, Blocks.SHULKER_BOX, Blocks.HOPPER, Blocks.DROPPER, Blocks.DISPENSER, Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.CRAFTING_TABLE, Blocks.ENCHANTING_TABLE, Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL, Blocks.BREWING_STAND, Blocks.SMITHING_TABLE, Blocks.GRINDSTONE, Blocks.STONECUTTER, Blocks.LECTERN, Blocks.LOOM, Blocks.CARTOGRAPHY_TABLE, Blocks.JUKEBOX, Blocks.BEEHIVE, Blocks.BEE_NEST, Blocks.BUDDING_AMETHYST, Blocks.AMETHYST_BLOCK, Blocks.AMETHYST_CLUSTER, Blocks.LARGE_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.SMALL_AMETHYST_BUD);
    private static String scanStatus = "Press G to enable";
    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("basefinder", "controls"));

    @Override
    public void onInitializeClient() {
        loadConfig();
        toggleKey = registerKey("key.basefinder.toggle", GLFW.GLFW_KEY_G);
        rescanKey = registerKey("key.basefinder.rescan", GLFW.GLFW_KEY_R);
        increaseRadiusKey = registerKey("key.basefinder.radius_increase", GLFW.GLFW_KEY_RIGHT_BRACKET);
        decreaseRadiusKey = registerKey("key.basefinder.radius_decrease", GLFW.GLFW_KEY_LEFT_BRACKET);
        configKey = registerKey("key.basefinder.config", GLFW.GLFW_KEY_F8);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (configKey.wasPressed()) {
                if (client.currentScreen == null) client.setScreen(new FinderConfigScreen());
            }
            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                if (!enabled) { found.clear(); scanStatus = "Disabled"; }
                else { scanStatus = "Scanning nearby loaded blocks..."; tickCounter = 39; }
                notifyPlayer(client, "Base Finder: " + (enabled ? "ON" : "OFF"));
            }
            while (rescanKey.wasPressed()) {
                if (enabled && client.player != null && client.world != null) {
                    scan(client);
                    notifyPlayer(client, "Base Finder: scan complete (" + found.size() + " displayed)");
                } else notifyPlayer(client, "Base Finder is off. Press G first.");
            }
            while (increaseRadiusKey.wasPressed()) {
                radius = Math.min(MAX_RADIUS, radius + 8);
                saveConfig();
                notifyPlayer(client, "Base Finder radius: " + radius + " blocks");
                if (enabled) tickCounter = 39;
            }
            while (decreaseRadiusKey.wasPressed()) {
                radius = Math.max(MIN_RADIUS, radius - 8);
                saveConfig();
                notifyPlayer(client, "Base Finder radius: " + radius + " blocks");
                if (enabled) tickCounter = 39;
            }
            if (enabled && highlightsEnabled && client.player != null && client.world != null && ++highlightTick >= 5) {
                highlightTick = 0;
                highlightFoundBlocks(client);
            }
            if (enabled && client.player != null && client.world != null && ++tickCounter >= 40) {
                tickCounter = 0;
                scan(client);
            }
        });
        // The HUD is removed; particle markers are rendered at detected blocks.
    }

    private static KeyBinding registerKey(String translationKey, int keyCode) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(translationKey, InputUtil.Type.KEYSYM, keyCode, CATEGORY));
    }

    private static void notifyPlayer(MinecraftClient client, String message) {
        if (client.player != null) client.player.sendMessage(Text.literal(message), true);
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("base-finder.properties");
    }

    private static void loadConfig() {
        Properties p = new Properties();
        Path path = configPath();
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) { p.load(in); }
            catch (IOException ignored) { }
        }
        sensitivity = clamp(parseInt(p.getProperty("sensitivity"), 3), 1, 5);
        simulationDistance = clamp(parseInt(p.getProperty("simulationDistance"), 4), 2, 16);
        radius = clamp(parseInt(p.getProperty("radius"), 24), MIN_RADIUS, MAX_RADIUS);
        chunkFinderEnabled = Boolean.parseBoolean(p.getProperty("chunkFinderEnabled", "true"));
        highlightsEnabled = Boolean.parseBoolean(p.getProperty("highlightsEnabled", "true"));
        blockFilters.clear();
        for (Block block : filterBlocks) {
            String key = "block." + Registries.BLOCK.getId(block).toString().replace(':', '.');
            blockFilters.put(block, Boolean.parseBoolean(p.getProperty(key, "true")));
        }
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
    }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static void saveConfig() {
        Properties p = new Properties();
        p.setProperty("sensitivity", Integer.toString(sensitivity));
        p.setProperty("simulationDistance", Integer.toString(simulationDistance));
        p.setProperty("radius", Integer.toString(radius));
        p.setProperty("chunkFinderEnabled", Boolean.toString(chunkFinderEnabled));
        p.setProperty("highlightsEnabled", Boolean.toString(highlightsEnabled));
        for (Block block : filterBlocks) {
            String key = "block." + Registries.BLOCK.getId(block).toString().replace(':', '.');
            p.setProperty(key, Boolean.toString(blockFilters.getOrDefault(block, true)));
        }
        try (OutputStream out = Files.newOutputStream(configPath())) {
            p.store(out, "Base Finder settings");
        } catch (IOException ignored) { }
    }

    private static void scan(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        BlockPos center = client.player.getBlockPos();
        Vec3d playerPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
        List<FoundBlock> results = new ArrayList<>();
        int scanRadius = Math.min(radius, simulationDistance * 16);
        int verticalRadius = Math.min(12 + sensitivity * 2, 20);
        for (int x = -scanRadius; x <= scanRadius; x++) {
            for (int y = -verticalRadius; y <= verticalRadius; y++) {
                for (int z = -scanRadius; z <= scanRadius; z++) {
                    BlockPos pos = center.add(x, y, z);
                    if (!client.world.isChunkLoaded(pos)) continue;
                    Block block = client.world.getBlockState(pos).getBlock();
                    if (isBaseClue(block)) {
                        double distance = Math.sqrt(pos.getSquaredDistance(playerPos));
                        results.add(new FoundBlock(pos.toImmutable(), Registries.BLOCK.getId(block).getPath(), distance));
                    }
                }
            }
        }
        results.sort(Comparator.comparingDouble(FoundBlock::distance));
        found.clear();
        found.addAll(results.subList(0, Math.min(MAX_RESULTS, results.size())));
        scanStatus = results.size() + " clues found; showing nearest " + found.size();
    }

    private static boolean isBaseClue(Block block) {
        // Only explicitly supported filter blocks are clues. Unknown blocks (including air,
        // grass, dirt, stone, and other terrain) must never be added to the results.
        if (!blockFilters.containsKey(block) || !blockFilters.get(block)) return false;
        boolean geode = block == Blocks.BUDDING_AMETHYST || block == Blocks.AMETHYST_BLOCK
                || block == Blocks.AMETHYST_CLUSTER || block == Blocks.LARGE_AMETHYST_BUD
                || block == Blocks.MEDIUM_AMETHYST_BUD || block == Blocks.SMALL_AMETHYST_BUD;
        return !geode || chunkFinderEnabled;
    }

    private static void highlightFoundBlocks(MinecraftClient client) {
        if (client.world == null) return;
        for (FoundBlock item : found) {
            BlockPos p = item.pos();
            Block block = client.world.getBlockState(p).getBlock();
            // Distinct vanilla particle palettes make each clue type easier to identify.
            net.minecraft.particle.ParticleEffect markerParticle;
            if (block == Blocks.SPAWNER) {
                markerParticle = ParticleTypes.PORTAL; // purple
            } else if (block == Blocks.ANCIENT_DEBRIS) {
                markerParticle = ParticleTypes.FLAME; // orange
            } else if (block == Blocks.BUDDING_AMETHYST || block == Blocks.AMETHYST_BLOCK
                    || block == Blocks.AMETHYST_CLUSTER || block == Blocks.LARGE_AMETHYST_BUD
                    || block == Blocks.MEDIUM_AMETHYST_BUD || block == Blocks.SMALL_AMETHYST_BUD) {
                markerParticle = ParticleTypes.END_ROD; // bright white
            } else if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL
                    || block == Blocks.ENDER_CHEST || block == Blocks.SHULKER_BOX) {
                markerParticle = ParticleTypes.HAPPY_VILLAGER; // green
            } else if (block == Blocks.HOPPER || block == Blocks.DROPPER || block == Blocks.DISPENSER
                    || block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER) {
                markerParticle = ParticleTypes.SOUL_FIRE_FLAME; // cyan-blue
            } else {
                markerParticle = ParticleTypes.WITCH; // purple-pink for other utility blocks
            }
            double[][] markers = {
                    {-0.42, 0.95, -0.42}, {0.42, 0.95, -0.42},
                    {-0.42, 0.95, 0.42}, {0.42, 0.95, 0.42},
                    {-0.42, 0.10, -0.42}, {0.42, 0.10, -0.42},
                    {-0.42, 0.10, 0.42}, {0.42, 0.10, 0.42},
                    {-0.42, 0.55, -0.42}, {0.42, 0.55, -0.42},
                    {-0.42, 0.55, 0.42}, {0.42, 0.55, 0.42}
            };
            double x = p.getX() + 0.5, z = p.getZ() + 0.5;
            for (double[] marker : markers) {
                client.world.addParticleClient(markerParticle,
                        x + marker[0], p.getY() + marker[1], z + marker[2], 0, 0.005, 0);
            }
        }
    }

    private static final class FinderConfigScreen extends Screen {
        private int filterPage = 0;
        private static final int FILTERS_PER_PAGE = 10;

        protected FinderConfigScreen() { super(Text.literal("Base Finder Settings")); }

        @Override
        protected void init() {
            clearChildren();
            int cx = width / 2;
            int panelWidth = Math.min(430, width - 24);
            int left = cx - panelWidth / 2;
            int top = 42;
            addDrawableChild(ButtonWidget.builder(moduleLabel(), b -> {
                chunkFinderEnabled = !chunkFinderEnabled;
                b.setMessage(moduleLabel()); saveConfig();
                if (enabled) tickCounter = 39;
            }).dimensions(left, top, panelWidth, 20).build());
            int half = (panelWidth - 8) / 2;
            addDrawableChild(ButtonWidget.builder(sensitivityLabel(), b -> {
                sensitivity = sensitivity % 5 + 1; b.setMessage(sensitivityLabel()); saveConfig();
                if (enabled) tickCounter = 39;
            }).dimensions(left, top + 27, half, 20).build());
            addDrawableChild(ButtonWidget.builder(distanceLabel(), b -> {
                simulationDistance = simulationDistance >= 16 ? 2 : simulationDistance + 2;
                b.setMessage(distanceLabel()); saveConfig(); if (enabled) tickCounter = 39;
            }).dimensions(left + half + 8, top + 27, half, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Highlights: " + (highlightsEnabled ? "ON" : "OFF")), b -> {
                highlightsEnabled = !highlightsEnabled;
                b.setMessage(Text.literal("Highlights: " + (highlightsEnabled ? "ON" : "OFF")));
                saveConfig();
            }).dimensions(left, top + 81, panelWidth, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Scan radius: " + radius + " blocks"), b -> {
                radius = radius >= MAX_RADIUS ? MIN_RADIUS : radius + 8;
                b.setMessage(Text.literal("Scan radius: " + radius + " blocks")); saveConfig();
                if (enabled) tickCounter = 39;
            }).dimensions(left, top + 54, panelWidth, 20).build());

            int pages = (filterBlocks.size() + FILTERS_PER_PAGE - 1) / FILTERS_PER_PAGE;
            int first = filterPage * FILTERS_PER_PAGE;
            for (int i = first; i < Math.min(first + FILTERS_PER_PAGE, filterBlocks.size()); i++) {
                final Block block = filterBlocks.get(i);
                int local = i - first;
                int bx = left + (local % 2) * (half + 8);
                int by = top + 105 + (local / 2) * 22;
                addDrawableChild(ButtonWidget.builder(filterLabel(block), b -> {
                    blockFilters.put(block, !blockFilters.getOrDefault(block, true));
                    b.setMessage(filterLabel(block)); saveConfig(); if (enabled) tickCounter = 39;
                }).dimensions(bx, by, half, 20).build());
            }
            int navY = top + 105 + 5 * 22 + 2;
            addDrawableChild(ButtonWidget.builder(Text.literal("< Previous"), b -> {
                filterPage = (filterPage + pages - 1) % pages; init();
            }).dimensions(left, navY, half, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Next >"), b -> {
                filterPage = (filterPage + 1) % pages; init();
            }).dimensions(left + half + 8, navY, half, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                    .dimensions(cx - 55, navY + 26, 110, 20).build());
        }

        private Text moduleLabel() { return Text.literal("VISUALS  /  AMETHYST GEODE CLUES: " + (chunkFinderEnabled ? "ON" : "OFF")); }
        private Text sensitivityLabel() { return Text.literal("Sensitivity  " + sensitivity + " / 5"); }
        private Text distanceLabel() { return Text.literal("Simulation distance  " + simulationDistance + " chunks"); }
        private Text filterLabel(Block block) {
            String name = Registries.BLOCK.getId(block).getPath().replace('_', ' ');
            name = name.substring(0, 1).toUpperCase() + name.substring(1);
            return Text.literal((blockFilters.getOrDefault(block, true) ? "✓  " : "✕  ") + name);
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            // Prestige/Donut-inspired dark navy and violet settings shell.
            context.fill(0, 0, width, height, 0xD9070A17);
            int panelWidth = Math.min(570, width - 24);
            int panelHeight = Math.min(height - 26, 390);
            int left = width / 2 - panelWidth / 2;
            int top = Math.max(12, height / 2 - panelHeight / 2);
            int bottom = top + panelHeight;

            context.fill(left - 2, top - 2, left + panelWidth + 2, bottom + 2, 0xFF070914);
            context.fill(left, top, left + panelWidth, bottom, 0xF80D1025);
            context.fill(left, top, left + 142, bottom, 0xFF10152C);
            context.fill(left, top, left + 142, top + 3, 0xFF9C78FF);
            context.fill(left + 154, top + 44, left + panelWidth - 12, top + 45, 0xFF292D4B);

            context.drawTextWithShadow(textRenderer, Text.literal("♛ PRESTIGE ALPHA"), left + 12, top + 12, 0xFFE2D7FF);
            context.drawTextWithShadow(textRenderer, Text.literal("BASE FINDER"), left + 12, top + 34, 0xFF9E80FF);
            context.drawTextWithShadow(textRenderer, Text.literal("MODULES"), left + 12, top + 65, 0xFF7E89AB);
            String[] nav = {"Combat", "Misc", "Movement", "Visual", "Donut"};
            for (int i = 0; i < nav.length; i++) {
                int ny = top + 82 + i * 23;
                if (i == 4) context.fill(left + 7, ny - 4, left + 135, ny + 15, 0xFF332A69);
                context.drawTextWithShadow(textRenderer, Text.literal((i == 4 ? "●  " : "›  ") + nav[i]),
                        left + 15, ny, i == 4 ? 0xFFE5DCFF : 0xFFAAB4D0);
            }
            context.fill(left + 10, bottom - 45, left + 132, bottom - 44, 0xFF292D4B);
            context.drawTextWithShadow(textRenderer, Text.literal("GENERAL"), left + 12, bottom - 34, 0xFF7E89AB);
            context.drawTextWithShadow(textRenderer, Text.literal("⚙  Settings"), left + 12, bottom - 18, 0xFFAAB4D0);

            int contentLeft = left + 158;
            context.drawTextWithShadow(textRenderer, Text.literal("Donut Modules"), contentLeft, top + 12, 0xFFE4D9FF);
            context.drawTextWithShadow(textRenderer, Text.literal("Block filters  •  saved locally"),
                    contentLeft, top + 27, 0xFF8E9AB9);
            context.fill(left + panelWidth - 158, top + 10, left + panelWidth - 12, top + 31, 0xFF151A31);
            context.drawTextWithShadow(textRenderer, Text.literal("⌕  Search modules"), left + panelWidth - 151, top + 16, 0xFF687493);

            // Card-like backgrounds behind the existing functional controls.
            context.fill(contentLeft - 4, top + 51, left + panelWidth - 12, top + 75, 0xFF171C35);
            context.fill(contentLeft - 4, top + 79, left + panelWidth - 12, top + 103, 0xFF171C35);
            context.fill(contentLeft - 4, top + 106, left + panelWidth - 12, top + 130, 0xFF171C35);
            context.fill(contentLeft - 4, top + 133, left + panelWidth - 12, top + 157, 0xFF171C35);
            context.fill(contentLeft - 4, top + 160, left + panelWidth - 12, bottom - 35, 0xFF11162C);
            context.fill(contentLeft - 4, top + 160, contentLeft - 1, bottom - 35, 0xFF9C78FF);

            int pages = (filterBlocks.size() + FILTERS_PER_PAGE - 1) / FILTERS_PER_PAGE;
            context.drawTextWithShadow(textRenderer,
                    Text.literal("Toggle the blocks that count as clues  •  Page " + (filterPage + 1) + "/" + pages),
                    contentLeft, top + 145, 0xFFD5DDF0);
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("F8 menu  •  G scanner  •  R rescan  •  [ ] radius"), width / 2, height - 10, 0xFF9CAAC4);
            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public void close() { if (client != null) client.setScreen(null); }
    }

    private record FoundBlock(BlockPos pos, String name, double distance) { }
}
