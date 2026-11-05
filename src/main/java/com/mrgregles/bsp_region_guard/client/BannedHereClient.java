package com.mrgregles.bsp_region_guard.client;

import com.mrgregles.bsp_region_guard.network.BannedListPacket;
import com.mrgregles.bsp_region_guard.network.BannedListRequestPacket;
import com.mrgregles.bsp_region_guard.network.RegionNetwork;
import com.mrgregles.bsp_region_guard.protection.BannedBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The player's side of the banned lists (D133 to D139): keeps the list for where they stand (asked for when the
 * inventory opens, for its BANNED button), opens the BANNED HERE screen, and answers counts for the bought-plot pop-up
 * and the Land Office. Tags and whole mods are opened into items here, with the server's own matching, so a player
 * sees exactly what the server refuses.
 */
public final class BannedHereClient {
    /** One banned item and the rules that stop it. */
    public record Banned(ItemStack stack, ResourceLocation id, String name, String mod, EnumSet<BannedBlocks.Rule> rules) {
    }

    @Nullable
    private static BannedListPacket here;
    private static final Map<String, List<IntConsumer>> WAITING = new HashMap<>();
    private static final Map<String, Screen> OPEN_OVER = new HashMap<>();

    private BannedHereClient() {
    }

    /** The list where the player stood when the inventory last opened, or null before an answer came. */
    @Nullable
    public static BannedListPacket here() {
        return here;
    }

    /** Asks for the list where the player stands, for the inventory button. */
    public static void askHere() {
        here = null;
        RegionNetwork.CHANNEL.sendToServer(BannedListRequestPacket.here(BannedListPacket.STATUS));
    }

    /** Opens the BANNED HERE screen for a region (its middle), over {@code parent}; Esc returns to it. */
    public static void openRegion(String regionId, @Nullable Screen parent) {
        OPEN_OVER.put(regionId, parent);
        RegionNetwork.CHANNEL.sendToServer(new BannedListRequestPacket(BannedListPacket.OPEN, regionId));
    }

    /** Asks how many items are banned in a region; {@code answer} runs on the client thread when the server replies. */
    public static void count(String regionId, IntConsumer answer) {
        WAITING.computeIfAbsent(regionId, k -> new ArrayList<>()).add(answer);
        RegionNetwork.CHANNEL.sendToServer(new BannedListRequestPacket(BannedListPacket.INFO, regionId));
    }

    public static void receive(BannedListPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        switch (msg.purpose()) {
            case BannedListPacket.STATUS -> here = msg;
            case BannedListPacket.INFO -> {
                List<IntConsumer> answers = WAITING.remove(msg.regionId());
                if (answers != null) {
                    int n = resolve(msg).size();
                    answers.forEach(a -> a.accept(n));
                }
            }
            default -> {
                if (mc.player == null || mc.screen instanceof ChatScreen) {
                    return;
                }
                Screen parent = OPEN_OVER.containsKey(msg.regionId()) ? OPEN_OVER.remove(msg.regionId()) : mc.screen;
                mc.setScreen(new BannedHereScreen(parent, msg));
            }
        }
    }

    // ------------------------------------------------------------------ the items

    private static BannedListPacket cachedFor;
    private static List<Banned> cached = List.of();

    /** Every item the lists ban, with its rules, sorted by mod and then by name. */
    public static List<Banned> resolve(BannedListPacket msg) {
        if (cachedFor != null && cachedFor.lists().equals(msg.lists())) {
            return cached; // the same lists (a Land Office asking again for the same tag): no second scan of every item
        }
        Map<BannedBlocks.Rule, Map<String, String>> entries = new EnumMap<>(BannedBlocks.Rule.class);
        for (var e : msg.lists().entrySet()) {
            Map<String, String> m = new LinkedHashMap<>();
            for (String s : e.getValue()) {
                m.put(s.toLowerCase(Locale.ROOT), msg.regionId());
            }
            entries.put(e.getKey(), m);
        }
        Map<String, String> modNames = new HashMap<>();
        List<Banned> out = new ArrayList<>();
        if (msg.any()) {
            for (Item item : ForgeRegistries.ITEMS) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (item == Items.AIR || id == null) {
                    continue;
                }
                ItemStack stack = new ItemStack(item);
                EnumSet<BannedBlocks.Rule> rules = EnumSet.noneOf(BannedBlocks.Rule.class);
                for (var e : entries.entrySet()) {
                    if (BannedBlocks.match(e.getValue(), stack) != null) {
                        rules.add(e.getKey());
                    }
                }
                if (!rules.isEmpty()) {
                    String mod = modNames.computeIfAbsent(id.getNamespace(), ns -> ModList.get().getModContainerById(ns)
                            .map(c -> c.getModInfo().getDisplayName()).orElse(ns));
                    out.add(new Banned(stack, id, stack.getHoverName().getString(), mod, rules));
                }
            }
            out.sort(Comparator.comparing((Banned b) -> b.mod().toLowerCase(Locale.ROOT)).thenComparing(b -> b.name().toLowerCase(Locale.ROOT)));
        }
        cachedFor = msg;
        cached = out;
        return out;
    }
}
