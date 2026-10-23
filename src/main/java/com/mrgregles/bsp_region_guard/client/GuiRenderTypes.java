package com.mrgregles.bsp_region_guard.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

import java.util.OptionalDouble;

/** Render types for drawing 3D inside a screen. Holds constants only; never made. */
final class GuiRenderTypes extends RenderType {
    /**
     * Lines for the PLOT LOOK preview. Minecraft's own {@code lines()} draw into the item-entity target when Fabulous
     * graphics is on, which only the world's drawing shows, so in a screen they would vanish. These draw straight to
     * the screen.
     */
    static final RenderType LINES = create("bsp_region_guard_gui_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 1024, false, false,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    .setLineState(new LineStateShard(OptionalDouble.empty()))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_DEPTH_WRITE)
                    .setCullState(NO_CULL)
                    .createCompositeState(false));

    private GuiRenderTypes() {
        super("bsp_region_guard_unused", DefaultVertexFormat.POSITION, VertexFormat.Mode.LINES, 0, false, false, () -> {
        }, () -> {
        });
    }
}
