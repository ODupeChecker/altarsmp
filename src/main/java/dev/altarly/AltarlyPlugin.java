package dev.altarly;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class AltarlyPlugin extends JavaPlugin {
    private File altarlyConfigFile;
    private FileConfiguration altarlyConfig;

    @Override
    public void onEnable() {
        createAltarlyConfig();

        WeaponManager weaponManager = new WeaponManager(this);
        AbilityListener abilityListener = new AbilityListener(this, weaponManager);
        AltarlyCommand altarlyCommand = new AltarlyCommand(this, weaponManager, abilityListener);

        getServer().getPluginManager().registerEvents(abilityListener, this);
        if (getCommand("altarly") != null) {
            getCommand("altarly").setExecutor(altarlyCommand);
            getCommand("altarly").setTabCompleter(altarlyCommand);
        }
    }

    public FileConfiguration getAltarlyConfig() {
        return altarlyConfig;
    }

    public void reloadAltarlyConfig() {
        if (altarlyConfigFile == null) {
            createAltarlyConfig();
            return;
        }
        altarlyConfig = YamlConfiguration.loadConfiguration(altarlyConfigFile);
    }

    private void createAltarlyConfig() {
        File folder = new File(getDataFolder(), "config");
        if (!folder.exists()) {
            folder.mkdirs();
        }

        altarlyConfigFile = new File(folder, "altarly.yml");
        if (!altarlyConfigFile.exists()) {
            saveResource("config/altarly.yml", false);
        }

        altarlyConfig = YamlConfiguration.loadConfiguration(altarlyConfigFile);
    }
}
