package com.cumania.war;

import com.cumania.war.sync.SyncMessages;
import com.cumania.war.sync.Versions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells people about new versions:
 * - the staff (console, and players with bmcwar.admin when they join) when a newer BmcWar is out;
 * - a player whose BMC Atlas mod is out of date, with a link to download the new one. The mod (1.2.6 and newer) says its
 *   version on the "bmcatlas:sync" channel; a mod that opens the channel but says nothing is 1.2.4 or 1.2.5, so out of date
 *   as soon as a newer one is out. A mod that tells the player about updates itself (selfCheck) is left alone. Players
 *   without the mod, or with one older than 1.2.4, cannot be told apart from players who do not use it: nothing is said.
 * The latest releases are read from GitHub's public API when the plugin starts and every 6 hours.
 * updates.check: false turns all of it off; updates.warn-old-mod: false only the players' warning.
 */
final class UpdateNotices implements Listener, PluginMessageListener {
    private final BmcWarPlugin plugin;
    /** What each player's mod said about itself (only mods 1.2.6 and newer say it). */
    private final Map<UUID, Versions.ModHello> mods = new ConcurrentHashMap<>();
    private volatile Versions.Release latestPlugin, latestMod;
    private volatile String announcedPlugin;

    UpdateNotices(BmcWarPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean on() {
        return plugin.getConfig().getBoolean("updates.check", true);
    }

    /** Reads the latest releases now and every 6 hours (off the game thread). */
    void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::fetch, 20L * 10, 20L * 60 * 60 * 6);
    }

    private void fetch() {
        if (!on()) return;
        Versions.Release p = latest(Versions.PLUGIN_LATEST);
        if (p != null) latestPlugin = p;
        Versions.Release m = latest(Versions.MOD_LATEST);
        if (m != null) latestMod = m;
        String mine = installed();
        if (p != null && Versions.newer(p.version(), mine) && !p.version().equals(announcedPlugin)) {
            announcedPlugin = p.version();
            plugin.getLogger().info("BmcWar " + p.version() + " is out (this server has " + mine + "). Download: " + page(p, "https://github.com/Normandioo/bmcmc-war-plugin/releases/latest"));
        }
    }

    private Versions.Release latest(String url) {
        try {
            HttpResponse<String> r = plugin.source().http().send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "bmc-war").header("Accept", "application/vnd.github+json").GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? Versions.release(r.body()) : null;
        } catch (Exception e) {
            return null;   // no connection or GitHub's rate limit: try again in 6 hours
        }
    }

    private String installed() {
        return Versions.versionIn(plugin.getPluginMeta().getVersion());
    }

    private static String page(Versions.Release r, String fallback) {
        return r.page() == null || r.page().isBlank() ? fallback : r.page();
    }

    /** The staff hears about a newer plugin when they join. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Versions.Release r = latestPlugin;
        if (!on() || r == null || !p.hasPermission("bmcwar.admin") || !Versions.newer(r.version(), installed())) return;
        String url = page(r, "https://github.com/Normandioo/bmcmc-war-plugin/releases/latest");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) p.sendMessage(Component.text("[BmcWar] Version " + r.version() + " of the plugin is out (this server has " + installed() + "). ", NamedTextColor.YELLOW)
                    .append(link("Download it here", url)));
        }, 20L * 3);
    }

    /** A player's mod opened the channel: give it a few seconds to say its version, then warn the player if it is out of date. */
    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent e) {
        if (!SyncMessages.CHANNEL.equals(e.getChannel())) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> warnIfOld(p), 20L * 10);
    }

    private void warnIfOld(Player p) {
        Versions.Release r = latestMod;
        if (!on() || !plugin.getConfig().getBoolean("updates.warn-old-mod", true) || r == null || !p.isOnline()) return;
        Versions.ModHello hello = mods.get(p.getUniqueId());
        if (hello != null && (hello.selfCheck() || !Versions.newer(r.version(), hello.version()))) return;   // up to date, or the mod tells the player itself
        String have = hello == null ? "an older version" : "version " + hello.version();
        p.sendMessage(Component.text("[BMC Atlas] Your BMC Atlas mod is out of date: version " + r.version() + " is out (you have " + have + "). ", NamedTextColor.YELLOW)
                .append(link("Download it here", page(r, Versions.MOD_PAGE))));
    }

    /** What the mod says on the channel (its version). */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!SyncMessages.CHANNEL.equals(channel)) return;
        Versions.ModHello hello = Versions.modHello(new String(message, StandardCharsets.UTF_8));
        if (hello != null) mods.put(player.getUniqueId(), hello);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        mods.remove(e.getPlayer().getUniqueId());
    }

    private static Component link(String text, String url) {
        return Component.text(text, NamedTextColor.AQUA).decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(url)).hoverEvent(HoverEvent.showText(Component.text(url)));
    }
}
