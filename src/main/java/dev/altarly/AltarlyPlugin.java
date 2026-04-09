package dev.altarly;

import org.bukkit.plugin.java.JavaPlugin;

public final class AltarlyPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        WeaponManager weaponManager = new WeaponManager(this);
        AbilityListener abilityListener = new AbilityListener(this, weaponManager);
        AltarlyCommand altarlyCommand = new AltarlyCommand(this, weaponManager);

        getServer().getPluginManager().registerEvents(abilityListener, this);
        if (getCommand("altarly") != null) {
            getCommand("altarly").setExecutor(altarlyCommand);
            getCommand("altarly").setTabCompleter(altarlyCommand);
        }
    }
}
