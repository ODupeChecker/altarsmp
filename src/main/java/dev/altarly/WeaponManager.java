package dev.altarly;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.stream.Collectors;

public final class WeaponManager {
    private final AltarlyPlugin plugin;
    private final NamespacedKey weaponKey;
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacyAmpersand();

    public WeaponManager(AltarlyPlugin plugin) {
        this.plugin = plugin;
        this.weaponKey = new NamespacedKey(plugin, "cursed_blade");
    }

    public ItemStack createLegendaryWeapon() {
        FileConfiguration cfg = plugin.getAltarlyConfig();
        String root = "CURSED_BLADE";

        ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.customName(legacySerializer.deserialize(cfg.getString(root + ".ITEM.DISPLAY_NAME", "&5Cursed Blade")));
        meta.setCustomModelData(cfg.getInt(root + ".ITEM.CUSTOM_MODEL_DATA", 15));

        List<Component> lore = cfg.getStringList(root + ".ITEM.LORE")
                .stream()
                .map(legacySerializer::deserialize)
                .collect(Collectors.toList());
        meta.lore(lore);

        meta.getPersistentDataContainer().set(weaponKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isLegendaryWeapon(ItemStack stack) {
        if (stack == null || stack.getType() != Material.NETHERITE_SWORD || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        Byte marker = meta.getPersistentDataContainer().get(weaponKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }
}
