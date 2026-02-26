package io.github.lumine1909.blocktuner.listener;

import io.github.lumine1909.blocktuner.data.PlayerData;
import io.github.lumine1909.blocktuner.network.BlockTunerProtocol;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import static io.github.lumine1909.blocktuner.BlockTunerPlugin.plugin;

public class DataHandleListener implements Listener {

    private final BlockTunerProtocol protocol;

    public DataHandleListener(BlockTunerProtocol protocol) {
        this.protocol = protocol;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        PlayerData.of(event.getPlayer());
        protocol.injectPlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        protocol.uninjectPlayer(event.getPlayer());
        PlayerData.delete(event.getPlayer());
    }
}
