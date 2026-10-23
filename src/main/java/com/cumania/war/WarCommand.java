package com.cumania.war;

import com.cumania.war.engine.Capitulation;
import com.cumania.war.engine.CaptureEngine;
import com.cumania.war.engine.Engagement;
import com.cumania.war.engine.Markers;
import com.cumania.war.engine.WarGraph;
import com.cumania.war.site.SiteData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** /bmcwar status | where | engagement ... | set &lt;city&gt; &lt;state&gt; | reset [war] confirm | reload */
final class WarCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBS = List.of("status", "where", "table", "debug", "engagement", "set", "reset", "reload");
    private static final List<String> KINDS = List.of("strategic", "tactical", "assault", "raid", "free");
    private static final String USAGE = "Usage: /bmcwar status | where | table | debug | engagement [start <strategic|tactical|assault|raid|free> [war=<id>] [cities...] | stop [war]] | set <city> <red|blue|green|contested|uncontrolled|clear> | reset [war] confirm | reload";
    private static final List<String> STATES = List.of("red", "blue", "green", "contested", "uncontrolled", "clear");

    private final BmcWarPlugin plugin;

    WarCommand(BmcWarPlugin plugin) { this.plugin = plugin; }

    private static void say(CommandSender to, String text, NamedTextColor color) { to.sendMessage(Component.text(text, color)); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "where" -> where(sender);
            case "set" -> { if (admin(sender)) set(sender, args); }
            case "reset" -> { if (admin(sender)) reset(sender, args); }
            case "engagement", "eng" -> engagement(sender, args);
            case "table", "capitulation", "cap" -> {
                if (!(sender instanceof Player p)) { say(sender, "Only a player can show the table.", NamedTextColor.GRAY); return true; }
                if (plugin.table().toggle(p)) say(sender, "Capitulation table on (only for you). /bmcwar table again to hide it.", NamedTextColor.GREEN);
                else say(sender, "Capitulation table off.", NamedTextColor.GRAY);
            }
            case "debug" -> {
                if (!(sender instanceof Player p)) { say(sender, "Only a player can use debug.", NamedTextColor.GRAY); return true; }
                if (plugin.debug.remove(p.getUniqueId())) say(sender, "Debug off.", NamedTextColor.GRAY);
                else { plugin.debug.add(p.getUniqueId()); say(sender, "Debug on: above your hotbar you will see, every second, whether you count for captures and why not. /bmcwar debug again to turn it off.", NamedTextColor.GREEN); }
            }
            case "reload" -> {
                if (!admin(sender)) return true;
                plugin.reloadConfig();
                plugin.setup();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, plugin::fetchSite);
                say(sender, "Reloaded. The site's data is being downloaded again.", NamedTextColor.GREEN);
            }
            default -> say(sender, USAGE, NamedTextColor.GRAY);
        }
        return true;
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("bmcwar.admin")) return true;
        say(sender, "You do not have permission to do that.", NamedTextColor.RED);
        return false;
    }

    private void status(CommandSender sender) {
        if (plugin.maps().stream().allMatch(m -> m.site == null)) { say(sender, "The site's data has not arrived yet.", NamedTextColor.GRAY); return; }
        List<SiteData.War> wars = plugin.handledWars();
        if (wars.isEmpty()) { say(sender, "No war is being run (none is live on the site).", NamedTextColor.GRAY); return; }
        var rules = plugin.rules();
        for (SiteData.War war : wars) {
            SiteData site = plugin.site(war);
            WarMap map = plugin.mapOf(war);
            if (site == null || map == null) continue;
            say(sender, war.name() + (plugin.maps().size() > 1 ? " (map " + map.name + ", world " + map.world + ")" : ""), NamedTextColor.RED);
            WarGraph g = plugin.engine(war).graph(site, war);
            say(sender, "  " + engagementLine(war), NamedTextColor.GOLD);
            if (plugin.engine(war).engagement(war) != null && sender.hasPermission("bmcwar.admin")) say(sender, "  Ready check: " + plugin.ready().summary(war), NamedTextColor.GRAY);
            long inWar = g.units().stream().filter(u -> !u.neutral()).count(), inPlay = g.units().stream().filter(u -> u.inPlay).count();
            long encircled = g.units().stream().filter(u -> u.encircled).count();
            if (!g.hasNetwork()) say(sender, "  No Connections from the site: In Play cannot be worked out, every locality of the war counts.", NamedTextColor.GRAY);
            else say(sender, "  " + inWar + " localities in the war, " + inPlay + " In Play" + (encircled > 0 ? ", " + encircled + " encircled" : "")
                    + (rules.inPlay() ? "" : " (the In Play rule is off)"), NamedTextColor.GRAY);
            if (!g.ties().isEmpty()) say(sender, "  " + g.ties().size() + " tie(s) for a Connection across neutral land wait for Staff (\"shortCircuits\" on the site).", NamedTextColor.YELLOW);
            if (!g.theaterUnknown().isEmpty()) say(sender, "  Theater entries the plugin does not know (left out): " + String.join(", ", g.theaterUnknown()), NamedTextColor.YELLOW);
            var list = plugin.engine(war).statuses(site, war);
            if (list.isEmpty()) say(sender, "  No capture under way.", NamedTextColor.GRAY);
            for (var st : list) {
                String who = war.sides().get(st.side()).name();
                String name = st.size() > 1 ? st.city().name() + " warzone" : st.city().name();
                say(sender, "  " + name + " (" + st.city().country() + "): " + (st.contested() ? "contested" : who + " " + BmcWarPlugin.clock(st.remaining()) + " left"), st.contested() ? NamedTextColor.YELLOW : NamedTextColor.WHITE);
            }
            for (Capitulation.Score c : Capitulation.of(site, war, g)) {
                if (c.score() <= 0 && !c.capitalTaken()) continue;
                say(sender, "  " + c.nation() + ": capitulation score " + Math.round(c.score()) + "/100, " + c.taken() + " of " + c.total() + " settlements taken, capital "
                        + (c.capital() == null ? "unknown" : c.capitalTaken() ? "TAKEN" : "held") + (c.capitulated() ? " - CAPITULATED" : ""), c.capitulated() ? NamedTextColor.RED : NamedTextColor.GRAY);
            }
        }
    }

    private String engagementLine(SiteData.War war) {
        Engagement e = plugin.engine(war).engagement(war);
        if (e == null) return plugin.rules().requireEngagement() ? "No engagement: nothing can be captured now (7.8.8)." : "No engagement (captures are allowed anyway: rules.require-engagement is off).";
        if (e.waiting()) return e.kind().label + " called, waiting for everyone to be ready (/bmcwar engagement go to begin it anyway).";
        String left = Double.isInfinite(e.endsAt()) ? "until stopped" : BmcWarPlugin.clock(e.endsAt() - plugin.engine(war).clock(war)) + " left";
        String targets = e.targets().isEmpty() ? "the whole Theater" : names(plugin.site(war), e.targets());
        return e.kind().label + " going on (" + left + "), over " + targets + ".";
    }

    private String names(SiteData site, Set<String> keys) {
        List<String> out = new ArrayList<>();
        for (String k : keys) { SiteData.City c = site == null ? null : site.city(k); out.add(c == null ? k : c.name()); }
        return String.join(", ", out);
    }

    private void where(CommandSender sender) {
        if (!(sender instanceof Player p)) { say(sender, "Only a player can ask where they stand.", NamedTextColor.GRAY); return; }
        WarMap map = plugin.mapAt(p);
        if (map == null) {
            say(sender, "This world (" + p.getWorld().getName() + ") is not a war map. War maps: "
                    + String.join(", ", plugin.maps().stream().map(m -> m.name + " (world " + m.world + ")").toList()) + ".", NamedTextColor.GRAY);
            return;
        }
        SiteData site = map.site;
        if (site == null) { say(sender, "The site's data for " + map.name + " has not arrived yet.", NamedTextColor.GRAY); return; }
        if (plugin.maps().size() > 1) say(sender, "War map: " + map.name + ".", NamedTextColor.GRAY);
        var loc = p.getLocation();
        String country = plugin.countryOf(p, site);
        String tag = plugin.countryFromTag(p, site);
        say(sender, "Your country: " + (country == null ? "none (no scoreboard team matches a country on the site)" : country
                + (tag != null ? " (from your tag)" : " (from the site's population lists)")), NamedTextColor.GRAY);
        String counts = plugin.countsLine(p);
        say(sender, counts, counts.startsWith("You count") ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
        int depth = BmcWarPlugin.depthUnderground(p);
        for (SiteData.City city : site.cities()) {
            if (!city.area().contains(loc.getX(), loc.getZ())) continue;
            say(sender, "You are in " + city.name() + " (" + city.country() + "), a " + city.kind() + (switch (city.limit()) {
                case ZONE -> " (inside its capture limit)";
                case PROVINCE -> "";
                case CIRCLE -> " (no outline on the site: a round area is used)";
            }) + ".", NamedTextColor.WHITE);
            var rules = plugin.rules();
            int max = rules.maxDepthFor(city);
            if (depth > 0) say(sender, "  You are " + depth + " blocks underground; here up to " + (max < 0 ? "any depth" : max + " blocks") + " counts"
                    + (rules.tooDeep(city, depth) ? ", so you do NOT count." : "."), rules.tooDeep(city, depth) ? NamedTextColor.YELLOW : NamedTextColor.GRAY);
            for (SiteData.War war : plugin.handledWars(map)) {
                WarGraph g = map.engine.graph(site, war);
                WarGraph.Unit u = g.unitOf(city);
                if (u != null && u.warzone()) say(sender, "  Part of a warzone, captured as one (7.8.7): " + String.join(", ", u.members.stream().map(SiteData.City::name).toList()), NamedTextColor.GRAY);
                CaptureEngine.Blocked why = map.engine.blocked(g, u, map.engine.engagement(war));
                String front = u == null || u.neutral() ? "outside this war's Theater" : !g.hasNetwork() ? "In Play (no Connections from the site)"
                        : (u.inPlay ? "In Play" : "not In Play") + (u.encircled ? ", encircled" : "")
                        + (!u.by.isEmpty() ? " (connected to " + String.join(", ", u.by.stream().map(WarGraph.Unit::label).toList()) + ")" : "")
                        + (!u.byBreaking.isEmpty() ? " (its capture would break the encirclement of " + String.join(", ", u.byBreaking.stream().map(WarGraph.Unit::label).toList()) + ")" : "")
                        + (!u.inPlay && !u.encircledBy.isEmpty() ? " (next to " + String.join(", ", u.encircledBy.stream().map(WarGraph.Unit::label).toList()) + ", encircled: 7.8.10)" : "");
                say(sender, "  " + war.name() + " front: " + front, NamedTextColor.GRAY);
                say(sender, "  " + (why == null ? "It can be captured now." : switch (why) {
                    case NOT_IN_WAR -> "It cannot be captured in this war.";
                    case NOT_IN_PLAY -> "It cannot be captured now: it is not In Play (7.8.1).";
                    case NO_ENGAGEMENT -> "It cannot be captured now: no engagement is going on (7.8.8).";
                    case WAITING -> "It cannot be captured yet: the engagement begins when everyone is ready.";
                    case NOT_A_TARGET -> "It cannot be captured now: it is not a target of the engagement going on.";
                }), why == null ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
                int mark = map.markers.read(war, city), owner = site.sideOfCity(war, city);
                String holder = mark >= 0 ? war.sides().get(mark).name() : mark == Markers.CONTESTED ? "contested" : mark == Markers.UNCONTROLLED ? "uncontrolled"
                        : owner >= 0 ? war.sides().get(owner).name() + " (its owner)" : "nobody in the war";
                say(sender, "  " + war.name() + ": " + holder, NamedTextColor.GRAY);
                int mine = plugin.sideOf(p, site, war);
                say(sender, "  You fight for: " + (mine >= 0 ? war.sides().get(mine).name() : "no side"), NamedTextColor.GRAY);
            }
            return;
        }
        say(sender, "You are not inside any city's land.", NamedTextColor.GRAY);
        for (SiteData.War war : plugin.handledWars(map)) {
            int mine = plugin.sideOf(p, site, war);
            say(sender, "  " + war.name() + ", you fight for: " + (mine >= 0 ? war.sides().get(mine).name() : "no side"), NamedTextColor.GRAY);
        }
    }

    private void set(CommandSender sender, String[] args) {
        if (args.length < 3) { say(sender, "Usage: /bmcwar set <city> <red|blue|green|contested|uncontrolled|clear>", NamedTextColor.GRAY); return; }
        WarMap map = plugin.mapFor(sender);
        SiteData site = map == null ? null : map.site;
        if (site == null) { say(sender, "The site's data has not arrived yet.", NamedTextColor.GRAY); return; }
        String wanted = args[1].toLowerCase(Locale.ROOT);
        List<SiteData.City> found = new ArrayList<>();
        for (SiteData.City c : site.cities()) if (c.key().equals(wanted) || c.name().toLowerCase(Locale.ROOT).equals(wanted)) found.add(c);
        if (found.isEmpty()) for (WarMap m : plugin.maps()) {   // not on the sender's map: another map's city
            if (m == map || m.site == null) continue;
            for (SiteData.City c : m.site.cities()) if (c.key().equals(wanted) || c.name().toLowerCase(Locale.ROOT).equals(wanted)) found.add(c);
            if (!found.isEmpty()) { map = m; site = m.site; break; }
        }
        if (found.isEmpty()) { say(sender, "No city called " + args[1] + ".", NamedTextColor.RED); return; }
        if (found.size() > 1) { say(sender, "Several cities are called " + args[1] + ": use Name(Country).", NamedTextColor.RED); return; }
        int state = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "red" -> 0; case "blue" -> 1; case "green" -> 2;
            case "contested" -> Markers.CONTESTED; case "uncontrolled" -> Markers.UNCONTROLLED; case "clear" -> Markers.NONE;
            default -> -99;
        };
        if (state == -99) { say(sender, "The state is one of: " + String.join(", ", STATES), NamedTextColor.RED); return; }
        SiteData.City city = found.get(0);
        SiteData.War war = null;
        for (SiteData.War w : plugin.handledWars(map)) { if (war == null || site.sideOfCity(w, city) >= 0) war = w; }
        if (war == null) { say(sender, "No war is being run on " + map.name + ".", NamedTextColor.RED); return; }
        if (!map.markers.write(war, city, state)) { say(sender, "The icon could not be changed (see the console).", NamedTextColor.RED); return; }
        map.engine.forget(war, city);
        say(sender, city.name() + " is now " + args[2].toLowerCase(Locale.ROOT) + " in " + war.name() + ".", NamedTextColor.GREEN);
    }

    /** /bmcwar engagement [status] | start <kind> [war=<id>] [cities...] | stop [war] */
    private void engagement(CommandSender sender, String[] args) {
        if (plugin.maps().stream().allMatch(m -> m.site == null)) { say(sender, "The site's data has not arrived yet.", NamedTextColor.GRAY); return; }
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        List<String> rest = new ArrayList<>();
        String warId = null;
        for (int i = 2; i < args.length; i++) {
            if (args[i].toLowerCase(Locale.ROOT).startsWith("war=")) warId = args[i].substring(4); else rest.add(args[i]);
        }
        if ((action.equals("stop") || action.equals("go")) && warId == null && !rest.isEmpty()) warId = rest.remove(0);
        List<SiteData.War> wars = plugin.handledWars();
        if (wars.isEmpty()) { say(sender, "No war is being run.", NamedTextColor.RED); return; }
        SiteData.War war = pick(sender, wars, warId);
        if (war == null && warId == null) {   // several wars: the one on the sender's map, when it is the only one there
            WarMap here = sender instanceof Player p ? plugin.mapAt(p) : null;
            List<SiteData.War> mine = here == null ? List.of() : plugin.handledWars(here);
            if (mine.size() == 1) war = mine.get(0);
        }
        if (action.equals("status")) {
            for (SiteData.War w : wars) if (war == null || w == war) {
                say(sender, w.name() + ": " + engagementLine(w), NamedTextColor.GOLD);
                if (plugin.engine(w).engagement(w) != null && sender.hasPermission("bmcwar.admin")) say(sender, "  Ready check: " + plugin.ready().summary(w), NamedTextColor.GRAY);
            }
            return;
        }
        if (!admin(sender)) return;
        if (war == null) {
            if (warId == null || plugin.warsNamed(warId).isEmpty())
                say(sender, warId == null ? "Several wars are running: add war=<id>, one of " + String.join(", ", wars.stream().map(plugin::warId).toList())
                        : "No running war with the id " + warId + ".", NamedTextColor.RED);
            return;
        }
        SiteData site = plugin.site(war);
        if (site == null) { say(sender, "The site's data for that war has not arrived yet.", NamedTextColor.GRAY); return; }
        if (action.equals("stop")) {
            Engagement e = plugin.engine(war).stopEngagement(site, war);
            plugin.ready().stop(war);
            if (e == null) { say(sender, "No engagement is going on in " + war.name() + ".", NamedTextColor.GRAY); return; }
            plugin.broadcast(war, Component.text("The " + e.kind().label.toLowerCase(Locale.ROOT) + " is over" + (e.kind() == Engagement.Kind.RAID
                    ? ": the raided fort goes back to who held it (7.17.13)." : ". Captures stop until the next engagement."), NamedTextColor.GOLD));
            plugin.getLogger().info("[" + war.id() + "] engagement stopped by " + sender.getName());
            return;
        }
        if (action.equals("go")) {
            if (!plugin.ready().begin(war, sender.getName())) say(sender, "No engagement is waiting to begin in " + war.name() + ".", NamedTextColor.GRAY);
            return;
        }
        if (!action.equals("start") || rest.isEmpty()) { say(sender, "Usage: /bmcwar engagement start <" + String.join("|", KINDS) + "> [war=<id>] [cities...] | go [war] | stop [war] | status", NamedTextColor.GRAY); return; }
        Engagement.Kind kind;
        try { kind = Engagement.Kind.valueOf(rest.remove(0).toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { say(sender, "The kind is one of: " + String.join(", ", KINDS), NamedTextColor.RED); return; }

        WarGraph g = plugin.engine(war).graph(site, war);
        Set<String> targets = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        for (String name : rest) {
            List<SiteData.City> found = new ArrayList<>();
            String wanted = name.toLowerCase(Locale.ROOT);
            for (SiteData.City c : site.cities()) if (c.key().equals(wanted) || c.name().toLowerCase(Locale.ROOT).equals(wanted)) found.add(c);
            if (found.isEmpty()) { say(sender, "No city called " + name + ".", NamedTextColor.RED); return; }
            if (found.size() > 1) { say(sender, "Several cities are called " + name + ": use Name(Country).", NamedTextColor.RED); return; }
            SiteData.City c = found.get(0);
            WarGraph.Unit u = g.unitOf(c);
            if (u == null || u.neutral()) { say(sender, c.name() + " is not in this war's Theater.", NamedTextColor.RED); return; }
            for (SiteData.City m : u.members) targets.add(m.key());   // a Warzone is added whole (7.13.5)
            if (u.warzone()) warnings.add(c.name() + " is part of a warzone: all " + u.members.size() + " of its localities are targets (7.13.5).");
            if (kind != Engagement.Kind.RAID && g.hasNetwork() && !u.inPlay) warnings.add(c.name() + " is not In Play: it cannot be captured unless that changes (7.8.8).");
            if (kind == Engagement.Kind.ASSAULT && c.capital()) warnings.add(c.name() + " is a capital: an Assault may not target a Realm or Hinterland Capital (7.16.4).");
            if (kind == Engagement.Kind.ASSAULT && c.settlement()) warnings.add(c.name() + " is a Settlement: an Assault may only target it outside a Mainland or Hinterland, or if Uncontrolled (7.16.2). Check it.");
            if (kind == Engagement.Kind.TACTICAL && !c.settlement()) warnings.add(c.name() + " is not a Settlement: a Tactical Campaign is over Settlements (7.13.2).");
            if (kind == Engagement.Kind.RAID && !c.fort()) warnings.add(c.name() + " is not a Fort: a Raid takes place in a Fort (7.17.8).");
        }
        switch (kind) {
            case STRATEGIC, FREE -> { if (!targets.isEmpty()) warnings.add("A " + kind.label + " is over the whole Theater: the cities given are ignored."); targets.clear(); }
            case TACTICAL, ASSAULT -> { if (targets.isEmpty()) { say(sender, "Name the cities it is fought over.", NamedTextColor.RED); return; } }
            case RAID -> { if (targets.isEmpty()) { say(sender, "Name the Fort that is raided.", NamedTextColor.RED); return; } }
        }
        double minutes = kind == Engagement.Kind.FREE ? 0 : plugin.engagementMinutes(kind);
        Engagement e = plugin.engine(war).startEngagement(site, war, kind, targets, minutes, sender.getName(), plugin.ready().holds());
        for (String w : warnings) say(sender, "Note: " + w, NamedTextColor.YELLOW);
        plugin.broadcast(war, Component.text(kind.label + (e.waiting() ? " called" : " started") + (minutes > 0 ? " (" + (int) minutes + " min)" : "") + ", over "
                + (e.targets().isEmpty() ? "the whole Theater" : names(site, e.targets())) + "." + (e.waiting() ? " It begins when everyone is ready." : ""), NamedTextColor.GOLD));
        plugin.ready().start(war, e);
        plugin.getLogger().info("[" + war.id() + "] " + kind + " started by " + sender.getName() + " over " + (e.targets().isEmpty() ? "the Theater" : e.targets()));
    }

    /** The war a command is about: the one named (id or map:id), or the only one running. Null (and says why) when that is not clear. */
    private SiteData.War pick(CommandSender sender, List<SiteData.War> wars, String id) {
        if (id == null) return wars.size() == 1 ? wars.get(0) : null;
        List<SiteData.War> named = plugin.warsNamed(id);
        if (named.size() == 1) return named.get(0);
        if (named.size() > 1) say(sender, "Several maps have a war called " + id + ": use one of " + String.join(", ", named.stream().map(plugin::warId).toList()) + ".", NamedTextColor.RED);
        return null;
    }

    /**
     * /bmcwar reset [war] confirm: the fronts back to how the war began, with no capture on either side: every city of the war's
     * countries gets the colour of its own country's side again, and every capture under way is dropped.
     */
    private void reset(CommandSender sender, String[] args) {
        List<SiteData.War> wars = plugin.handledWars();
        if (wars.isEmpty()) { say(sender, "No war is being run.", NamedTextColor.RED); return; }
        boolean confirm = args.length > 1 && args[args.length - 1].equalsIgnoreCase("confirm");
        String id = args.length > 1 && !args[1].equalsIgnoreCase("confirm") ? args[1] : null;
        SiteData.War war = pick(sender, wars, id);
        if (war == null) {
            if (id == null || plugin.warsNamed(id).isEmpty())
                say(sender, id == null ? "Several wars are running: /bmcwar reset <war> confirm, with one of: " + String.join(", ", wars.stream().map(plugin::warId).toList())
                        : "No running war with the id " + id + ".", NamedTextColor.RED);
            return;
        }
        SiteData site = plugin.site(war);
        if (site == null) { say(sender, "The site's data for that war has not arrived yet.", NamedTextColor.GRAY); return; }
        List<SiteData.City> cities = new ArrayList<>();
        for (SiteData.City c : site.cities()) if (site.sideOfCity(war, c) >= 0) cities.add(c);
        if (!confirm) {
            say(sender, "This puts all " + cities.size() + " cities of " + war.name() + " back with their own side, as at the start of the war, and drops every capture under way.", NamedTextColor.YELLOW);
            say(sender, "To do it: /bmcwar reset " + plugin.warId(war) + " confirm", NamedTextColor.YELLOW);
            return;
        }
        plugin.engine(war).reset(war);
        int done = 0, failed = 0;
        for (SiteData.City c : cities) { if (plugin.markers(war).write(war, c, site.sideOfCity(war, c))) done++; else failed++; }
        say(sender, "Reset: " + done + " cities back with their own side" + (failed > 0 ? ", " + failed + " could not be changed (see the console)" : "") + ".", failed > 0 ? NamedTextColor.YELLOW : NamedTextColor.GREEN);
        Bukkit.getServer().broadcast(Component.text("[" + war.name() + "] ", NamedTextColor.RED).append(Component.text("The fronts were reset: every city is back with its own side.", NamedTextColor.WHITE)));
        plugin.getLogger().info("[" + war.id() + "] fronts reset by " + sender.getName() + ": " + done + " cities, " + failed + " failed");
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) { for (String s : SUBS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s); return out; }
        WarMap here = plugin.mapFor(sender);
        SiteData site = here == null ? null : here.site;
        if (args.length == 2 && args[0].equalsIgnoreCase("set") && site != null) {
            for (SiteData.City c : site.cities()) if (c.name().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)) && out.size() < 40) out.add(c.name());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            for (String s : STATES) if (s.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args[0].equalsIgnoreCase("engagement") || args[0].equalsIgnoreCase("eng")) {
            String last = args[args.length - 1].toLowerCase(Locale.ROOT);
            if (args.length == 2) { for (String s : List.of("status", "start", "go", "stop")) if (s.startsWith(last)) out.add(s); }
            else if (args.length == 3 && args[1].equalsIgnoreCase("start")) { for (String s : KINDS) if (s.startsWith(last)) out.add(s); }
            else if (args.length >= 4 && args[1].equalsIgnoreCase("start") && site != null) {
                for (SiteData.City c : site.cities()) if (c.name().toLowerCase(Locale.ROOT).startsWith(last) && out.size() < 40) out.add(c.name());
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            for (SiteData.War w : plugin.handledWars()) if (plugin.warId(w).toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(plugin.warId(w));
            if ("confirm".startsWith(args[1].toLowerCase(Locale.ROOT))) out.add("confirm");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("reset")) {
            if ("confirm".startsWith(args[2].toLowerCase(Locale.ROOT))) out.add("confirm");
        }
        return out;
    }
}
