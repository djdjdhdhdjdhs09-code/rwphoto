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
final class MapArt {

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

    private int register(World world, String fileName, List<Integer> mapIds) {
        int id = pictures.getInt("next", 1);
        pictures.set("next", id + 1);
        String path = "pictures." + id;
        pictures.set(path + ".file", fileName);
        pictures.set(path + ".world", world.getUID().toString());
        pictures.set(path + ".maps", mapIds);
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
    }

    private void removePicture(CommandSender to, int picId) {
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
