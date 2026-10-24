package com.example.slopeclimb;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class SlopeClimbPlugin extends JavaPlugin {

    private GameManager game;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        game = new GameManager(this);

        getServer().getPluginManager().registerEvents(new MobListener(game), this);

        PluginCommand cmd = getCommand("koth");
        if (cmd != null) {
            SlopeCommand handler = new SlopeCommand(this, game);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        getServer().getScheduler().runTaskTimer(this, game::tick, 1L, 1L);
    }

    @Override
    public void onDisable() {
        if (game != null) {
            game.shutdown();
        }
    }
}
