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
        boolean fresh = false;
        if (sel.p1 != null && sel.p2 != null) {
            // The last selection was complete: this click starts a brand new, separate selection.
            sel.p1 = null;
            sel.p2 = null;
            fresh = true;
        }
        if (first) {
            sel.p1 = new int[] {x, y, z};
        } else {
            sel.p2 = new int[] {x, y, z};
        }
        String text = (fresh ? "New selection. " : "") + (first ? "Position 1" : "Position 2")
                + " set: " + x + ", " + y + ", " + z;
        if (sel.p1 != null && sel.p2 != null) {
            text += "  (" + (Math.abs(sel.p1[0] - sel.p2[0]) + 1) + " x "
                    + (Math.abs(sel.p1[1] - sel.p2[1]) + 1) + " x "
                    + (Math.abs(sel.p1[2] - sel.p2[2]) + 1) + ")";
        }
        say(player, text, NamedTextColor.GREEN);
    }
