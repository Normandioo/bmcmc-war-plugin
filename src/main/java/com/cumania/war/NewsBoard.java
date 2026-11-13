package com.cumania.war;

import com.cumania.war.news.News;
import com.cumania.war.news.NewsDiff;
import com.cumania.war.news.NewsStore;
import com.cumania.war.site.TerritoryFile;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Diplomatic news: what changed in the site's diplomacy (wars, alliances, truces, ultimatums, subjects) between two downloads,
 * said in the chat when it happens, told to players who join, and kept in a book (/news, /bmcwar news) with the Staff's text from the site.
 */
final class NewsBoard implements Listener {
    private static final int SHOWN = 30, PER_INDEX_PAGE = 3, ROWS = 13, ROW_CHARS = 19;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final BmcWarPlugin plugin;
    private final NewsStore store;

    NewsBoard(BmcWarPlugin plugin) {
        this.plugin = plugin;
        this.store = new NewsStore(plugin.getDataFolder().toPath().resolve("news.json"));
        try { store.load(); } catch (Exception e) { plugin.getLogger().warning("news.json could not be read (kept aside as news.json.bad); the news start over: " + e); }
    }

    private boolean enabled() { return plugin.getConfig().getBoolean("news.enabled", true); }

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

    /**
     * Every news.posts-refresh-seconds (off the game thread): each map's news.json, the Staff's posts (/news post of the site's Discord
     * bot), next to its territories.json. A small file, so it is read more often than the site's data. No file (404) = no posts yet.
     */
    void fetchPosts() {
        if (!enabled()) return;
        for (WarMap m : plugin.maps()) {
            if (BmcWarPlugin.worldOf(m) == null) continue;   // a map whose world is not here, like its site data
            String url = java.net.URI.create(m.url).resolve("news.json").toString();
            try {
                var response = plugin.source().http().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(20))
                        .header("User-Agent", "bmc-war").GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200 && response.statusCode() != 404) throw new IllegalStateException("HTTP " + response.statusCode());
                List<NewsDiff.Post> posts = response.statusCode() == 404 ? List.of() : NewsDiff.parsePosts(response.body());
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
        Bukkit.getServer().broadcast(Component.text("[News] ", NamedTextColor.GOLD).append(Component.text(n.title(), NamedTextColor.WHITE))
                .append(Component.text(" ")).append(readLink()));
    }

    private static Component readLink() {
        return Component.text("[Read]", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/bmcwar news"))
                .hoverEvent(HoverEvent.showText(Component.text("Open the diplomatic news")));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled() || !plugin.getConfig().getBoolean("news.on-join", true)) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            int n = store.unread(p.getUniqueId().toString());
            if (n == 0 || !p.isOnline()) return;
            p.sendMessage(Component.text("[News] ", NamedTextColor.GOLD).append(Component.text(n == 1 ? "1 diplomatic news since you last read them. " : n + " diplomatic news since you last read them. ", NamedTextColor.WHITE)).append(readLink()));
        }, 60L);
    }

    // ----- The book: a list of the latest news (click one to read it), then a page or more for each -----

    /** Opens the news book for a player (and counts everything in it as read). */
    void open(CommandSender sender) {
        if (!(sender instanceof Player p)) { sender.sendMessage(Component.text("Only a player can read the news book.", NamedTextColor.GRAY)); return; }
        if (!enabled()) { p.sendMessage(Component.text("The diplomatic news are turned off (news.enabled).", NamedTextColor.GRAY)); return; }
        p.openBook(book(store.latest(SHOWN)));
        store.markRead(p.getUniqueId().toString());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { store.save(); } catch (Exception ex) { plugin.getLogger().warning("Could not save news.json: " + ex); }
        });
    }

    private record Line(String text, TextColor color, boolean bold) {}

    static Book book(List<News> news) {
        Component bookTitle = Component.text("Diplomatic news"), author = Component.text("BMC");
        if (news.isEmpty()) return Book.book(bookTitle, author, List.of(Component.text("Diplomatic news\n\n", NamedTextColor.DARK_RED, TextDecoration.BOLD)
                .append(Component.text("Nothing yet. News appear here when the site's diplomacy changes: wars, alliances, truces, ultimatums and subjects.", NamedTextColor.BLACK)
                        .decoration(TextDecoration.BOLD, false))));
        int indexPages = (news.size() + PER_INDEX_PAGE - 1) / PER_INDEX_PAGE;
        List<List<Component>> details = new ArrayList<>();
        int[] first = new int[news.size()];
        int next = indexPages + 1;   // book pages count from 1
        for (int i = 0; i < news.size(); i++) {
            List<Component> pages = detail(news.get(i), 1 + i / PER_INDEX_PAGE);
            first[i] = next;
            next += pages.size();
            details.add(pages);
        }
        List<Component> pages = new ArrayList<>();
        for (int ip = 0; ip < indexPages; ip++) {
            Component page = Component.text("Diplomatic news" + (indexPages > 1 ? " " + (ip + 1) + "/" + indexPages : "") + "\n", NamedTextColor.DARK_RED, TextDecoration.BOLD);
            for (int i = ip * PER_INDEX_PAGE; i < Math.min(news.size(), (ip + 1) * PER_INDEX_PAGE); i++) {
                News n = news.get(i);
                String t = n.title().length() > 52 ? n.title().substring(0, 50) + "..." : n.title();
                page = page.append(Component.text("\n" + t + "\n", NamedTextColor.DARK_BLUE).decoration(TextDecoration.BOLD, false)
                                .clickEvent(ClickEvent.changePage(first[i])).hoverEvent(HoverEvent.showText(Component.text("Read it"))))
                        .append(Component.text(WHEN.format(Instant.ofEpochMilli(n.time())) + "\n", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false));
            }
            pages.add(page);
        }
        for (List<Component> d : details) pages.addAll(d);
        return Book.book(bookTitle, author, pages);
    }

    /** The pages of one news: title, when, the facts, the Staff's text; a link back to its list page at the bottom of each. */
    private static List<Component> detail(News n, int indexPage) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(n.title(), NamedTextColor.DARK_RED, true));
        lines.add(new Line(WHEN.format(Instant.ofEpochMilli(n.time())), NamedTextColor.DARK_GRAY, false));
        lines.add(new Line("", NamedTextColor.BLACK, false));
        for (String d : n.details()) for (String part : News.chunks(d, (ROWS - 1) * ROW_CHARS)) lines.add(new Line(part, NamedTextColor.BLACK, false));
        if (n.notes() != null) {
            lines.add(new Line("", NamedTextColor.BLACK, false));
            for (String para : n.notes().split("\\R+")) for (String part : News.chunks(para, (ROWS - 1) * ROW_CHARS)) lines.add(new Line(part, NamedTextColor.DARK_GREEN, false));
        }
        Component back = Component.text("\n< All news", NamedTextColor.DARK_BLUE).decoration(TextDecoration.BOLD, false)
                .clickEvent(ClickEvent.changePage(indexPage)).hoverEvent(HoverEvent.showText(Component.text("Back to the list")));
        List<Component> pages = new ArrayList<>();
        Component page = Component.empty();
        int rows = 0;
        for (Line l : lines) {
            int need = Math.max(1, (l.text().length() + ROW_CHARS - 1) / ROW_CHARS);   // a rough count: the book's font is not fixed-width
            if (rows > 0 && rows + need > ROWS) { pages.add(page.append(back)); page = Component.empty(); rows = 0; if (l.text().isEmpty()) continue; }
            page = page.append(Component.text((rows > 0 ? "\n" : "") + l.text(), l.color()).decoration(TextDecoration.BOLD, l.bold()));
            rows += need;
        }
        pages.add(page.append(back));
        return pages;
    }
}
