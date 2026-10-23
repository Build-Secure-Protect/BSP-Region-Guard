package com.mrgregles.bsp_region_guard.claim;

import com.mrgregles.bsp_region_guard.region.Region;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * How a plot for sale or an open area looks in the world (owner decisions D105 to D114): the outline's colour and a
 * marker over its middle with its name. Kept in the region's type data, so a copied plot takes it along (D112).
 *
 * @param marker one of {@link #MARKERS}: just the outline, or the arrow, orbit sphere, spire beacon or for sale sign
 * @param colour the outline's and marker's colour, {@code 0xRRGGBB}
 * @param size   the marker's size, 0.5 to 3
 * @param text   the name's size, 0.5 to 3
 * @param height blocks above the ground in the plot's middle; drawn no higher than the plot's top
 */
public record PlotLook(String marker, int colour, float size, float text, int height) {
    public static final List<String> MARKERS = List.of("outline", "arrow", "sphere", "beacon", "sign");
    /** The eight colours on the PLOT LOOK window (D110); any other comes from the hex box. */
    public static final int[] COLOURS = {0xFFD23A, 0x19D3B0, 0xB58CFF, 0xFF6B5C, 0x5AA9FF, 0x7BE36B, 0xFF9F40, 0xF2F2F2};
    /** New plots: the arrow in gold (D106). */
    public static final PlotLook DEFAULT = new PlotLook("arrow", 0xFFD23A, 1f, 1f, 3);
    public static final float MIN_SIZE = 0.5f, MAX_SIZE = 3f;
    public static final int MAX_HEIGHT = 64;
    private static final String KEY = "look";

    public PlotLook {
        marker = MARKERS.contains(marker) ? marker : DEFAULT.marker;
        colour &= 0xFFFFFF;
        size = clampSize(size);
        text = clampSize(text);
        height = Math.max(0, Math.min(MAX_HEIGHT, height));
    }

    private static float clampSize(float v) {
        return Float.isNaN(v) ? 1f : Math.max(MIN_SIZE, Math.min(MAX_SIZE, Math.round(v * 4) / 4f));
    }

    /** The region's look, or the default when it has none. */
    public static PlotLook of(Region r) {
        CompoundTag t = r.typeData().getCompound(KEY);
        if (t.isEmpty()) {
            return DEFAULT;
        }
        return new PlotLook(t.getString("marker"), t.getInt("colour"), t.getFloat("size"), t.getFloat("text"), t.getInt("height"));
    }

    public void save(Region r) {
        CompoundTag t = new CompoundTag();
        t.putString("marker", marker);
        t.putInt("colour", colour);
        t.putFloat("size", size);
        t.putFloat("text", text);
        t.putInt("height", height);
        r.typeData().put(KEY, t);
    }

    public PlotLook withMarker(String m) {
        return new PlotLook(m, colour, size, text, height);
    }

    public PlotLook withColour(int c) {
        return new PlotLook(marker, c, size, text, height);
    }

    public PlotLook withSize(float s) {
        return new PlotLook(marker, colour, s, text, height);
    }

    public PlotLook withText(float t) {
        return new PlotLook(marker, colour, size, t, height);
    }

    public PlotLook withHeight(int h) {
        return new PlotLook(marker, colour, size, text, h);
    }

    /** {@code #FFD23A} */
    public String hex() {
        return String.format(Locale.ROOT, "#%06X", colour);
    }

    /** {@code #FFD23A}, {@code FFD23A} or {@code ffd23a}; null when it is not a colour. */
    @Nullable
    public static Integer parseColour(String s) {
        String v = s.trim();
        if (v.startsWith("#")) {
            v = v.substring(1);
        }
        if (!v.matches("[0-9a-fA-F]{6}")) {
            return null;
        }
        return Integer.parseInt(v, 16);
    }

    /** The screen's form, {@code arrow;#FFD23A;1.0;1.0;3}. */
    public String line() {
        return marker + ";" + hex() + ";" + size + ";" + text + ";" + height;
    }

    /** Reads {@link #line()}; null when any part is not valid. */
    @Nullable
    public static PlotLook parse(String line) {
        String[] p = line.trim().split(";");
        if (p.length != 5 || !MARKERS.contains(p[0])) {
            return null;
        }
        Integer colour = parseColour(p[1]);
        try {
            return colour == null ? null : new PlotLook(p[0], colour, Float.parseFloat(p[2]), Float.parseFloat(p[3]), Integer.parseInt(p[4]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(marker, 16);
        buf.writeInt(colour);
        buf.writeFloat(size);
        buf.writeFloat(text);
        buf.writeVarInt(height);
    }

    public static PlotLook read(FriendlyByteBuf buf) {
        return new PlotLook(buf.readUtf(16), buf.readInt(), buf.readFloat(), buf.readFloat(), buf.readVarInt());
    }
}
