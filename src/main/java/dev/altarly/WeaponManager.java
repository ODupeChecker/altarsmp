package dev.altarly;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.stream.Collectors;

public final class WeaponManager {
    public static final String CURSED_BLADE_ID = "cursed_blade";
    public static final String ENDER_BLADE_ID = "ender_blade";

    private final JavaPlugin plugin;
    private final NamespacedKey weaponKey;
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacyAmpersand();

    public WeaponManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.weaponKey = new NamespacedKey(plugin, "legendary_weapon_id");
    }

    public ItemStack createCursedBlade() {
        return createWeapon("CURSED_BLADE", CURSED_BLADE_ID);
    }

    public ItemStack createEnderBlade() {
        return createWeapon("ENDER_BLADE", ENDER_BLADE_ID);
    }

    public String getWeaponId(ItemStack stack) {
        if (stack == null || stack.getType() != Material.NETHERITE_SWORD || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta.getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
    }

    public boolean isCursedBlade(ItemStack stack) {
        return CURSED_BLADE_ID.equals(getWeaponId(stack));
    }

    public boolean isEnderBlade(ItemStack stack) {
        return ENDER_BLADE_ID.equals(getWeaponId(stack));
    }

    private ItemStack createWeapon(String configRoot, String weaponId) {
        FileConfiguration cfg = plugin.getConfig();

        Material material = Material.matchMaterial(cfg.getString(configRoot + ".ITEM.MATERIAL", "NETHERITE_SWORD"));
        if (material == null) {
            material = Material.NETHERITE_SWORD;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.customName(legacySerializer.deserialize(cfg.getString(configRoot + ".ITEM.DISPLAY_NAME", "&5Legendary Blade")));
        meta.setCustomModelData(cfg.getInt(configRoot + ".ITEM.CUSTOM_MODEL_DATA", 0));

        List<Component> lore = cfg.getStringList(configRoot + ".ITEM.LORE")
                .stream()
                .map(legacySerializer::deserialize)
                .collect(Collectors.toList());
        meta.lore(lore);

        meta.getPersistentDataContainer().set(weaponKey, PersistentDataType.STRING, weaponId);
        item.setItemMeta(meta);
        return item;
    }
}
