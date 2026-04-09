package dev.altarly;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class WeaponManager {
    public enum LegendaryType {
        RUINED_BLADE,
        ENDER_BLADE
    }

    private final JavaPlugin plugin;
    private final NamespacedKey weaponKey;
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacyAmpersand();

    public WeaponManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.weaponKey = new NamespacedKey(plugin, "legendary_weapon");
    }

    public ItemStack createWeapon(LegendaryType type) {
        return switch (type) {
            case RUINED_BLADE -> createConfiguredWeapon("ALTARS.RUINED_BLADE.ITEM", "&5&lRuined Blade", 15);
            case ENDER_BLADE -> createConfiguredWeapon("ALTARS.ENDER_BLADE.ITEM", "&5&lEnder Blade", 16);
        };
    }

    public List<ItemStack> createAllLegendaryWeapons() {
        List<ItemStack> items = new ArrayList<>();
        for (LegendaryType type : LegendaryType.values()) {
            items.add(createWeapon(type));
        }
        return items;
    }

    public boolean isLegendaryWeapon(ItemStack stack) {
        return getLegendaryType(stack) != null;
    }

    public LegendaryType getLegendaryType(ItemStack stack) {
        if (stack == null || stack.getType() != Material.NETHERITE_SWORD || !stack.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer container = meta.getPersistentDataContainer();
        String storedType = container.get(weaponKey, PersistentDataType.STRING);
        if (storedType == null) {
            return null;
        }

        try {
            return LegendaryType.valueOf(storedType);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private ItemStack createConfiguredWeapon(String root, String fallbackName, int fallbackModelData) {
        FileConfiguration cfg = plugin.getConfig();

        Material material = Material.matchMaterial(cfg.getString(root + ".MATERIAL", "NETHERITE_SWORD"));
        if (material == null) {
            material = Material.NETHERITE_SWORD;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.customName(legacySerializer.deserialize(cfg.getString(root + ".DISPLAY_NAME", fallbackName)));
        meta.setCustomModelData(cfg.getInt(root + ".CUSTOM_MODEL_DATA", fallbackModelData));

        List<Component> lore = cfg.getStringList(root + ".LORE")
                .stream()
                .map(legacySerializer::deserialize)
                .collect(Collectors.toList());
        meta.lore(lore);

        LegendaryType type = root.contains("ENDER_BLADE") ? LegendaryType.ENDER_BLADE : LegendaryType.RUINED_BLADE;
        meta.getPersistentDataContainer().set(weaponKey, PersistentDataType.STRING, type.name());
        item.setItemMeta(meta);
        return item;
    }
}
