package com.rwphoto;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Map art: shows a real picture on a wall using maps inside invisible item frames.
 * Each map is 128 x 128 pixels, so the picture is much sharper than block pictures.
 */
final class MapArt implements Listener {

    private static final int TILE = 128;

    private final RWPhoto plugin;
    private final File photosDir;
    private final File mapsDir;
    private final File picturesFile;
    private YamlConfiguration pictures;
    private final Set<UUID> busy = new HashSet<>();

    MapArt(RWPhoto plugin) {
        this.plugin = plugin;
        this.photosDir = new File(plugin.getDataFolder(), "photos");
        this.mapsDir = new File(plugin.getDataFolder(), "maps");
        if (!mapsDir.exists() && !mapsDir.mkdirs()) {
            plugin.getLogger().warning("Could not create the maps folder: " + mapsDir);
        }
        this.picturesFile = new File(mapsDir, "pictures.yml");
        this.pictures = YamlConfiguration.loadConfiguration(picturesFile);
    }

    // ---- Picture registry (each placed picture gets its own number) ----------------

    private void savePictures() {
        try {
            pictures.save(picturesFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save pictures.yml: " + ex.getMessage());
        }
    }

    private int register(World world, String fileName, List<Integer> mapIds, List<String> frames) {
        int id = pictures.getInt("next", 1);
        pictures.set("next", id + 1);
        String path = "pictures." + id;
        pictures.set(path + ".file", fileName);
        pictures.set(path + ".world", world.getUID().toString());
        pictures.set(path + ".maps", mapIds);
        pictures.set(path + ".frames", frames);
        savePictures();
        return id;
    }

    /** Returns the picture number that owns this map, or -1. */
    private int findPictureByMap(int mapId) {
        ConfigurationSection all = pictures.getConfigurationSection("pictures");
        if (all == null) {
            return -1;
        }
        for (String key : all.getKeys(false)) {
            if (pictures.getIntegerList("pictures." + key + ".maps").contains(mapId)) {
                try {
                    return Integer.parseInt(key);
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** Forget one map (its frame was removed on purpose). Drops the picture when no frames are left. */
    private void forgetMap(int mapId) {
        ConfigurationSection all = pictures.getConfigurationSection("pictures");
        if (all == null) {
            return;
        }
        for (String key : new ArrayList<>(all.getKeys(false))) {
            String path = "pictures." + key;
            List<String> frames = pictures.getStringList(path + ".frames");
            if (frames.isEmpty()) {
                continue;
            }
            List<String> keep = new ArrayList<>();
            for (String line : frames) {
                if (!line.endsWith(" " + mapId)) {
                    keep.add(line);
                }
            }
            if (keep.size() == frames.size()) {
                continue;
            }
            if (keep.isEmpty()) {
                pictures.set(path, null);
            } else {
                pictures.set(path + ".frames", keep);
            }
        }
    }

    private World worldOf(String uuid) {
        if (uuid == null) {
            return null;
        }
        try {
            return Bukkit.getWorld(UUID.fromString(uuid));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean entitiesReady(World w, int cx, int cz) {
        try {
            return w.getChunkAt(cx, cz).isEntitiesLoaded();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 0 = frame missing, 1 = this map is there, 2 = another map picture is there now. */
    private int frameState(World w, int x, int y, int z, BlockFace face, int mapId) {
        int other = 0;
        for (Entity e : w.getNearbyEntities(new BoundingBox(x, y, z, x + 1, y + 1, z + 1))) {
            if (!(e instanceof ItemFrame fr) || fr.getFacing() != face) {
                continue;
            }
            ItemStack it = fr.getItem();
            if (it.getType() != Material.FILLED_MAP || !(it.getItemMeta() instanceof MapMeta mm)) {
                continue;
            }
            MapView v = mm.getMapView();
            if (v != null && v.getId() == mapId) {
                return 1;
            }
            other = 2;
        }
        return other;
    }    private ItemStack mapItem(MapView view) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Self-repair: every 30 seconds, put back any picture frame that some other plugin
     * (or a chunk reload) removed. Only looks at chunks that are loaded.
     */
    private void heal() {
        ConfigurationSection all = pictures.getConfigurationSection("pictures");
        if (all == null) {
            return;
        }
        int restored = 0;
        boolean changed = false;
        for (String key : new ArrayList<>(all.getKeys(false))) {
            String path = "pictures." + key;
            World w = worldOf(pictures.getString(path + ".world"));
            List<String> frames = pictures.getStringList(path + ".frames");
            if (w == null || frames.isEmpty()) {
                continue;
            }
            List<String> keep = new ArrayList<>();
            for (String line : frames) {
                int x;
                int y;
                int z;
                int mapId;
                BlockFace face;
                try {
                    String[] p = line.split(" ");
                    x = Integer.parseInt(p[0]);
                    y = Integer.parseInt(p[1]);
                    z = Integer.parseInt(p[2]);
                    face = BlockFace.valueOf(p[3]);
                    mapId = Integer.parseInt(p[4]);
                } catch (RuntimeException ex) {
                    changed = true;
                    continue;
                }
                if (!w.isChunkLoaded(x >> 4, z >> 4) || !entitiesReady(w, x >> 4, z >> 4)) {
                    keep.add(line);
                    continue;
                }
                int state = frameState(w, x, y, z, face, mapId);
                if (state == 2) {
                    changed = true;
                    continue;
                }
                if (state == 0) {
                    MapView view = Bukkit.getMap(mapId);
                    if (view == null) {
                        changed = true;
                        continue;
                    }
                    spawnFrame(w, x, y, z, face, mapItem(view));
                    restored++;
                }
                keep.add(line);
            }
            if (keep.isEmpty()) {
                pictures.set(path, null);
                changed = true;
            } else if (keep.size() != frames.size()) {
                pictures.set(path + ".frames", keep);
                changed = true;
            }
        }
        if (changed) {
            savePictures();
        }
        if (restored > 0) {
            plugin.getLogger().info("Restored " + restored + " map picture frames that were removed by something else.");
        }
    }

    /** A map was (re)loaded by the server: put our picture back on it. */
    @EventHandler
    public void onMapInit(MapInitializeEvent e) {
        MapView view = e.getMap();
        File f = new File(mapsDir, "map_" + view.getId() + ".png");
        if (!f.exists()) {
            return;
        }
        try {
            BufferedImage img = ImageIO.read(f);
            if (img != null) {
                attach(view, img);
            }
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().warning("Could not load " + f.getName() + ": " + ex.getMessage());
        }
    }

    // ---- /rwphoto maplist ------------------------------------------------------------

    void list(Player player) {
        ConfigurationSection all = pictures.getConfigurationSection("pictures");
        if (all == null || all.getKeys(false).isEmpty()) {
            say(player, "No map pictures yet. Use /rwphoto map <file>.", NamedTextColor.RED);
            return;
        }
        say(player, "Map pictures (click one to get its remove command):", NamedTextColor.GOLD);
        for (String key : all.getKeys(false)) {
            String file = pictures.getString("pictures." + key + ".file", "?");
            int count = pictures.getIntegerList("pictures." + key + ".maps").size();
            player.sendMessage(Component.text("  #" + key + " ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(file + " (" + count + " maps)", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.suggestCommand("/rwphoto mapremove " + key))
                            .hoverEvent(HoverEvent.showText(
                                    Component.text("Click to remove this picture", NamedTextColor.YELLOW)))));
        }
    }

    // ---- /rwphoto mapremove [number]  (no number = the picture you are looking at) --------

    void remove(Player player, String[] args) {
        int picId;
        if (args.length >= 2) {
            try {
                picId = Integer.parseInt(args[1].replace("#", ""));
            } catch (NumberFormatException ex) {
                say(player, "Usage: /rwphoto mapremove [number]", NamedTextColor.RED);
                return;
            }
        } else {
            Location eye = player.getEyeLocation();
            RayTraceResult hit = player.getWorld().rayTraceEntities(eye, eye.getDirection(), 12,
                    e -> e instanceof ItemFrame);
            if (hit == null || !(hit.getHitEntity() instanceof ItemFrame fr)) {
                say(player, "Look at a map picture and run /rwphoto mapremove, or use /rwphoto maplist.",
                        NamedTextColor.RED);
                return;
            }
            ItemStack it = fr.getItem();
            if (it.getType() != Material.FILLED_MAP || !(it.getItemMeta() instanceof MapMeta mm)
                    || mm.getMapView() == null) {
                say(player, "That frame is not a map picture.", NamedTextColor.RED);
                return;
            }
            picId = findPictureByMap(mm.getMapView().getId());
            if (picId < 0) {
                say(player, "This picture has no number (it is an old one). Select its area and use "
                        + "/rwphoto mapclear.", NamedTextColor.RED);
                return;
            }
        }
        removePicture(player, picId);
                        }    private void removePicture(CommandSender to, int picId) {
        String path = "pictures." + picId;
        if (!pictures.isConfigurationSection(path)) {
            say(to, "No picture with number " + picId + ". Use /rwphoto maplist.", NamedTextColor.RED);
            return;
        }
        Set<Integer> ids = new HashSet<>(pictures.getIntegerList(path + ".maps"));
        World world = null;
        String ws = pictures.getString(path + ".world");
        if (ws != null) {
            try {
                world = Bukkit.getWorld(UUID.fromString(ws));
            } catch (IllegalArgumentException ignored) {
                world = null;
            }
        }
        int removed = 0;
        if (world != null) {
            for (ItemFrame fr : world.getEntitiesByClass(ItemFrame.class)) {
                ItemStack it = fr.getItem();
                if (it.getType() != Material.FILLED_MAP || !(it.getItemMeta() instanceof MapMeta mm)) {
                    continue;
                }
                MapView v = mm.getMapView();
                if (v != null && ids.contains(v.getId())) {
                    fr.remove();
                    removed++;
                }
            }
        }
        for (int mapId : ids) {
            File f = new File(mapsDir, "map_" + mapId + ".png");
            if (f.exists() && !f.delete()) {
                plugin.getLogger().warning("Could not delete " + f.getName());
            }
        }
        pictures.set(path, null);
        savePictures();
        say(to, "Removed picture #" + picId + " (" + removed + " frames).", NamedTextColor.GREEN);
    }

    // ---- Load saved pictures when the server starts ------------------------------

    void loadAll() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::heal, 200L, 600L);
        File[] files = mapsDir.listFiles(f -> f.isFile() && f.getName().startsWith("map_")
                && f.getName().endsWith(".png"));
        if (files == null) {
            return;
        }
        int ok = 0;
        for (File f : files) {
            String n = f.getName();
            try {
                int id = Integer.parseInt(n.substring(4, n.length() - 4));
                MapView view = Bukkit.getMap(id);
                if (view == null) {
                    continue;
                }
                BufferedImage img = ImageIO.read(f);
                if (img == null) {
                    continue;
                }
                attach(view, img);
                ok++;
            } catch (IOException | RuntimeException ex) {
                plugin.getLogger().warning("Could not load " + n + ": " + ex.getMessage());
            }
        }
        if (ok > 0) {
            plugin.getLogger().info("Loaded " + ok + " map pictures.");
        }
    }

    private void attach(MapView view, BufferedImage img) {
        for (MapRenderer r : new ArrayList<>(view.getRenderers())) {
            view.removeRenderer(r);
        }
        view.addRenderer(new TileRenderer(img));
        view.setTrackingPosition(false);
    }

    private static final class TileRenderer extends MapRenderer {
        private final BufferedImage image;
        private boolean done;

        TileRenderer(BufferedImage image) {
            super(false);
            this.image = image;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            if (done) {
                return;
            }
            canvas.drawImage(0, 0, image);
            done = true;
        }
    }    // ---- /rwphoto map <file> [stretch] ---------------------------------------------

    void place(Player player, String[] args, int[] p1, int[] p2) {
        UUID id = player.getUniqueId();
        if (busy.contains(id)) {
            say(player, "Please wait, the last map picture is still being made.", NamedTextColor.RED);
            return;
        }

        // Default: the picture fills the whole selected area. Add "fit" to keep the picture's proportions.
        boolean stretch = true;
        List<String> nameParts = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i].toLowerCase(Locale.ROOT);
            if (a.equals("stretch")) {
                stretch = true;
            } else if (a.equals("fit")) {
                stretch = false;
            } else {
                nameParts.add(args[i]);
            }
        }
        if (nameParts.isEmpty()) {
            say(player, "Usage: /rwphoto map <file> [fit]", NamedTextColor.RED);
            return;
        }
        File file = findPhoto(String.join(" ", nameParts));
        if (file == null) {
            say(player, "Picture not found. Use /rwphoto list to see your pictures.", NamedTextColor.RED);
            return;
        }

        final int minX = Math.min(p1[0], p2[0]);
        final int maxX = Math.max(p1[0], p2[0]);
        final int minY = Math.min(p1[1], p2[1]);
        final int maxY = Math.max(p1[1], p2[1]);
        final int minZ = Math.min(p1[2], p2[2]);
        final int maxZ = Math.max(p1[2], p2[2]);
        int dx = maxX - minX + 1;
        int dy = maxY - minY + 1;
        int dz = maxZ - minZ + 1;

        final boolean alongZ;
        final int cols;
        if (dx == 1 && dz > 1) {
            alongZ = true;
            cols = dz;
        } else if (dz == 1 && dx > 1) {
            alongZ = false;
            cols = dx;
        } else {
            say(player, "Select a vertical wall that is 1 block thick (for example 9 wide and 10 tall). "
                    + "Yours is " + dx + " x " + dy + " x " + dz + ".", NamedTextColor.RED);
            return;
        }
        final int rows = dy;
        int maxMaps = plugin.getConfig().getInt("map.max-maps", 100);
        if (cols * rows > maxMaps) {
            say(player, "Too many maps (" + cols * rows + "). Limit: " + maxMaps + ".", NamedTextColor.RED);
            return;
        }

        // Which side of the wall is the viewer on?
        final boolean viewerPositive = alongZ
                ? player.getLocation().getX() >= minX + 0.5
                : player.getLocation().getZ() >= minZ + 0.5;

        final boolean fStretch = stretch;
        final World world = player.getWorld();
        busy.add(id);
        say(player, "Making " + (cols * rows) + " maps from " + file.getName() + " ...", NamedTextColor.GRAY);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BufferedImage src = ImageMapper.read(file);
                BufferedImage big = fit(src, cols * TILE, rows * TILE, fStretch);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        build(player, world, file.getName(), big, cols, rows, alongZ, viewerPositive,
                                minX, maxX, minY, maxY, minZ, maxZ);
                    } finally {
                        busy.remove(id);
                    }
                });
            } catch (IOException | RuntimeException ex) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    busy.remove(id);
                    say(player, "Could not read the picture: " + ex.getMessage(), NamedTextColor.RED);
                });
            }
        });
    }    private void build(Player player, World world, String fileName, BufferedImage big, int cols, int rows,
                       boolean alongZ, boolean viewerPositive,
                       int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        int placed = 0;
        List<Integer> mapIds = new ArrayList<>();
        List<String> frameList = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                BufferedImage tile = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = tile.createGraphics();
                g.drawImage(big.getSubimage(col * TILE, row * TILE, TILE, TILE), 0, 0, null);
                g.dispose();

                MapView view = Bukkit.createMap(world);
                mapIds.add(view.getId());
                attach(view, tile);
                try {
                    ImageIO.write(tile, "png", new File(mapsDir, "map_" + view.getId() + ".png"));
                } catch (IOException ex) {
                    plugin.getLogger().warning("Could not save map " + view.getId() + ": " + ex.getMessage());
                }

                ItemStack item = new ItemStack(Material.FILLED_MAP);
                MapMeta meta = (MapMeta) item.getItemMeta();
                meta.setMapView(view);
                item.setItemMeta(meta);

                int y = maxY - row;
                int fx;
                int fz;
                BlockFace face;
                if (alongZ) {
                    fx = viewerPositive ? minX + 1 : minX - 1;
                    fz = viewerPositive ? maxZ - col : minZ + col;
                    face = viewerPositive ? BlockFace.EAST : BlockFace.WEST;
                } else {
                    fx = viewerPositive ? minX + col : maxX - col;
                    fz = viewerPositive ? minZ + 1 : minZ - 1;
                    face = viewerPositive ? BlockFace.SOUTH : BlockFace.NORTH;
                }
                spawnFrame(world, fx, y, fz, face, item);
                frameList.add(fx + " " + y + " " + fz + " " + face.name() + " " + view.getId());
                placed++;
            }
        }
        int picId = register(world, fileName, mapIds, frameList);
        if (player.isOnline()) {
            say(player, "Done! Picture #" + picId + " (" + placed + " maps). Look at it and use "
                    + "/rwphoto mapremove to remove it.", NamedTextColor.GREEN);
        }
    }

    private void spawnFrame(World world, int x, int y, int z, BlockFace face, ItemStack item) {
        // Replace an older map picture in the same spot instead of stacking on top of it.
        BoundingBox cell = new BoundingBox(x, y, z, x + 1, y + 1, z + 1);
        for (Entity e : world.getNearbyEntities(cell)) {
            if (e instanceof ItemFrame old && old.getFacing() == face
                    && old.getItem().getType() == Material.FILLED_MAP) {
                old.remove();
            }
        }
        Location loc = new Location(world, x, y, z);
        world.spawn(loc, ItemFrame.class, fr -> {
            fr.setFacingDirection(face, true);
            fr.setItem(item, false);
            fr.setItemDropChance(0f);
            fr.setVisible(false);
            fr.setFixed(true);
            fr.setInvulnerable(true);
        });
    }

    // ---- /rwphoto mapclear -------------------------------------------------------------

    /** Removes the map frames made by this plugin inside (and just in front of) the selection. */
    void clear(Player player, int[] p1, int[] p2) {
        World world = player.getWorld();
        BoundingBox box = new BoundingBox(
                Math.min(p1[0], p2[0]) - 1, Math.min(p1[1], p2[1]), Math.min(p1[2], p2[2]) - 1,
                Math.max(p1[0], p2[0]) + 2, Math.max(p1[1], p2[1]) + 1, Math.max(p1[2], p2[2]) + 2);
        int removed = 0;
        for (Entity e : world.getNearbyEntities(box)) {
            if (!(e instanceof ItemFrame fr)) {
                continue;
            }
            ItemStack it = fr.getItem();
            if (it.getType() != Material.FILLED_MAP) {
                continue;
            }
            if (!(it.getItemMeta() instanceof MapMeta mm)) {
                continue;
            }
            MapView v = mm.getMapView();
            if (v == null || !new File(mapsDir, "map_" + v.getId() + ".png").exists()) {
                continue;
            }
            forgetMap(v.getId());
            fr.remove();
            removed++;
        }
        if (removed > 0) {
            savePictures();
        }
        say(player, "Removed " + removed + " map frames.", NamedTextColor.GREEN);
    }    // ---- Image scaling (high quality, no Bukkit classes) ---------------------------------

    static BufferedImage fit(BufferedImage src, int tw, int th, boolean stretch) {
        BufferedImage flat = flatten(src);
        int w;
        int h;
        if (stretch) {
            w = tw;
            h = th;
        } else {
            double s = Math.min(tw / (double) flat.getWidth(), th / (double) flat.getHeight());
            w = Math.max(1, Math.min(tw, (int) Math.round(flat.getWidth() * s)));
            h = Math.max(1, Math.min(th, (int) Math.round(flat.getHeight() * s)));
        }
        BufferedImage scaled = scale(flat, w, h);
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, tw, th);
        g.drawImage(scaled, (tw - w) / 2, (th - h) / 2, null);
        g.dispose();
        return out;
    }

    private static BufferedImage flatten(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            cur = resize(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        return resize(cur, w, h);
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    // ---- Helpers --------------------------------------------------------------------------

    private File findPhoto(String name) {
        File[] files = photosDir.listFiles(f -> f.isFile());
        if (files == null) {
            return null;
        }
        List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort(Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        for (File f : list) {
            String n = f.getName();
            String lower = n.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"))) {
                continue;
            }
            if (n.equalsIgnoreCase(name)) {
                return f;
            }
            int dot = n.lastIndexOf('.');
            if (dot > 0 && n.substring(0, dot).equalsIgnoreCase(name)) {
                return f;
            }
        }
        return null;
    }

    private void say(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(Component.text("[RWPhoto] ", NamedTextColor.GOLD)
                .append(Component.text(text, color)));
    }
}
