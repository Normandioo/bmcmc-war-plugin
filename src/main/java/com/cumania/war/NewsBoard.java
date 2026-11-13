package com.cumania.war;

import com.cumania.war.news.News;
import com.cumania.war.news.NewsDiff;
import com.cumania.war.news.NewsStore;
import com.cumania.war.news.WorldClock;
import com.cumania.war.site.TerritoryFile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.time.Instant;
import java.util.List;

/**
 * Diplomatic news: what changed in the site's diplomacy (wars, alliances, truces, ultimatums, subjects) between two downloads, plus the Staff's
 * posts. Said in the chat when it happens, in a framed block that opens the news menu wherever it is clicked; told to players who join; read in
 * the menu (/news, NewsMenu) and its window (NewsDialog). Kept in plugins/BmcWar/news.json.
 */
final class NewsBoard implements Listener {
    private final BmcWarPlugin plugin;
    private final NewsStore store;

    NewsBoard(BmcWarPlugin plugin) {
        this.plugin = plugin;
        this.store = new NewsStore(plugin.getDataFolder().toPath().resolve("news.json"));
        loadClock();
        try { store.load(); } catch (Exception e) { plugin.getLogger().warning("news.json could not be read (kept aside as news.json.bad); the news start over: " + e); }
    }

    private boolean enabled() { return plugin.getConfig().getBoolean("news.enabled", true); }

    /** The latest news, newest first. */
    List<News> latest(int n) { return store.latest(n); }

    /** The server's calendar for the dates in the news (news.calendar), read at start and on /bmcwar reload. */
    void loadClock() {
        var c = plugin.getConfig();
        NewsDiff.setClock(WorldClock.of(c.getString("news.calendar.year-zero"), c.getString("news.calendar.era"), c.getString("news.calendar.before-era")));
    }

    /** A map's site was downloaded (off the game thread): the news since the last download are kept and said in the chat. */
    void observe(WarMap m, String body) {
        if (!enabled()) return;
        List<News> fresh;
        try {
            fresh = store.observe(m.name, TerritoryFile.parse(body), Instant.now(), plugin.getConfig().getInt("news.keep", 50));
            store.save();
        } catch (Exception e) {
            plugin.getLogger().warning("[" + m.name + "] Could not work out the diplomatic news: " + e);
            return;
        }
        if (fresh.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> { for (News n : fresh) announce(n); });
    }

    /** The last error reading each map's news.json, so it is said once, not every minute. */
    private final java.util.Map<String, String> postErrors = new java.util.concurrent.ConcurrentHashMap<>();
    /** The ETag of each map's last news.json, to ask again only for what changed. */
    private final java.util.Map<String, String> postTags = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Every news.posts-refresh-seconds (off the game thread): each map's news.json, the Staff's posts (/news post of the site's Discord
     * bot), next to its territories.json. A small file, so it is read more often than the site's data. No file (404) = no posts yet.
     */
    void fetchPosts() {
        if (!enabled() || !readingPosts.compareAndSet(false, true)) return;   // a slow site must not pile one round on top of the last
        try { readPosts(); } finally { readingPosts.set(false); }
    }

    private final java.util.concurrent.atomic.AtomicBoolean readingPosts = new java.util.concurrent.atomic.AtomicBoolean();

    private void readPosts() {
        for (WarMap m : plugin.maps()) {
            if (BmcWarPlugin.worldOf(m) == null) continue;   // a map whose world is not here, like its site data
            String url = java.net.URI.create(m.url).resolve("news.json").toString();
            try {
                var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(20)).header("User-Agent", "bmc-war");
                String tag = postTags.get(m.name);
                if (tag != null) request.header("If-None-Match", tag);   // nothing new: a short answer, nothing to read
                var response = plugin.source().http().send(request.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) response.body().close();
                if (response.statusCode() == 304) continue;
                if (response.statusCode() != 200 && response.statusCode() != 404) throw new IllegalStateException("HTTP " + response.statusCode());
                response.headers().firstValue("ETag").ifPresentOrElse(t -> postTags.put(m.name, t), () -> postTags.remove(m.name));
                List<NewsDiff.Post> posts = response.statusCode() == 404 ? List.of() : NewsDiff.parsePosts(MapSource.body(response));
                List<News> fresh = store.observePosts(m.name, posts, Instant.now(), plugin.getConfig().getInt("news.keep", 50));
                if (postErrors.remove(m.name) != null) plugin.getLogger().info("[" + m.name + "] " + url + " can be read again.");
                store.save();   // also the first reading, so a post made while the server is off is still news after a restart
                if (fresh.isEmpty()) continue;
                Bukkit.getScheduler().runTask(plugin, () -> { for (News n : fresh) announce(n); });
            } catch (Exception e) {
                if (!String.valueOf(e).equals(postErrors.put(m.name, String.valueOf(e)))) plugin.getLogger().warning("[" + m.name + "] Could not read the Staff's news from " + url + ": " + e);
            }
        }
    }

    private void announce(News n) {
        plugin.getLogger().info("[news] " + n.title());
        if (!plugin.getConfig().getStringList("news.announce").contains(n.type().key())) return;
        Bukkit.getServer().broadcast(block(Component.text()
                .append(Component.text(headline(n.type()), color(n.type()), TextDecoration.BOLD)).append(Component.text("  " + n.title(), NamedTextColor.WHITE)).build()));
    }

    /**
     * The chat block that tells a news: a gold rule above and below, the masthead, the line, and a call to click. A click anywhere on it opens the
     * news menu, so nobody has to know a command.
     */
    private static Component block(Component line) {
        Component rule = Component.text(" ".repeat(48), NamedTextColor.GOLD, TextDecoration.STRIKETHROUGH);
        return Component.text()
                .append(rule).append(Component.newline())
                .append(Component.text(">>> GAIA GUARDIAN <<<", NamedTextColor.GOLD, TextDecoration.BOLD)).append(Component.newline())
                .append(line).append(Component.newline())
                .append(Component.text("[ CLICK HERE TO OPEN THE NEWS ]", NamedTextColor.AQUA, TextDecoration.BOLD, TextDecoration.UNDERLINED)).append(Component.newline())
                .append(rule)
                .clickEvent(ClickEvent.runCommand("/news"))
                .hoverEvent(HoverEvent.showText(Component.text("Open the news menu")))
                .build();
    }

    private static String headline(News.Type t) {
        return switch (t) {
            case WAR -> "WAR!"; case PEACE -> "Peace"; case POST -> "Staff notice"; case ALLIANCE -> "New alliance"; case ALLIANCE_JOIN -> "An alliance grows";
            case ALLIANCE_LEAVE -> "An alliance breaks"; case ALLIANCE_END -> "Alliance dissolved"; case TRUCE -> "Truce"; case NAP -> "Pact signed";
            case ULTIMATUM -> "Ultimatum"; case VASSAL -> "New subject"; case INDEPENDENCE -> "Independence";
        };
    }

    static NamedTextColor color(News.Type t) {
        return switch (t) {
            case WAR -> NamedTextColor.RED; case PEACE, TRUCE, NAP -> NamedTextColor.GREEN; case POST -> NamedTextColor.GOLD; case ULTIMATUM -> NamedTextColor.YELLOW;
            case ALLIANCE, ALLIANCE_JOIN, ALLIANCE_LEAVE, ALLIANCE_END -> NamedTextColor.AQUA; case VASSAL, INDEPENDENCE -> NamedTextColor.LIGHT_PURPLE;
        };
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled() || !plugin.getConfig().getBoolean("news.on-join", true)) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            int n = store.unread(p.getUniqueId().toString());
            if (n == 0 || !p.isOnline()) return;
            p.sendMessage(block(Component.text(n == 1 ? "1 news since you last read them" : n + " news since you last read them", NamedTextColor.WHITE)));
        }, 60L);
    }

    /** /news: the news menu (it counts everything in it as read). */
    void open(CommandSender sender) {
        if (!(sender instanceof Player p)) { sender.sendMessage(Component.text("Only a player can open the news.", NamedTextColor.GRAY)); return; }
        if (!enabled()) { p.sendMessage(Component.text("The diplomatic news are turned off (news.enabled).", NamedTextColor.GRAY)); return; }
        plugin.menu().open(p);
    }

    /** Everything there is now counts as read by this player (the notice when they join stops counting it). */
    void markRead(Player p) {
        store.markRead(p.getUniqueId().toString());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { store.save(); } catch (Exception ex) { plugin.getLogger().warning("Could not save news.json: " + ex); }
        });
    }
}
