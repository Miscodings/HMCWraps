package de.skyslycer.hmcwraps.listener;

import de.skyslycer.hmcwraps.HMCWrapsPlugin;
import de.skyslycer.hmcwraps.util.PermissionUtil;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerJoinListener implements Listener {

    private final HMCWrapsPlugin plugin;

    public PlayerJoinListener(HMCWrapsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        var manager = plugin.getDataManager();
        if (manager != null) {
            manager.load(event.getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        var manager = plugin.getDataManager();
        if (manager != null) {
            manager.unload(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.getFoliaLib().getScheduler().runAtEntityLater(event.getPlayer(), () -> PermissionUtil.loopThroughInventory(plugin, event.getPlayer(), event.getPlayer().getInventory()), 1);
        plugin.getFoliaLib().getScheduler().runLaterAsync(() -> plugin.getUpdateChecker().checkPlayer(event.getPlayer()), 5);
    }

}
