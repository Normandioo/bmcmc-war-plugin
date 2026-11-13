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
    /** last: what the sidebar says now (one slot), so it is sent again only when it changes. */
    private record Shown(Scoreboard own, Scoreboard original, View[] last) {}

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
        shown.put(p.getUniqueId(), new Shown(own, original, new View[1]));
        p.setScoreboard(own);
        update(p, new HashMap<>());
        return true;
    }

    private int ticks;

    /** Every second the table is brought up to date (and sent only if it changed); every 5 seconds the teams are copied again. */
    void tick() {
        boolean copy = ticks++ % 5 == 0;   // tags change rarely: copying every team of every viewer each second costs more than it is worth
        Map<SiteData.War, View> views = new HashMap<>();   // worked out once per war, not once per player
        for (UUID id : new ArrayList<>(shown.keySet())) {
            Player p = Bukkit.getPlayer(id);
            Shown s = shown.get(id);
            if (p == null || !p.isOnline()) { shown.remove(id); continue; }
            if (p.getScoreboard() != s.own()) {   // another plugin gave them a scoreboard: leave it to it
                shown.remove(id);
                p.sendMessage(Component.text("The capitulation table was turned off: another plugin changed your scoreboard. /bmcwar table to show it again.", NamedTextColor.GRAY));
                continue;
            }
            if (copy) copyTeams(s.original(), s.own());
            update(p, views);
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

    /** One line of the table. colour: -1 grey, -2 yellow (the engagement line), otherwise the index of the side whose colour it takes. */
    private record Row(String text, int score, int colour, String suffix) {}

    /** What the table says for one war (null = no engagement going on); worked out once a second and shared by every player who sees it. */
    private record View(SiteData.War war, Component title, List<Row> rows) {}

    private View view(SiteData.War war) {
        SiteData site = war == null ? null : plugin.site(war);
        if (site == null) return new View(null, Component.text("Capitulation", NamedTextColor.GOLD, TextDecoration.BOLD), List.of(new Row("No engagement", 0, -1, "")));
        List<Row> rows = new ArrayList<>();
        WarGraph g = plugin.engine(war).graph(site, war);
        List<Capitulation.Score> scores = new ArrayList<>(Capitulation.of(site, war, g));
        scores.sort(Comparator.comparingDouble(Capitulation.Score::score).reversed().thenComparing(Capitulation.Score::nation));
        for (Capitulation.Score c : scores.subList(0, Math.min(13, scores.size()))) {
            int pct = (int) Math.min(100, Math.round(c.score()));
            String suffix = c.capitulated() ? " CAPITULATED" : c.capitalTaken() ? " capital taken" : "";
            rows.add(new Row(c.nation(), pct, c.side(), suffix));
        }
        Engagement e = plugin.engine(war).engagement(war);
        if (e != null) rows.add(new Row(e.kind().label + (e.waiting() ? " (waiting)" : e.paused() ? " (paused)" : Double.isInfinite(e.endsAt()) ? "" : " " + BmcWarPlugin.clock(e.left(plugin.engine(war).clock(war)))), -1, -2, ""));
        return new View(war, Component.text(war.name(), NamedTextColor.GOLD, TextDecoration.BOLD), rows);
    }

    private void update(Player p, Map<SiteData.War, View> views) {
        Shown s = shown.get(p.getUniqueId());
        if (s == null) return;
        Scoreboard board = s.own();
        Objective old = board.getObjective(DisplaySlot.SIDEBAR);
        View v = views.computeIfAbsent(pickWar(p), this::view);
        View was = s.last()[0];
        // with no engagement the table never changes: nothing is rebuilt or sent (it used to be, to every viewer, every second)
        if (was != null && old != null && was.war() == v.war() && was.title().equals(v.title()) && was.rows().equals(v.rows())) return;
        s.last()[0] = v;

        // Rebuilt each second on a fresh objective, then swapped in, so the sidebar does not flicker or keep stale rows
        String name = old != null && old.getName().equals(OBJECTIVE) ? OBJECTIVE + "2" : OBJECTIVE;
        Objective stale = board.getObjective(name);
        if (stale != null) stale.unregister();
        Objective obj = board.registerNewObjective(name, Criteria.DUMMY, v.title());
        Set<String> used = new HashSet<>();
        for (Row r : v.rows()) {
            String entry = r.text();
            while (!used.add(entry)) entry += " ";
            Score score = obj.getScore(entry);
            score.setScore(r.score());
            NamedTextColor nameColour = r.colour() == -2 ? NamedTextColor.YELLOW : r.colour() < 0 ? NamedTextColor.GRAY : plainColour(plugin.sideColor(v.war(), r.colour()));
            score.customName(Component.text(r.text(), nameColour).append(Component.text(r.suffix(), NamedTextColor.RED)));
            score.numberFormat(r.colour() == -2 || r.colour() == -1 && v.war() == null ? NumberFormat.blank()
                    : NumberFormat.fixed(Component.text(r.score() + "%", r.suffix().contains("CAPITULATED") ? NamedTextColor.RED : NamedTextColor.WHITE)));
        }
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        if (old != null && old.getName().startsWith(OBJECTIVE)) old.unregister();
    }

    /** The war shown: one with an engagement going on; on the map the player is in first, then one they fight in. */
    private SiteData.War pickWar(Player p) {
        WarMap here = plugin.mapAt(p);
        SiteData.War first = null, mine = null;
        for (SiteData.War w : plugin.handledWars()) {
            if (plugin.engine(w).engagement(w) == null) continue;
            SiteData site = plugin.site(w);
            boolean side = site != null && plugin.sideOf(p, site, w) >= 0;
            if (here != null && plugin.mapOf(w) == here && side) return w;
            if (side && mine == null) mine = w;
            if (first == null) first = w;
        }
        if (here != null) for (SiteData.War w : plugin.handledWars(here)) if (plugin.engine(w).engagement(w) != null) return w;
        return mine != null ? mine : first;
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
            if (src.hasColor()) {
                NamedTextColor c = NamedTextColor.nearestTo(src.color());
                if (!dst.hasColor() || !c.equals(dst.color())) dst.color(c);
            }
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
