package com.rwphoto;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Turns an image into a grid of palette blocks.
 * Steps: area-average resize in linear light, rotate/flip, color enhance,
 * then nearest-color matching in OKLab with optional Floyd-Steinberg dithering.
 * No Bukkit classes in here.
 */
final class ImageMapper {

    private ImageMapper() {
    }

    static final class Settings {
        boolean dither = false;
        float ditherStrength = 0.6f;
        float saturation = 1.15f;
        float contrast = 1.08f;
        float brightness = 0f;
    }

    static final class Result {
        final int width;
        final int height;
        final Palette.Entry[] cells; // row-major, width * height

        Result(int width, int height, Palette.Entry[] cells) {
            this.width = width;
            this.height = height;
            this.cells = cells;
        }
    }

    private static final float[] LUT = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            LUT[i] = Palette.toLinear(i / 255f);
        }
    }

    static BufferedImage read(File file) throws IOException {
        BufferedImage img = ImageIO.read(file);
        if (img == null) {
            throw new IOException("Unsupported or corrupt image: " + file.getName());
        }
        return img;
    }

    /**
     * @param planeW  width of the area to fill, in blocks
     * @param planeH  height of the area to fill, in blocks
     * @param stretch true = fill the whole area, false = keep the picture's proportions
     * @param rotate  number of 90 degree clockwise turns
     */
    static Result map(BufferedImage src, int planeW, int planeH, boolean stretch, int rotate, boolean flip,
                      List<Palette.Entry> palette, Settings s) {
        rotate = ((rotate % 4) + 4) % 4;
        boolean odd = (rotate & 1) == 1;
        int effW = odd ? src.getHeight() : src.getWidth();
        int effH = odd ? src.getWidth() : src.getHeight();

        int tw;
        int th;
        if (stretch) {
            tw = planeW;
            th = planeH;
        } else {
            double scale = Math.min(planeW / (double) effW, planeH / (double) effH);
            tw = clampInt((int) Math.round(effW * scale), 1, planeW);
            th = clampInt((int) Math.round(effH * scale), 1, planeH);
        }

        int rw = odd ? th : tw;
        int rh = odd ? tw : th;
        float[] lin = resample(src, rw, rh);
        lin = rotateFlip(lin, rw, rh, rotate, flip);

        enhance(lin, tw * th, s);
        return new Result(tw, th, quantize(lin, tw, th, palette, s));
    }

    // ---- Resize (area average in linear light) --------------------------------

    private static float[] resample(BufferedImage src, int tw, int th) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        float[] out = new float[tw * th * 3];
        double[] acc = new double[tw * 3];
        int[] row = new int[sw];
        double fx = sw / (double) tw;
        double fy = sh / (double) th;

        for (int ty = 0; ty < th; ty++) {
            Arrays.fill(acc, 0.0);
            double y0 = ty * fy;
            double y1 = (ty + 1) * fy;
            int iy0 = (int) Math.floor(y0);
            int iy1 = Math.min(sh - 1, (int) Math.ceil(y1) - 1);
            for (int iy = iy0; iy <= iy1; iy++) {
                double wy = Math.min(iy + 1, y1) - Math.max(iy, y0);
                if (wy <= 0) {
                    continue;
                }
                src.getRGB(0, iy, sw, 1, row, 0, sw);
                for (int tx = 0; tx < tw; tx++) {
                    double x0 = tx * fx;
                    double x1 = (tx + 1) * fx;
                    int ix0 = (int) Math.floor(x0);
                    int ix1 = Math.min(sw - 1, (int) Math.ceil(x1) - 1);
                    for (int ix = ix0; ix <= ix1; ix++) {
                        double w = wy * (Math.min(ix + 1, x1) - Math.max(ix, x0));
                        if (w <= 0) {
                            continue;
                        }
                        int p = row[ix];
                        int a = p >>> 24;
                        int r = (p >> 16) & 255;
                        int g = (p >> 8) & 255;
                        int b = p & 255;
                        if (a < 255) { // transparent parts become white
                            r = (r * a + 255 * (255 - a)) / 255;
                            g = (g * a + 255 * (255 - a)) / 255;
                            b = (b * a + 255 * (255 - a)) / 255;
                        }
                        acc[tx * 3] += w * LUT[r];
                        acc[tx * 3 + 1] += w * LUT[g];
                        acc[tx * 3 + 2] += w * LUT[b];
                    }
                }
            }
            double norm = fx * fy;
            for (int tx = 0; tx < tw; tx++) {
                int o = (ty * tw + tx) * 3;
                out[o] = (float) (acc[tx * 3] / norm);
                out[o + 1] = (float) (acc[tx * 3 + 1] / norm);
                out[o + 2] = (float) (acc[tx * 3 + 2] / norm);
            }
        }
        return out;
    }

    private static float[] rotateFlip(float[] in, int iw, int ih, int rot, boolean flip) {
        if (rot == 0 && !flip) {
            return in;
        }
        int ow = (rot & 1) == 1 ? ih : iw;
        int oh = (rot & 1) == 1 ? iw : ih;
        float[] out = new float[ow * oh * 3];
        for (int y = 0; y < ih; y++) {
            for (int x = 0; x < iw; x++) {
                int nx;
                int ny;
                switch (rot) {
                    case 1 -> {
                        nx = ih - 1 - y;
                        ny = x;
                    }
                    case 2 -> {
                        nx = iw - 1 - x;
                        ny = ih - 1 - y;
                    }
                    case 3 -> {
                        nx = y;
                        ny = iw - 1 - x;
                    }
                    default -> {
                        nx = x;
                        ny = y;
                    }
                }
                if (flip) {
                    nx = ow - 1 - nx;
                }
                int from = (y * iw + x) * 3;
                int to = (ny * ow + nx) * 3;
                out[to] = in[from];
                out[to + 1] = in[from + 1];
                out[to + 2] = in[from + 2];
            }
        }
        return out;
    }

    // ---- Color enhancement ------------------------------------------------------

    /** More vivid, slightly punchier colors so the limited block palette still looks good. */
    private static void enhance(float[] lin, int pixels, Settings s) {
        for (int i = 0; i < pixels; i++) {
            float r = Palette.toSrgb(lin[i * 3]);
            float g = Palette.toSrgb(lin[i * 3 + 1]);
            float b = Palette.toSrgb(lin[i * 3 + 2]);
            float lum = 0.299f * r + 0.587f * g + 0.114f * b;
            r = lum + (r - lum) * s.saturation;
            g = lum + (g - lum) * s.saturation;
            b = lum + (b - lum) * s.saturation;
            r = (r - 0.5f) * s.contrast + 0.5f + s.brightness;
            g = (g - 0.5f) * s.contrast + 0.5f + s.brightness;
            b = (b - 0.5f) * s.contrast + 0.5f + s.brightness;
            lin[i * 3] = Palette.toLinear(clamp01(r));
            lin[i * 3 + 1] = Palette.toLinear(clamp01(g));
            lin[i * 3 + 2] = Palette.toLinear(clamp01(b));
        }
    }

    // ---- Matching + dithering ---------------------------------------------------

    private static Palette.Entry[] quantize(float[] lin, int w, int h, List<Palette.Entry> palette, Settings s) {
        Palette.Entry[] pal = palette.toArray(new Palette.Entry[0]);
        float[] pl = new float[pal.length * 3];
        for (int i = 0; i < pal.length; i++) {
            pl[i * 3] = LUT[(pal[i].rgb >> 16) & 255];
            pl[i * 3 + 1] = LUT[(pal[i].rgb >> 8) & 255];
            pl[i * 3 + 2] = LUT[pal[i].rgb & 255];
        }

        float strength = s.dither ? s.ditherStrength : 0f;
        Palette.Entry[] cells = new Palette.Entry[w * h];
        float[] lab = new float[3];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 3;
                float r = clamp01(lin[i]);
                float g = clamp01(lin[i + 1]);
                float b = clamp01(lin[i + 2]);
                Palette.oklab(r, g, b, lab);

                int best = 0;
                float bestDist = Float.MAX_VALUE;
                for (int k = 0; k < pal.length; k++) {
                    float dl = pal[k].l - lab[0];
                    float da = pal[k].a - lab[1];
                    float db = pal[k].b - lab[2];
                    float d = dl * dl + da * da + db * db;
                    if (d < bestDist) {
                        bestDist = d;
                        best = k;
                    }
                }
                cells[y * w + x] = pal[best];

                if (strength > 0f) {
                    // Small, limited error keeps the dithering calm instead of noisy.
                    float er = clampErr((r - pl[best * 3]) * strength);
                    float eg = clampErr((g - pl[best * 3 + 1]) * strength);
                    float eb = clampErr((b - pl[best * 3 + 2]) * strength);
                    spread(lin, w, h, x + 1, y, er, eg, eb, 7f / 16f);
                    spread(lin, w, h, x - 1, y + 1, er, eg, eb, 3f / 16f);
                    spread(lin, w, h, x, y + 1, er, eg, eb, 5f / 16f);
                    spread(lin, w, h, x + 1, y + 1, er, eg, eb, 1f / 16f);
                }
            }
        }
        return cells;
    }

    private static void spread(float[] a, int w, int h, int x, int y, float er, float eg, float eb, float f) {
        if (x < 0 || x >= w || y < 0 || y >= h) {
            return;
        }
        int i = (y * w + x) * 3;
        a[i] += er * f;
        a[i + 1] += eg * f;
        a[i + 2] += eb * f;
    }

    private static float clampErr(float v) {
        return v < -0.08f ? -0.08f : (v > 0.08f ? 0.08f : v);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
              }
