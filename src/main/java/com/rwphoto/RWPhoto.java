package com.rwphoto;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * RWPhoto: paste JPG/PNG pictures into the world as colored blocks, like WorldEdit but with images.
 * Built only on the public Paper / Adventure API.
 */
public final class RWPhoto extends JavaPlugin implements Listener {

    private static final String[] EXTENSIONS = {".jpg", ".jpeg", ".png"};
    private static final int UNDO_LIMIT = 5;

    private enum Plane { FLOOR, WALL_YZ, WALL_XY }

    private static final class Sel {
        UUID world;
        int[] p1;
        int[] p2;
    }

    private static final class Undo {
        UUID world;
        int[] xs;
        int[] ys;
        int[] zs;
        BlockData[] old;
    }

    private NamespacedKey wandKey;
    private File photosDir;
    private List<Palette.Entry> palette = new ArrayList<>();
    private final Map<String, BlockData> blockData = new HashMap<>();
    private final Map<UUID, Sel> selections = new HashMap<>();
    private final Map<UUID, Deque<Undo>> undos = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private MapArt mapArt;

    // ---- Lifecycle --------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        wandKey = new NamespacedKey(this, "wand");
        photosDir = new File(getDataFolder(), "photos");
        if (!photosDir.exists() && !photosDir.mkdirs()) {
            getLogger().warning("Could not create the photos folder: " + photosDir);
        }
        loadPalette();
        getServer().getPluginManager().registerEvents(this, this);
        mapArt = new MapArt(this);
        mapArt.loadAll();
        getLogger().info("Put JPG/PNG pictures in: " + photosDir.getPath());
    }

    private void loadPalette() {
        List<String> groups = getConfig().getStringList("palette.groups");
        if (groups.isEmpty()) {
            groups = List.of("concrete", "wool", "terracotta", "neutral");
        }
        Set<String> exclude = new HashSet<>();
        for (String s : getConfig().getStringList("palette.exclude")) {
            exclude.add(s.toLowerCase(Locale.ROOT));
        }
        List<Palette.Entry> list = new ArrayList<>();
        blockData.clear();
        for (Palette.Entry e : Palette.build(groups)) {
            if (exclude.contains(e.id)) {
                continue;
            }
            Material m = Material.matchMaterial(e.id);
            if (m == null || !m.isBlock()) {
                getLogger().warning("Block not available on this server version, skipped: " + e.id);
                continue;
            }
            blockData.put(e.id, m.createBlockData());
            list.add(e);
        }
        palette = list;
        getLogger().info("Loaded " + palette.size() + " block colors.");
    }

    // ---- Wand -------------------------------------------------------------------

    private ItemStack makeWand() {
        ItemStack axe = new ItemStack(Material.IRON_AXE);
        ItemMeta meta = axe.getItemMeta();
        meta.displayName(Component.text("RWPhoto Wand", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Left click: position 1", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Right click: position 2", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        axe.setItemMeta(meta);
        return axe;
    }

    private boolean isWand(ItemStack item) {
        if (item == null || item.getType() != Material.IRON_AXE || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isWand(event.getItem())) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("rwphoto.use")) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            setPosition(player, block.getX(), block.getY(), block.getZ(), true);
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            setPosition(player, block.getX(), block.getY(), block.getZ(), false);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        // Creative mode: stop the wand from breaking blocks.
        if (isWand(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    private void setPosition(Player player, int x, int y, int z, boolean first) {
        Sel sel = selections.computeIfAbsent(player.getUniqueId(), k -> new Sel());
        if (sel.world != null && !sel.world.equals(player.getWorld().getUID())) {
            sel.p1 = null;
            sel.p2 = null;
        }
        sel.world = player.getWorld().getUID();
        if (first) {
            sel.p1 = new int[] {x, y, z};
        } else {
            sel.p2 = new int[] {x, y, z};
        }
        String text = (first ? "Position 1" : "Position 2") + " set: " + x + ", " + y + ", " + z;
        if (sel.p1 != null && sel.p2 != null) {
            text += "  (" + (Math.abs(sel.p1[0] - sel.p2[0]) + 1) + " x "
                    + (Math.abs(sel.p1[1] - sel.p2[1]) + 1) + " x "
                    + (Math.abs(sel.p1[2] - sel.p2[2]) + 1) + ")";
        }
        say(player, text, NamedTextColor.GREEN);
    }

    // ---- Commands -----------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("rwphoto.use")) {
            say(sender, "You do not have permission.", NamedTextColor.RED);
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            reloadConfig();
            loadPalette();
            say(sender, "Reloaded. " + palette.size() + " block colors.", NamedTextColor.GREEN);
            return true;
        }
        if (sub.equals("list")) {
            listPhotos(sender);
            return true;
        }
        if (!(sender instanceof Player player)) {
            say(sender, "This command can only be used in game.", NamedTextColor.RED);
            return true;
        }

        switch (sub) {
            case "wand" -> {
                Map<Integer, ItemStack> left = player.getInventory().addItem(makeWand());
                left.values().forEach(i -> player.getWorld().dropItem(player.getLocation(), i));
                say(player, "You got the RWPhoto wand. Left click = pos1, right click = pos2.",
                        NamedTextColor.GREEN);
            }
            case "pos1" -> setPosition(player, player.getLocation().getBlockX(),
                    player.getLocation().getBlockY() - 1, player.getLocation().getBlockZ(), true);
            case "pos2" -> setPosition(player, player.getLocation().getBlockX(),
                    player.getLocation().getBlockY() - 1, player.getLocation().getBlockZ(), false);
            case "paste" -> paste(player, args);
            case "undo" -> undo(player);
            case "map" -> {
                Sel ms = selections.get(player.getUniqueId());
                if (ms == null || ms.p1 == null || ms.p2 == null
                        || !ms.world.equals(player.getWorld().getUID())) {
                    say(player, "Select a wall first: /rwphoto wand, then click two corners.",
                            NamedTextColor.RED);
                } else {
                    mapArt.place(player, args, ms.p1, ms.p2);
                }
            }
            case "mapclear" -> {
                Sel cs = selections.get(player.getUniqueId());
                if (cs == null || cs.p1 == null || cs.p2 == null
                        || !cs.world.equals(player.getWorld().getUID())) {
                    say(player, "Select the picture area first: /rwphoto wand, then click two corners.",
                            NamedTextColor.RED);
                } else {
                    mapArt.clear(player, cs.p1, cs.p2);
                }
            }
            default -> help(player);
        }
        return true;
    }

    private void help(CommandSender s) {
        say(s, "/rwphoto wand - get the selection wand", NamedTextColor.YELLOW);
        say(s, "/rwphoto list - show your pictures (click one)", NamedTextColor.YELLOW);
        say(s, "/rwphoto paste <file> [stretch] [flip] [r90|r180|r270] [dither|nodither]",
                NamedTextColor.YELLOW);
        say(s, "/rwphoto undo - undo the last paste", NamedTextColor.YELLOW);
        say(s, "/rwphoto map <file> [stretch] - real picture on a wall (maps)", NamedTextColor.YELLOW);
        say(s, "/rwphoto mapclear - remove map pictures in the selection", NamedTextColor.YELLOW);
        say(s, "/rwphoto reload - reload config", NamedTextColor.YELLOW);
    }

    // ---- List ---------------------------------------------------------------------

    private List<File> photoFiles() {
        File[] files = photosDir.listFiles(f -> f.isFile() && hasPhotoExtension(f.getName()));
        List<File> list = new ArrayList<>();
        if (files != null) {
            list.addAll(Arrays.asList(files));
        }
        list.sort(Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        return list;
    }

    private static boolean hasPhotoExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private void listPhotos(CommandSender sender) {
        List<File> files = photoFiles();
        if (files.isEmpty()) {
            say(sender, "No pictures found. Put .jpg files in plugins/RWPhoto/photos/ then run /rwphoto list again.",
                    NamedTextColor.RED);
            return;
        }
        say(sender, files.size() + " picture(s). Click one to get its paste command:", NamedTextColor.GOLD);
        int shown = 0;
        for (File f : files) {
            if (shown++ >= 60) {
                say(sender, "... and " + (files.size() - 60) + " more.", NamedTextColor.GRAY);
                break;
            }
            sender.sendMessage(Component.text("  \u2022 ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(f.getName(), NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.suggestCommand("/rwphoto paste " + f.getName()))
                            .hoverEvent(HoverEvent.showText(
                                    Component.text("Click to get the paste command", NamedTextColor.YELLOW)))));
        }
    }

    private File findPhoto(String name) {
        for (File f : photoFiles()) {
            String n = f.getName();
            if (n.equalsIgnoreCase(name)) {
                return f;
            }
            int dot = n.lastIndexOf('.');
            if (dot > 0 && n.substring(0, dot).equalsIgnoreCase(name)) {
                return f;
            }
        }
        return null;
        }    // ---- Paste --------------------------------------------------------------------

    private void paste(Player player, String[] args) {
        UUID id = player.getUniqueId();
        if (busy.contains(id)) {
            say(player, "Please wait, your last paste is still running.", NamedTextColor.RED);
            return;
        }

        boolean stretch = "stretch".equalsIgnoreCase(getConfig().getString("look.mode", "fit"));
        boolean flip = false;
        int rotate = 0;
        Boolean ditherOpt = null;
        List<String> nameParts = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            switch (args[i].toLowerCase(Locale.ROOT)) {
                case "stretch" -> stretch = true;
                case "fit" -> stretch = false;
                case "flip" -> flip = true;
                case "dither" -> ditherOpt = Boolean.TRUE;
                case "nodither" -> ditherOpt = Boolean.FALSE;
                case "r90" -> rotate = 1;
                case "r180" -> rotate = 2;
                case "r270" -> rotate = 3;
                default -> nameParts.add(args[i]);
            }
        }
        if (nameParts.isEmpty()) {
            say(player, "Usage: /rwphoto paste <file> [stretch] [flip] [r90|r180|r270] [dither|nodither]",
                    NamedTextColor.RED);
            return;
        }
        File file = findPhoto(String.join(" ", nameParts));
        if (file == null) {
            say(player, "Picture not found. Use /rwphoto list to see your pictures.", NamedTextColor.RED);
            return;
        }

        Sel sel = selections.get(id);
        if (sel == null || sel.p1 == null || sel.p2 == null) {
            say(player, "Select an area first: /rwphoto wand, then left/right click two corners.",
                    NamedTextColor.RED);
            return;
        }
        if (!sel.world.equals(player.getWorld().getUID())) {
            say(player, "Your selection is in a different world.", NamedTextColor.RED);
            return;
        }

        final int minX = Math.min(sel.p1[0], sel.p2[0]);
        final int maxX = Math.max(sel.p1[0], sel.p2[0]);
        final int minY = Math.min(sel.p1[1], sel.p2[1]);
        final int maxY = Math.max(sel.p1[1], sel.p2[1]);
        final int minZ = Math.min(sel.p1[2], sel.p2[2]);
        final int maxZ = Math.max(sel.p1[2], sel.p2[2]);
        int dx = maxX - minX + 1;
        int dy = maxY - minY + 1;
        int dz = maxZ - minZ + 1;

        final Plane plane;
        final int pw;
        final int ph;
        if (dy == 1 && dx > 1 && dz > 1) {
            plane = Plane.FLOOR;
            pw = dx;
            ph = dz;
        } else if (dx == 1 && dy > 1 && dz > 1) {
            plane = Plane.WALL_YZ;
            pw = dz;
            ph = dy;
        } else if (dz == 1 && dx > 1 && dy > 1) {
            plane = Plane.WALL_XY;
            pw = dx;
            ph = dy;
        } else {
            say(player, "The selection must be a flat area (1 block thick), for example a floor or a wall. "
                    + "Yours is " + dx + " x " + dy + " x " + dz + ".", NamedTextColor.RED);
            return;
        }

        int maxDim = getConfig().getInt("limits.max-dimension", 512);
        int maxBlocks = getConfig().getInt("limits.max-blocks", 160000);
        if (pw > maxDim || ph > maxDim || (long) pw * ph > maxBlocks) {
            say(player, "Area too big (" + pw + " x " + ph + "). Limit: " + maxDim + " per side, "
                    + maxBlocks + " blocks.", NamedTextColor.RED);
            return;
        }
        if (palette.size() < 2) {
            say(player, "The block palette is empty. Check palette.groups in config.yml.", NamedTextColor.RED);
            return;
        }

        // Which side is the viewer on (so walls are not mirrored)?
        final boolean viewerEastOrSouth;
        if (plane == Plane.WALL_YZ) {
            viewerEastOrSouth = player.getLocation().getX() >= minX + 0.5;
        } else {
            viewerEastOrSouth = player.getLocation().getZ() >= minZ + 0.5;
        }

        final ImageMapper.Settings settings = new ImageMapper.Settings();
        settings.dither = ditherOpt != null ? ditherOpt : getConfig().getBoolean("look.dither", false);
        settings.ditherStrength = (float) getConfig().getDouble("look.dither-strength", 0.6);
        settings.saturation = (float) getConfig().getDouble("look.saturation", 1.15);
        settings.contrast = (float) getConfig().getDouble("look.contrast", 1.08);
        settings.brightness = (float) getConfig().getDouble("look.brightness", 0.0);

        final List<Palette.Entry> paletteSnapshot = new ArrayList<>(palette);
        final boolean fStretch = stretch;
        final boolean fFlip = flip;
        final int fRotate = rotate;
        final World world = player.getWorld();

        busy.add(id);
        say(player, "Processing " + file.getName() + " ...", NamedTextColor.GRAY);

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                BufferedImage img = ImageMapper.read(file);
                ImageMapper.Result r = ImageMapper.map(img, pw, ph, fStretch, fRotate, fFlip,
                        paletteSnapshot, settings);
                getServer().getScheduler().runTask(this, () -> {
                    startPaste(player, world, plane, viewerEastOrSouth, minX, maxX, minY, maxY, minZ, maxZ,
                            pw, ph, r);
                });
            } catch (IOException | RuntimeException ex) {
                getServer().getScheduler().runTask(this, () -> {
                    busy.remove(id);
                    say(player, "Could not read the picture: " + ex.getMessage(), NamedTextColor.RED);
                });
            }
        });
    }

    private void startPaste(Player player, World world, Plane plane, boolean viewerEastOrSouth,
                            int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                            int pw, int ph, ImageMapper.Result r) {
        int offX = (pw - r.width) / 2;
        int offY = (ph - r.height) / 2;
        int n = r.width * r.height;
        int[] xs = new int[n];
        int[] ys = new int[n];
        int[] zs = new int[n];
        BlockData[] data = new BlockData[n];

        for (int iy = 0; iy < r.height; iy++) {
            for (int ix = 0; ix < r.width; ix++) {
                int cx = offX + ix;
                int cy = offY + iy;
                int i = iy * r.width + ix;
                switch (plane) {
                    case FLOOR -> {
                        xs[i] = minX + cx;
                        ys[i] = minY;
                        zs[i] = minZ + cy;
                    }
                    case WALL_YZ -> {
                        xs[i] = minX;
                        ys[i] = maxY - cy;
                        zs[i] = viewerEastOrSouth ? maxZ - cx : minZ + cx;
                    }
                    case WALL_XY -> {
                        xs[i] = viewerEastOrSouth ? minX + cx : maxX - cx;
                        ys[i] = maxY - cy;
                        zs[i] = minZ;
                    }
                }
                data[i] = blockData.get(r.cells[i].id);
            }
        }

        final BlockData[] old = new BlockData[n];
        final UUID id = player.getUniqueId();
        final int count = n;
        applyBlocks(world, xs, ys, zs, data, old, () -> {
            busy.remove(id);
            Undo u = new Undo();
            u.world = world.getUID();
            u.xs = xs;
            u.ys = ys;
            u.zs = zs;
            u.old = old;
            Deque<Undo> stack = undos.computeIfAbsent(id, k -> new ArrayDeque<>());
            stack.push(u);
            while (stack.size() > UNDO_LIMIT) {
                stack.removeLast();
            }
            if (player.isOnline()) {
                say(player, "Done! " + count + " blocks placed. Use /rwphoto undo to revert.",
                        NamedTextColor.GREEN);
            }
        });
    }

    private void undo(Player player) {
        UUID id = player.getUniqueId();
        if (busy.contains(id)) {
            say(player, "Please wait, a paste is still running.", NamedTextColor.RED);
            return;
        }
        Deque<Undo> stack = undos.get(id);
        if (stack == null || stack.isEmpty()) {
            say(player, "Nothing to undo.", NamedTextColor.RED);
            return;
        }
        Undo u = stack.pop();
        World world = getServer().getWorld(u.world);
        if (world == null) {
            say(player, "That world is not loaded.", NamedTextColor.RED);
            return;
        }
        busy.add(id);
        applyBlocks(world, u.xs, u.ys, u.zs, u.old, null, () -> {
            busy.remove(id);
            if (player.isOnline()) {
                say(player, "Undone.", NamedTextColor.GREEN);
            }
        });
    }

    /** Places blocks in small batches per tick so the server does not lag. */
    private void applyBlocks(World world, int[] xs, int[] ys, int[] zs, BlockData[] data,
                             BlockData[] oldOut, Runnable onDone) {
        final int perTick = Math.max(100, getConfig().getInt("limits.blocks-per-tick", 4000));
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                int end = Math.min(xs.length, i + perTick);
                for (; i < end; i++) {
                    BlockData d = data[i];
                    int y = ys[i];
                    if (d == null || y < world.getMinHeight() || y >= world.getMaxHeight()) {
                        continue;
                    }
                    Block b = world.getBlockAt(xs[i], y, zs[i]);
                    if (oldOut != null) {
                        oldOut[i] = b.getBlockData();
                    }
                    b.setBlockData(d, false);
                }
                if (i >= xs.length) {
                    cancel();
                    onDone.run();
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    // ---- Tab complete -------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("wand", "list", "paste", "map", "mapclear", "undo", "pos1", "pos2", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("paste") || args[0].equalsIgnoreCase("map"))) {
            for (File f : photoFiles()) {
                if (f.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(f.getName());
                }
            }
        } else if (args.length >= 3 && args[0].equalsIgnoreCase("paste")) {
            for (String s : List.of("stretch", "fit", "flip", "r90", "r180", "r270", "dither", "nodither")) {
                if (s.startsWith(args[args.length - 1].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    // ---- Helpers ------------------------------------------------------------------

    private void say(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(Component.text("[RWPhoto] ", NamedTextColor.GOLD)
                .append(Component.text(text, color)));
    }
            }
