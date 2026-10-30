package net.azisaba.airpen;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The pen item: tagged in its persistent data, "usable" so the client reports right-click holding, and with a
 * durability bar that shows the owner's remaining ink.
 */
public final class PenItem {
    /** Resolution of the ink gauge (the item's max damage). */
    private static final int GAUGE = 1000;

    private final NamespacedKey key;

    public PenItem(@NotNull AirPenPlugin plugin) {
        this.key = new NamespacedKey(plugin, "pen");
    }

    public @NotNull ItemStack create(@NotNull AirPenConfig config) {
        ItemStack item = ItemStack.of(config.penMaterial());
        ItemMeta meta = item.getItemMeta();
        MiniMessage mm = MiniMessage.miniMessage();
        meta.displayName(mm.deserialize(config.penName()).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        meta.lore(lore(config, config.maxSegmentsPerPlayer(), config.maxSegmentsPerPlayer()));
        if (config.penModelData() != 0) meta.setCustomModelData(config.penModelData());
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        // An hour-long silent "use" lets the server see when right-click is pressed and released
        item.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                .consumeSeconds(3600)
                .animation(ItemUseAnimation.NONE)
                .sound(Key.key("minecraft", "intentionally_empty"))
                .hasConsumeParticles(false)
                .build());
        item.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        item.setData(DataComponentTypes.MAX_DAMAGE, GAUGE);
        item.setData(DataComponentTypes.DAMAGE, 0);
        return item;
    }

    /**
     * Sets the durability bar and the ink line of the lore to {@code used} of {@code max} ({@code used} &lt; 0 means
     * unlimited ink); false when the item already showed exactly that.
     */
    public boolean applyInk(@NotNull ItemStack item, int used, int max, @NotNull AirPenConfig config) {
        boolean unlimited = used < 0;
        // Never reach max damage: the bar should read "empty", not "broken"
        int damage = unlimited || max <= 0 ? 0 : (int) Math.min(GAUGE - 1, Math.round((double) used * GAUGE / max));
        List<Component> lore = lore(config, unlimited ? -1 : max - used, max);
        Integer currentDamage = item.getData(DataComponentTypes.DAMAGE);
        Integer currentMax = item.getData(DataComponentTypes.MAX_DAMAGE);
        if (currentMax != null && currentMax == GAUGE && currentDamage != null && currentDamage == damage
                && lore.equals(item.lore())) {
            return false;
        }
        item.setData(DataComponentTypes.MAX_DAMAGE, GAUGE);
        item.setData(DataComponentTypes.DAMAGE, damage);
        item.lore(lore);
        return true;
    }

    private static List<Component> lore(AirPenConfig config, int remaining, int max) {
        MiniMessage mm = MiniMessage.miniMessage();
        List<Component> lines = new ArrayList<>();
        for (String line : config.penLore()) {
            lines.add(mm.deserialize(line).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        Component amount;
        if (remaining < 0) {
            amount = Component.text("無制限", NamedTextColor.AQUA);
        } else {
            double ratio = max <= 0 ? 0 : (double) remaining / max;
            NamedTextColor color = ratio > 0.5 ? NamedTextColor.GREEN : ratio > 0.2 ? NamedTextColor.YELLOW : NamedTextColor.RED;
            amount = Component.text(remaining + " / " + max, color);
        }
        lines.add(Component.text("インク: ", NamedTextColor.GRAY).append(amount).decoration(TextDecoration.ITALIC, false));
        return lines;
    }

    public boolean isPen(@Nullable ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }
}
