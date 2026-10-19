package com.cumania.war;

import com.cumania.war.engine.Capitulation;
import com.cumania.war.engine.Engagement;
import com.cumania.war.engine.WarGraph;
import com.cumania.war.site.SiteData;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The capitulation table: a sidebar, turned on and off by each player for themselves (/bmcwar table), showing how close each
 * nation of the war with an engagement going on is to Capitulating (its score out of 100, 7.9).
 * A sidebar is per scoreboard, so a player who turns it on gets a scoreboard of their own. The teams of the scoreboard they had
 * (their country tag, nametag colours) are copied into it every second, so tags keep showing and keep working for the plugin.
 * If another plugin gives the player a different scoreboard meanwhile, the table steps aside instead of fighting over it.
 */
final class CapitulationTable implements Listener {
    private record Shown(Scoreboard own, Scoreboard original) {}

    private final BmcWarPlugin plugin;
    private final Map<UUID, Shown> shown = new HashMap<>();

    CapitulationTable(BmcWarPlugin plugin) { this.plugin = plugin; }

    boolean isOn(Player p) { return shown.containsKey(p.getUniqueId()); }

    /** Turns the table on or off for this player only. True when it is now on. */
    boolean toggle(Player p) {
        Shown s = shown.remove(p.getUniqueId());
        if (s != null) {
            if (p.getScoreboard() == s.own()) p.setScoreboard(s.original());
            return false;
        }
        Scoreboard original = p.getScoreboard();
        Scoreboard own = Bukkit.getScoreboardManager().getNewScoreboard();
        copyTeams(original, own);
        shown.put(p.getUniqueId(), new Shown(own, original));
        p.setScoreboard(own);
        update(p);
        return true;
    }

    /** Every second: the teams copied again, and the table brought up to date. */
    void tick() {
        for (UUID id : new ArrayList<>(shown.keySet())) {
            Player p = Bukkit.getPlayer(id);
            Shown s = shown.get(id);
            if (p == null || !p.isOnline()) { shown.remove(id); continue; }
            if (p.getScoreboard() != s.own()) {   // another plugin gave them a scoreboard: leave it to it
                shown.remove(id);
                p.sendMessage(Component.text("The capitulation table was turned off: another plugin changed your scoreboard. /bmcwar table to show it again.", NamedTextColor.GRAY));
                continue;
            }
            copyTeams(s.original(), s.own());
            update(p);
        }
    }

    /** Puts back everyone's scoreboard (on shutdown). */
    void stopAll() {
        for (Map.Entry<UUID, Shown> e : shown.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && p.getScoreboard() == e.getValue().own()) p.setScoreboard(e.getValue().original());
        }
        shown.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { shown.remove(event.getPlayer().getUniqueId()); }

    // ----- The table -----

    private static final String OBJECTIVE = "bmcwar_capitulation";

    private void update(Player p) {
        Shown s = shown.get(p.getUniqueId());
        if (s == null) return;
        Scoreboard board = s.own();
        Objective old = board.getObjective(DisplaySlot.SIDEBAR);
        SiteData site = plugin.site();
        SiteData.War war = site == null ? null : pickWar(p, site);

        List<String[]> rows = new ArrayList<>();   // entry text, score, colour index (-1 grey, side otherwise), suffix
        Component title;
        if (war == null) {
            title = Component.text("Capitulation", NamedTextColor.GOLD, TextDecoration.BOLD);
            rows.add(new String[]{"No engagement", "0", "-1", ""});
        } else {
            title = Component.text(war.name(), NamedTextColor.GOLD, TextDecoration.BOLD);
            WarGraph g = plugin.engine().graph(site, war);
            List<Capitulation.Score> scores = new ArrayList<>(Capitulation.of(site, war, g));
            scores.sort(Comparator.comparingDouble(Capitulation.Score::score).reversed().thenComparing(Capitulation.Score::nation));
            for (Capitulation.Score c : scores.subList(0, Math.min(13, scores.size()))) {
                int pct = (int) Math.min(100, Math.round(c.score()));
                String suffix = c.capitulated() ? " CAPITULATED" : c.capitalTaken() ? " capital taken" : "";
                rows.add(new String[]{c.nation(), String.valueOf(pct), String.valueOf(c.side()), suffix});
            }
            Engagement e = plugin.engine().engagement(war);
            if (e != null) rows.add(new String[]{e.kind().label + (Double.isInfinite(e.endsAt()) ? "" : " " + BmcWarPlugin.clock(e.endsAt() - plugin.engine().clock(war))), "-1", "-2", ""});
        }

        // Rebuilt each second on a fresh objective, then swapped in, so the sidebar does not flicker or keep stale rows
        String name = old != null && old.getName().equals(OBJECTIVE) ? OBJECTIVE + "2" : OBJECTIVE;
        Objective stale = board.getObjective(name);
        if (stale != null) stale.unregister();
        Objective obj = board.registerNewObjective(name, Criteria.DUMMY, title);
        Set<String> used = new HashSet<>();
        for (String[] r : rows) {
            String entry = r[0];
            while (!used.add(entry)) entry += " ";
            Score score = obj.getScore(entry);
            score.setScore(Integer.parseInt(r[1]));
            int colour = Integer.parseInt(r[2]);
            NamedTextColor nameColour = colour == -2 ? NamedTextColor.YELLOW : colour < 0 ? NamedTextColor.GRAY : war == null ? NamedTextColor.WHITE : plainColour(plugin.sideColor(war, colour));
            score.customName(Component.text(r[0], nameColour).append(Component.text(r[3], NamedTextColor.RED)));
            score.numberFormat(colour == -2 || colour == -1 && war == null ? NumberFormat.blank()
                    : NumberFormat.fixed(Component.text(r[1] + "%", Integer.parseInt(r[1]) > 100 || r[3].contains("CAPITULATED") ? NamedTextColor.RED : NamedTextColor.WHITE)));
        }
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        if (old != null && old.getName().startsWith(OBJECTIVE)) old.unregister();
    }

    /** The war shown: one with an engagement going on, the player's own first. */
    private SiteData.War pickWar(Player p, SiteData site) {
        SiteData.War first = null;
        for (SiteData.War w : plugin.handledWars()) {
            if (plugin.engine().engagement(w) == null) continue;
            if (plugin.sideOf(p, site, w) >= 0) return w;
            if (first == null) first = w;
        }
        return first;
    }

    private static NamedTextColor plainColour(net.kyori.adventure.text.format.TextColor c) { return NamedTextColor.nearestTo(c); }

    // ----- Keeping the tags -----

    /** Makes the teams of `to` the same as those of `from`: names, entries, colour, prefix, suffix and options. */
    static void copyTeams(Scoreboard from, Scoreboard to) {
        if (from == null || from == to) return;
        Set<String> names = new HashSet<>();
        for (Team src : from.getTeams()) {
            names.add(src.getName());
            Team dst = to.getTeam(src.getName());
            if (dst == null) dst = to.registerNewTeam(src.getName());
            if (!dst.displayName().equals(src.displayName())) dst.displayName(src.displayName());
            if (!dst.prefix().equals(src.prefix())) dst.prefix(src.prefix());
            if (!dst.suffix().equals(src.suffix())) dst.suffix(src.suffix());
            if (src.hasColor()) { if (!dst.hasColor() || dst.color() != src.color()) dst.color(src.color()); }
            else if (dst.hasColor()) dst.color(null);
            for (Team.Option o : Team.Option.values()) if (dst.getOption(o) != src.getOption(o)) dst.setOption(o, src.getOption(o));
            if (dst.allowFriendlyFire() != src.allowFriendlyFire()) dst.setAllowFriendlyFire(src.allowFriendlyFire());
            if (dst.canSeeFriendlyInvisibles() != src.canSeeFriendlyInvisibles()) dst.setCanSeeFriendlyInvisibles(src.canSeeFriendlyInvisibles());
            Set<String> want = src.getEntries();
            for (String e : new ArrayList<>(dst.getEntries())) if (!want.contains(e)) dst.removeEntry(e);
            for (String e : want) if (!dst.hasEntry(e)) dst.addEntry(e);
        }
        for (Team t : new ArrayList<>(to.getTeams())) if (!names.contains(t.getName())) t.unregister();
    }
}
