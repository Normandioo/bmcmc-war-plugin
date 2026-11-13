package com.cumania.war;

import com.cumania.war.news.FontWidths;
import com.cumania.war.news.News;
import com.cumania.war.news.NewsDiff;
import com.cumania.war.news.NewsFilter;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The list of news as a chest menu that a resource pack dresses up (/news). The chest's title is the menu's picture: the 176x222
 * background and the headlines, all font characters (assets/bmcnews/font, built by tools/build_pack.py). Icons and buttons are items with their own
 * models, which the game draws over the picture; the items that only carry a tooltip use a model that draws nothing. Four news a page, the
 * buttons turn pages and pick a kind of news; a click on a news opens it in a window (NewsDialog). The player's inventory is never touched and every click in
 * the menu is cancelled. A page or filter change opens the chest again (this Paper cannot change a chest's title with a font), so the mouse
 * goes back to the middle of the screen.
 */
final class NewsMenu implements Listener {
    private static final Key MENU = Key.key("bmcnews", "menu");
    private static final char BG = '';
    private static final int BG_ADVANCE = 177;   // the picture is 176 wide, a glyph advances its width + 1
    private static final int TEXT_X = 28, TEXT_MAX = 120;   // inside a card: x from 26 to 149
    /** Two lines of text in each of the 4 cards (the pack's fonts l<top>, tools/build_pack.py keeps the same list). */
    private static final int[] LINE_TOPS = {36, 44, 54, 62, 72, 80, 90, 98};
    private static final TextColor INK = TextColor.color(0x3b2f1e);
    private static final int CARDS = 4;
    private static final int NAV = 5 * 9;   // the navigation row: previous (0), filters (2-5), close (7), next (8)
    private static final NewsFilter[] FILTERS = NewsFilter.values();
    private static final String[] FILTER_ICON = {"filter_war", "filter_alliance", "filter_ultimatum", "filter_truce"};
    private static final String[] FILTER_NAME = {"Wars", "Alliances and subjects", "Ultimatums", "Truces and pacts"};

    private final BmcWarPlugin plugin;

    NewsMenu(BmcWarPlugin plugin) { this.plugin = plugin; }

    private static final class Holder implements InventoryHolder {
        Inventory inventory;
        List<News> news = List.of();   // the ones on this page
        NewsFilter filter;             // null = every kind
        int page, pages;
        @Override public Inventory getInventory() { return inventory; }
    }

    /** A "space" character of the pack's menu font: moves the text by this many pixels (-256 to 255). */
    private static char space(int advance) { return (char) (0xF000 + advance + 256); }

    /** The title: background first, then every line of text; each line starts at TEXT_X, so the pen goes back by the width of the last one. */
    static Component title(List<News> news) {
        var title = Component.text();
        title.append(Component.text(String.valueOf(space(-8)) + BG, NamedTextColor.WHITE).font(MENU));   // a chest's title starts at x = 8
        int pen = BG_ADVANCE;
        for (int card = 0; card < Math.min(CARDS, news.size()); card++) {
            List<String> lines = FontWidths.wrap(FontWidths.ascii(news.get(card).title()), TEXT_MAX, 2);
            for (int k = 0; k < lines.size(); k++) {
                title.append(Component.text(String.valueOf(space(TEXT_X - pen)), NamedTextColor.WHITE).font(MENU));
                title.append(Component.text(lines.get(k), INK).font(Key.key("bmcnews", "l" + LINE_TOPS[card * 2 + k])));
                pen = TEXT_X + FontWidths.width(lines.get(k));
            }
        }
        return title.build();
    }

    static String icon(News.Type type) {
        return switch (type) {
            case WAR -> "war"; case PEACE -> "peace"; case POST -> "post"; case ALLIANCE -> "alliance"; case ALLIANCE_JOIN -> "alliance_join";
            case ALLIANCE_LEAVE -> "alliance_leave"; case ALLIANCE_END -> "alliance_end"; case TRUCE -> "truce"; case NAP -> "nap";
            case ULTIMATUM -> "ultimatum"; case VASSAL -> "vassal"; case INDEPENDENCE -> "independence";
        };
    }

    static ItemStack item(String model, String name, List<Component> lore) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(NamespacedKey.fromString("bmcnews:" + model));
        meta.displayName(Component.text(name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(l -> l.decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static Component gray(String text) { return Component.text(text, NamedTextColor.GRAY); }

    private static List<Component> tooltip(News n) {
        List<Component> lore = new ArrayList<>();
        if (!n.details().isEmpty()) for (String line : News.chunks(n.details().get(0), 42)) lore.add(gray(line));
        lore.add(Component.text(NewsDiff.clock().format(Instant.ofEpochMilli(n.time())), NamedTextColor.DARK_GRAY));
        lore.add(Component.text("Click to read it", NamedTextColor.AQUA));
        return lore;
    }

    void open(Player p) { open(p, null, 0); }

    private void open(Player p, NewsFilter filter, int page) {
        if (filter == null && page == 0) plugin.news().markRead(p);   // opening the menu counts as having seen the news
        List<News> all = plugin.news().latest(200).stream().filter(n -> filter == null || filter.matches(n.type())).toList();
        int pages = Math.max(1, (all.size() + CARDS - 1) / CARDS), at = Math.max(0, Math.min(page, pages - 1));
        Holder holder = new Holder();
        holder.news = all.subList(at * CARDS, Math.min(all.size(), (at + 1) * CARDS));
        holder.filter = filter; holder.page = at; holder.pages = pages;
        holder.inventory = Bukkit.createInventory(holder, 54, title(holder.news));
        for (int card = 0; card < holder.news.size(); card++) {
            News n = holder.news.get(card);
            int first = (card + 1) * 9;
            holder.inventory.setItem(first, item(icon(n.type()), n.title(), tooltip(n)));
            for (int c = 1; c <= 7; c++) holder.inventory.setItem(first + c, item("hidden", n.title(), tooltip(n)));   // the card: a tooltip, nothing drawn
            holder.inventory.setItem(first + 8, item("open", "Read it", List.of()));
        }
        if (at > 0) holder.inventory.setItem(NAV, item("prev", "Previous page", List.of(gray("Page " + at + " of " + pages))));
        for (int i = 0; i < FILTERS.length; i++) {
            boolean on = FILTERS[i] == filter;
            holder.inventory.setItem(NAV + 2 + i, item(on ? FILTER_ICON[i] + "_on" : FILTER_ICON[i], FILTER_NAME[i], List.of(gray(on ? "Click to show every kind of news" : "Click to show only these"))));
        }
        holder.inventory.setItem(NAV + 7, item("close", "Close", List.of()));
        if (at < pages - 1) holder.inventory.setItem(NAV + 8, item("next", "Next page", List.of(gray("Page " + (at + 2) + " of " + pages))));
        if (holder.news.isEmpty()) p.sendMessage(gray(filter == null ? "[News] No news yet." : "[News] No news of this kind."));
        p.openInventory(holder.inventory);
    }

    /** Opens the menu again on the next tick (a click cannot swap the chest it came from). */
    private void again(Player p, NewsFilter filter, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> open(p, filter, page));
    }

    private static Holder ours(InventoryView view) {
        return view.getTopInventory().getHolder(false) instanceof Holder h ? h : null;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Holder holder = ours(e.getView());
        if (holder == null) return;
        e.setCancelled(true);   // nothing moves, in the menu or in the player's own inventory below it
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot == NAV + 7) p.closeInventory();
        else if (slot == NAV && holder.page > 0) again(p, holder.filter, holder.page - 1);
        else if (slot == NAV + 8 && holder.page < holder.pages - 1) again(p, holder.filter, holder.page + 1);
        else if (slot >= NAV + 2 && slot < NAV + 2 + FILTERS.length) {
            NewsFilter picked = FILTERS[slot - NAV - 2];
            again(p, picked == holder.filter ? null : picked, 0);
        } else if (slot >= 9 && slot < NAV && slot / 9 <= holder.news.size()) {
            News n = holder.news.get(slot / 9 - 1);
            Bukkit.getScheduler().runTask(plugin, () -> plugin.dialog().show(p, n));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (ours(e.getView()) != null) e.setCancelled(true);
    }
}
