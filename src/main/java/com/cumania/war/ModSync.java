package com.cumania.war;

import com.cumania.war.site.SiteData;
import com.cumania.war.sync.SyncMessages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.Messenger;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tells the BMC Atlas client mod what changed, so it stops asking dynmap for the war markers every few seconds and the site for its
 * data every half hour: who holds each city of the live wars, the staff's bridges, and which site data this server uses. A message
 * goes only to players whose mod opened the "bmcatlas:sync" channel (players without the mod get nothing), and only when something
 * changed. Turned off with mod-sync.enabled: false; the mod then goes back to asking dynmap and the site itself.
 */
final class ModSync implements Listener {
    private final BmcWarPlugin plugin;
    /** The last message sent for each map (by map name), to send again only what changed. */
    private final Map<String, String> sent = new HashMap<>();
    private boolean tooBigWarned;

    ModSync(BmcWarPlugin plugin) {
        this.plugin = plugin;
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean("mod-sync.enabled", true);
    }

    /** A player's mod opened the channel: tell it this server has the plugin, and the state of every map. */
    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent e) {
        if (!enabled() || !SyncMessages.CHANNEL.equals(e.getChannel())) return;
        Player p = e.getPlayer();
        send(p, SyncMessages.hello());
        for (WarMap m : plugin.maps()) {
            String msg = sent.containsKey(m.name) ? sent.get(m.name) : build(m);
            if (msg != null) send(p, msg);
        }
    }

    /** Once a second: sends the maps whose state changed to every player whose mod listens. */
    void tick() {
        if (!enabled()) return;
        List<Player> listening = Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.getListeningPluginChannels().contains(SyncMessages.CHANNEL)).map(p -> (Player) p).toList();
        for (WarMap m : plugin.maps()) {
            String msg = build(m);
            if (msg == null || msg.equals(sent.get(m.name))) continue;
            sent.put(m.name, msg);
            for (Player p : listening) send(p, msg);
        }
    }

    /** The state of a map, or null while its site data has not arrived. */
    private String build(WarMap m) {
        SiteData s = m.site;
        if (s == null) return null;
        return SyncMessages.map(m.name, m.url, s, plugin.handledWars(m), m.markers, war -> m.engine == null ? Map.of() : m.engine.bridges(war));
    }

    private void send(Player p, String msg) {
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Messenger.MAX_MESSAGE_SIZE) {
            if (!tooBigWarned) plugin.getLogger().warning("A map's state is too big to send to the BMC Atlas mod (" + bytes.length + " bytes): the mod keeps reading dynmap for it.");
            tooBigWarned = true;
            return;
        }
        p.sendPluginMessage(plugin, SyncMessages.CHANNEL, bytes);
    }
}
