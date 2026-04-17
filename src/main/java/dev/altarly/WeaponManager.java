package dev.altarly;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.stream.Collectors;

public final class WeaponManager {
    public static final String CURSED_BLADE_ID = "cursed_blade";
    public static final String ENDER_BLADE_ID = "ender_blade";
    public static final String POSEIDONS_TRIDENT_ID = "poseidons_trident";

    private final JavaPlugin plugin;
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacyAmpersand();

    public WeaponManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public ItemStack createCursedBlade() {
        return createWeapon("CURSED_BLADE");
    }

    public ItemStack createEnderBlade() {
        return createWeapon("ENDER_BLADE");
    }

    public ItemStack createPoseidonsTrident() {
        return createWeapon("POSEIDONS_TRIDENT");
    }

    public String getWeaponId(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }

        if (isConfiguredWeapon(stack, "CURSED_BLADE")) {
            return CURSED_BLADE_ID;
        }
        if (isConfiguredWeapon(stack, "ENDER_BLADE")) {
            return ENDER_BLADE_ID;
        }
        if (isConfiguredWeapon(stack, "POSEIDONS_TRIDENT")) {
            return POSEIDONS_TRIDENT_ID;
        }
        return null;
    }

    private boolean isConfiguredWeapon(ItemStack stack, String configRoot) {
        FileConfiguration cfg = plugin.getConfig();
        Material expectedMaterial = Material.matchMaterial(cfg.getString(configRoot + ".ITEM.MATERIAL", "NETHERITE_SWORD"));
        if (expectedMaterial == null) {
            expectedMaterial = Material.NETHERITE_SWORD;
        }
        if (stack.getType() != expectedMaterial) {
            return false;
        }

        ItemMeta meta = stack.getItemMeta();
        if (!meta.hasCustomModelData()) {
            return false;
        }

        int expectedModelData = cfg.getInt(configRoot + ".ITEM.CUSTOM_MODEL_DATA", 0);
        return expectedModelData != 0 && meta.getCustomModelData() == expectedModelData;
    }

    public boolean isCursedBlade(ItemStack stack) {
        return CURSED_BLADE_ID.equals(getWeaponId(stack));
    }

    public boolean isEnderBlade(ItemStack stack) {
        return ENDER_BLADE_ID.equals(getWeaponId(stack));
    }

    public boolean isPoseidonsTrident(ItemStack stack) {
        return POSEIDONS_TRIDENT_ID.equals(getWeaponId(stack));
    }

    private ItemStack createWeapon(String configRoot) {
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

        item.setItemMeta(meta);
        return item;
    }
}
