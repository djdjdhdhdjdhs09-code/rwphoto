package com.rwphoto;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.util.BoundingBox;

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
final class MapArt {

    private static final int TILE = 128;

    private final RWPhoto plugin;
    private final File photosDir;
    private final File mapsDir;
    private final Set<UUID> busy = new HashSet<>();

    MapArt(RWPhoto plugin) {
        this.plugin = plugin;
        this.photosDir = new File(plugin.getDataFolder(), "photos");
        this.mapsDir = new File(plugin.getDataFolder(), "maps");
        if (!mapsDir.exists() && !mapsDir.mkdirs()) {
            plugin.getLogger().warning("Could not create the maps folder: " + mapsDir);
        }
    }

    // ---- Load saved pictures when the server starts ------------------------------

    void loadAll() {
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
    }

    // ---- /rwphoto map <file> [stretch] ---------------------------------------------

    void place(Player player, String[] args, int[] p1, int[] p2) {
        UUID id = player.getUniqueId();
        if (busy.contains(id)) {
            say(player, "Please wait, the last map picture is still being made.", NamedTextColor.RED);
            return;
        }

        boolean stretch = false;
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
            say(player, "Usage: /rwphoto map <file> [stretch]", NamedTextColor.RED);
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
                        build(player, world, big, cols, rows, alongZ, viewerPositive,
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
    }

    private void build(Player player, World world, BufferedImage big, int cols, int rows,
                       boolean alongZ, boolean viewerPositive,
                       int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        int placed = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                BufferedImage tile = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = tile.createGraphics();
                g.drawImage(big.getSubimage(col * TILE, row * TILE, TILE, TILE), 0, 0, null);
                g.dispose();

                MapView view = Bukkit.createMap(world);
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
                placed++;
            }
        }
        if (player.isOnline()) {
            say(player, "Done! " + placed + " maps placed. Use /rwphoto mapclear to remove them.",
                    NamedTextColor.GREEN);
        }
    }

    private void spawnFrame(World world, int x, int y, int z, BlockFace face, ItemStack item) {
        Location loc = new Location(world, x, y, z);
        world.spawn(loc, ItemFrame.class, fr -> {
            fr.setFacingDirection(face, true);
            fr.setItem(item, false);
            fr.setItemDropChance(0f);
            fr.setVisible(false);
            fr.setFixed(true);
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
            fr.remove();
            removed++;
        }
        say(player, "Removed " + removed + " map frames.", NamedTextColor.GREEN);
    }

    // ---- Image scaling (high quality, no Bukkit classes) ---------------------------------

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
