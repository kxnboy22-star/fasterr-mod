package com.faster;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

public class FasterClient implements ClientModInitializer {
    /**
     * true  = attribute swap: switch to the spear and jab in the SAME tick, so the server judges the jab
     *         with the item you swapped FROM (e.g. a golden apple), skipping the spear's own cooldown.
     * false = safe mode: swap, wait for the spear's attack meter to fill, then jab (works on servers
     *         that block attribute swapping).
     */
    private static final boolean ATTRIBUTE_SWAP = true;

    /** Ticks to stay on the spear after the jab before swapping back. */
    private static final int SWAP_BACK_DELAY = 2;
    /** Safe mode only: give up if the attack meter never fills (ticks). */
    private static final int MAX_WAIT = 80;
    /** A tap of the key stays "queued" this long if the swap item's meter isn't full yet (ticks). */
    private static final int PENDING_TIMEOUT = 10;

    private enum Phase { IDLE, WAIT_READY, JABBED }

    private static KeyMapping fasterKey;
    private Phase phase = Phase.IDLE;
    private int ticks = 0;
    private int pendingTicks = 0;
    private int previousSlot = 0;

    @Override
    public void onInitializeClient() {
        fasterKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.faster.go",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("faster", "main"))));

        // START of tick, so the slot change and the click land in the same game tick.
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.player == null) { phase = Phase.IDLE; pendingTicks = 0; return; }
            Inventory inv = client.player.getInventory();

            boolean pressed = false;
            while (fasterKey.consumeClick()) pressed = true;
            if (pressed) pendingTicks = PENDING_TIMEOUT;
            boolean want = pendingTicks > 0 || fasterKey.isDown();
            if (pendingTicks > 0) pendingTicks--;

            if (phase == Phase.IDLE) {
                if (!want) return;
                int slot = findBestLungeSpear(inv);
                if (slot < 0) {
                    pendingTicks = 0;
                    return;
                }
                if (ATTRIBUTE_SWAP) {
                    // Wait until the item in hand (golden apple etc.) has a full attack meter.
                    if (client.player.getAttackStrengthScale(0.0F) < 1.0F) return;
                    previousSlot = inv.getSelectedSlot();
                    inv.setSelectedSlot(slot);
                    clickAttack(client);
                    ticks = 0;
                    pendingTicks = 0;
                    phase = Phase.JABBED;
                } else {
                    previousSlot = inv.getSelectedSlot();
                    inv.setSelectedSlot(slot);
                    ticks = 0;
                    pendingTicks = 0;
                    phase = Phase.WAIT_READY;
                }
            } else if (phase == Phase.WAIT_READY) {
                ticks++;
                if (ticks >= 1 && client.player.getAttackStrengthScale(0.0F) >= 1.0F) {
                    clickAttack(client);
                    ticks = 0;
                    phase = Phase.JABBED;
                } else if (ticks > MAX_WAIT) {
                    inv.setSelectedSlot(previousSlot);
                    phase = Phase.IDLE;
                }
            } else if (phase == Phase.JABBED) {
                if (++ticks >= SWAP_BACK_DELAY) {
                    inv.setSelectedSlot(previousSlot);
                    phase = Phase.IDLE;
                }
            }
        });
    }

    private static void clickAttack(Minecraft client) {
        KeyMapping.click(InputConstants.getKey(client.options.keyAttack.saveString()));
    }

    /** Hotbar spear with the highest Lunge level (I-III); -1 if none has Lunge. */
    private static int findBestLungeSpear(Inventory inv) {
        int bestSlot = -1, bestLevel = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_spear")) continue;
            int level = lungeLevel(stack);
            if (level > bestLevel) { bestLevel = level; bestSlot = i; }
        }
        return bestSlot;
    }

    private static int lungeLevel(ItemStack stack) {
        int best = 0;
        for (var entry : stack.getEnchantments().entrySet()) {
            if (entry.getKey().getRegisteredName().endsWith(":lunge")) {
                best = Math.max(best, entry.getIntValue());
            }
        }
        return best;
    }
}
