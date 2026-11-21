package com.rwphoto;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Block color tables and color math (sRGB, linear light, OKLab).
 * No Bukkit classes in here so the logic stays simple and testable.
 */
final class Palette {

    private Palette() {
    }

    /** One usable block: its Minecraft material name and its color. */
    static final class Entry {
        final String id;
        final int rgb;
        final float l;
        final float a;
        final float b;

        Entry(String id, int rgb) {
            this.id = id;
            this.rgb = rgb;
            float[] lab = new float[3];
            oklab(toLinear(((rgb >> 16) & 255) / 255f),
                    toLinear(((rgb >> 8) & 255) / 255f),
                    toLinear((rgb & 255) / 255f), lab);
            this.l = lab[0];
            this.a = lab[1];
            this.b = lab[2];
        }
    }

    private static final Map<String, String> GROUPS = new LinkedHashMap<>();

    static {
        // Format: "material_name R G B;material_name R G B;..."  (average texture color)
        GROUPS.put("concrete",
                "white_concrete 207 213 214;orange_concrete 224 97 0;magenta_concrete 169 48 159;"
                        + "light_blue_concrete 36 137 199;yellow_concrete 240 175 21;lime_concrete 94 168 24;"
                        + "pink_concrete 213 101 142;gray_concrete 54 57 61;light_gray_concrete 125 125 115;"
                        + "cyan_concrete 21 119 136;purple_concrete 100 31 156;blue_concrete 44 46 143;"
                        + "brown_concrete 96 60 32;green_concrete 73 91 36;red_concrete 142 33 33;"
                        + "black_concrete 8 10 15");
        GROUPS.put("wool",
                "white_wool 233 236 236;orange_wool 240 118 19;magenta_wool 189 68 179;"
                        + "light_blue_wool 58 175 217;yellow_wool 248 197 39;lime_wool 112 185 25;"
                        + "pink_wool 237 141 172;gray_wool 62 68 71;light_gray_wool 142 142 134;"
                        + "cyan_wool 21 137 145;purple_wool 121 42 172;blue_wool 53 57 157;"
                        + "brown_wool 114 71 40;green_wool 84 109 27;red_wool 160 39 34;"
                        + "black_wool 20 21 25");
        GROUPS.put("terracotta",
                "terracotta 152 94 67;white_terracotta 209 178 161;orange_terracotta 161 83 37;"
                        + "magenta_terracotta 149 88 108;light_blue_terracotta 113 108 137;"
                        + "yellow_terracotta 186 133 35;lime_terracotta 103 117 53;pink_terracotta 161 78 78;"
                        + "gray_terracotta 57 42 35;light_gray_terracotta 135 106 97;cyan_terracotta 86 91 91;"
                        + "purple_terracotta 118 70 86;blue_terracotta 74 59 91;brown_terracotta 77 51 36;"
                        + "green_terracotta 76 83 42;red_terracotta 143 61 46;black_terracotta 37 22 16");
        GROUPS.put("neutral",
                "stone 125 125 125;smooth_stone 158 158 158;andesite 136 136 137;"
                        + "polished_andesite 132 135 134;diorite 188 188 189;polished_diorite 193 193 195;"
                        + "quartz_block 236 230 223;snow_block 249 254 254;calcite 223 224 220;tuff 108 109 102;"
                        + "polished_deepslate 72 72 73;blackstone 42 36 41;polished_blackstone 53 49 56;"
                        + "coal_block 16 15 15");
        GROUPS.put("wood",
                "oak_planks 162 130 78;spruce_planks 114 84 48;birch_planks 192 175 121;"
                        + "jungle_planks 160 115 81;acacia_planks 168 90 50;dark_oak_planks 66 43 20;"
                        + "mangrove_planks 117 54 48;cherry_planks 226 178 172;bamboo_planks 193 173 80;"
                        + "crimson_planks 101 48 70;warped_planks 43 104 99");
        GROUPS.put("extra",
                "gold_block 249 236 79;emerald_block 42 203 87;diamond_block 98 237 228;"
                        + "lapis_block 31 67 140;redstone_block 175 24 5;amethyst_block 133 97 191;"
                        + "purpur_block 169 125 169;prismarine 99 171 158;dark_prismarine 51 91 75;"
                        + "warped_wart_block 22 119 121;nether_wart_block 114 2 2;copper_block 192 107 79;"
                        + "exposed_copper 161 125 103;weathered_copper 108 153 110;oxidized_copper 82 162 132;"
                        + "sandstone 216 203 155;red_sandstone 186 99 29;dirt 134 96 67;clay 160 166 179;"
                        + "mud_bricks 137 104 79;bricks 150 97 83;moss_block 89 109 45;end_stone 219 222 158;"
                        + "netherrack 97 38 38;bone_block 229 225 207;hay_block 166 139 12;melon 111 145 30;"
                        + "honeycomb_block 229 148 29;shroomlight 240 146 70;glowstone 171 131 84");
    }

    static List<String> groupNames() {
        return new ArrayList<>(GROUPS.keySet());
    }

    /** Builds the list of entries for the given group names (unknown names are ignored). */
    static List<Entry> build(Collection<String> groups) {
        List<Entry> out = new ArrayList<>();
        for (String group : groups) {
            String data = GROUPS.get(group.toLowerCase(Locale.ROOT));
            if (data == null) {
                continue;
            }
            for (String item : data.split(";")) {
                String[] p = item.trim().split("\\s+");
                if (p.length != 4) {
                    continue;
                }
                int rgb = (Integer.parseInt(p[1]) << 16) | (Integer.parseInt(p[2]) << 8) | Integer.parseInt(p[3]);
                out.add(new Entry(p[0], rgb));
            }
        }
        return out;
    }

    // ---- Color math ---------------------------------------------------------

    static float toLinear(float c) {
        if (c <= 0.04045f) {
            return c / 12.92f;
        }
        return (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
    }

    static float toSrgb(float c) {
        if (c <= 0f) {
            return 0f;
        }
        if (c >= 1f) {
            return 1f;
        }
        if (c <= 0.0031308f) {
            return c * 12.92f;
        }
        return (float) (1.055 * Math.pow(c, 1.0 / 2.4) - 0.055);
    }

    /** Linear RGB (0..1) to OKLab. out = {L, a, b}. */
    static void oklab(float r, float g, float b, float[] out) {
        float l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b;
        float m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b;
        float s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b;
        float l3 = (float) Math.cbrt(l);
        float m3 = (float) Math.cbrt(m);
        float s3 = (float) Math.cbrt(s);
        out[0] = 0.2104542553f * l3 + 0.7936177850f * m3 - 0.0040720468f * s3;
        out[1] = 1.9779984951f * l3 - 2.4285922050f * m3 + 0.4505937099f * s3;
        out[2] = 0.0259040371f * l3 + 0.7827717662f * m3 - 0.8086757660f * s3;
    }
                        }
