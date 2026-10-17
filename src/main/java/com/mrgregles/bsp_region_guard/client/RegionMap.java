package com.mrgregles.bsp_region_guard.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mrgregles.bsp_region_guard.BSPRegionGuard;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.network.RegionListPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The MAP tab: a top-down map drawn from the terrain the client has loaded (the way BSP Core's chunk picker does),
 * with every region of the world as a tinted box. Unloaded chunks are dark; the map centres on the selected region
 * and picks a scale that fits it, which the mouse wheel can override.
 */
public final class RegionMap {
    static final int W = 262, H = 118;
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(BSPRegionGuard.MODID, "region_map");
    private static final int UNLOADED = 0x1A1E26;
    @Nullable
    private static DynamicTexture texture;
    private static long builtAt = Long.MIN_VALUE;
    private static int builtCx, builtCz, builtBpp;

    /** What the map currently shows: centre block and blocks per pixel. */
    int cx, cz, bpp = 1;

    private RegionMap() {
    }

    static RegionMap create() {
        return new RegionMap();
    }

    /** Frames the region (or the player) with some margin, unless the user has zoomed by hand. */
    void frame(@Nullable Cuboid bounds, BlockPos player, boolean keepZoom) {
        if (bounds == null) {
            cx = player.getX();
            cz = player.getZ();
            if (!keepZoom) {
                bpp = 2;
            }
            return;
        }
        cx = (bounds.minX() + bounds.maxX() + 1) / 2;
        cz = (bounds.minZ() + bounds.maxZ() + 1) / 2;
        if (!keepZoom) {
            int need = Math.max((int) Math.ceil(bounds.sizeX() * 1.4 / W), (int) Math.ceil(bounds.sizeZ() * 1.4 / H));
            bpp = Math.max(1, Math.min(64, need));
        }
    }

    void zoom(int steps) {
        int[] stops = {1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64};
        int i = 0;
        for (int k = 0; k < stops.length; k++) {
            if (stops[k] <= bpp) {
                i = k;
            }
        }
        i = Math.max(0, Math.min(stops.length - 1, i + steps));
        bpp = stops[i];
    }

    int blockX(int x, int px) {
        return cx + (px - x - W / 2) * bpp;
    }

    int blockZ(int y, int py) {
        return cz + (py - y - H / 2) * bpp;
    }

    private int pixelX(int x, int bx) {
        return x + W / 2 + Math.floorDiv(bx - cx, bpp);
    }

    private int pixelZ(int y, int bz) {
        return y + H / 2 + Math.floorDiv(bz - cz, bpp);
    }

    /** Rebuilds the terrain texture when the view moved or every two seconds (chunks keep arriving). */
    private void ensure(Level level) {
        Minecraft mc = Minecraft.getInstance();
        if (texture == null) {
            texture = new DynamicTexture(W, H, false);
            mc.getTextureManager().register(TEXTURE, texture);
        }
        long now = level.getGameTime();
        if (cx == builtCx && cz == builtCz && bpp == builtBpp && now - builtAt < 40 && now >= builtAt) {
            return;
        }
        builtCx = cx;
        builtCz = cz;
        builtBpp = bpp;
        builtAt = now;
        NativeImage image = texture.getPixels();
        if (image == null) {
            return;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x0 = cx - (W / 2) * bpp, z0 = cz - (H / 2) * bpp;
        for (int px = 0; px < W; px++) {
            for (int pz = 0; pz < H; pz++) {
                int wx = x0 + px * bpp, wz = z0 + pz * bpp, rgb = UNLOADED;
                if (level.hasChunk(wx >> 4, wz >> 4)) {
                    int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
                    MapColor colour = MapColor.NONE;
                    for (int y = top - 1; y >= Math.max(level.getMinBuildHeight(), top - 8) && colour == MapColor.NONE; y--) {
                        colour = level.getBlockState(p.set(wx, y, wz)).getMapColor(level, p);
                    }
                    if (colour != MapColor.NONE) {
                        int north = level.hasChunk(wx >> 4, (wz - bpp) >> 4) ? level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz - bpp) : top;
                        int shade = top > north ? 255 : top < north ? 180 : 220;
                        rgb = ((colour.col >> 16 & 0xFF) * shade / 255) << 16 | ((colour.col >> 8 & 0xFF) * shade / 255) << 8 | (colour.col & 0xFF) * shade / 255;
                    }
                }
                image.setPixelRGBA(px, pz, 0xFF000000 | (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF));
            }
        }
        texture.upload();
    }

    /** Draws the map with its top-left corner at {@code x, y}. Returns the region under the mouse, if any. */
    @Nullable
    RegionListPacket.Summary draw(GuiGraphics g, Font font, int x, int y, List<RegionListPacket.Summary> regions, @Nullable String selected,
                                  BlockPos player, int mouseX, int mouseY) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        ensure(level);
        g.blit(TEXTURE, x, y, W, H, 0f, 0f, W, H, W, H);
        HoloScreen.scissor(g, x, y, x + W, y + H);
        RegionListPacket.Summary hovered = null;
        int hoveredArea = Integer.MAX_VALUE;
        for (RegionListPacket.Summary s : regions) {
            Cuboid b = s.bounds();
            if (b == null) {
                continue;
            }
            int x1 = pixelX(x, b.minX()), x2 = pixelX(x, b.maxX() + 1), z1 = pixelZ(y, b.minZ()), z2 = pixelZ(y, b.maxZ() + 1);
            if (x2 <= x || x1 >= x + W || z2 <= y || z1 >= y + H) {
                continue;
            }
            x2 = Math.max(x2, x1 + 1);
            z2 = Math.max(z2, z1 + 1);
            int colour = RegionScreen.typeColour(s.type());
            boolean sel = s.id().equals(selected);
            g.fill(x1, z1, x2, z2, (sel ? 0x55000000 : 0x33000000) | (colour & 0xFFFFFF));
            int thick = sel ? 2 : 1;
            g.fill(x1, z1, x2, z1 + thick, 0xFF000000 | colour);
            g.fill(x1, z2 - thick, x2, z2, 0xFF000000 | colour);
            g.fill(x1, z1, x1 + thick, z2, 0xFF000000 | colour);
            g.fill(x2 - thick, z1, x2, z2, 0xFF000000 | colour);
            if (x2 - x1 > 24 && z2 - z1 > 8) {
                RegionScreen.text(g, font, s.id(), x1 + 3, z1 + 2, 0xFF000000 | colour, 0.5f);
            }
            if (mouseX >= x1 && mouseX < x2 && mouseY >= z1 && mouseY < z2) {
                int area = (x2 - x1) * (z2 - z1);
                if (area < hoveredArea) {
                    hoveredArea = area;
                    hovered = s;
                }
            }
        }
        int px = pixelX(x, player.getX()), pz = pixelZ(y, player.getZ());
        if (px >= x && px < x + W && pz >= y && pz < y + H) {
            g.fill(px - 2, pz - 2, px + 3, pz + 3, 0xFF000000);
            g.fill(px - 1, pz - 1, px + 2, pz + 2, 0xFFFFD23A);
        }
        g.disableScissor();
        return hovered;
    }
}
