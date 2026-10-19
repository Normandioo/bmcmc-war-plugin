package com.cumania.war;

import com.cumania.war.engine.Markers;
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
import java.util.Locale;

/** /bmcwar status | where | set &lt;city&gt; &lt;red|blue|green|contested|uncontrolled|clear&gt; | reset [war] confirm | reload */
final class WarCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBS = List.of("status", "where", "set", "reset", "reload");
    private static final String USAGE = "Usage: /bmcwar status | where | set <city> <red|blue|green|contested|uncontrolled|clear> | reset [war] confirm | reload";
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
        SiteData site = plugin.site();
        if (site == null) { say(sender, "The site's data has not arrived yet.", NamedTextColor.GRAY); return; }
        List<SiteData.War> wars = plugin.handledWars();
        if (wars.isEmpty()) { say(sender, "No war is being run (none is live on the site).", NamedTextColor.GRAY); return; }
        for (SiteData.War war : wars) {
            say(sender, war.name(), NamedTextColor.RED);
            var list = plugin.engine().statuses(site, war);
            if (list.isEmpty()) say(sender, "  No capture under way.", NamedTextColor.GRAY);
            for (var st : list) {
                String who = war.sides().get(st.side()).name();
                say(sender, "  " + st.city().name() + " (" + st.city().country() + "): " + (st.contested() ? "contested" : who + " " + BmcWarPlugin.clock(st.remaining()) + " left"), st.contested() ? NamedTextColor.YELLOW : NamedTextColor.WHITE);
            }
        }
    }

    private void where(CommandSender sender) {
        if (!(sender instanceof Player p)) { say(sender, "Only a player can ask where they stand.", NamedTextColor.GRAY); return; }
        SiteData site = plugin.site();
        if (site == null) { say(sender, "The site's data has not arrived yet.", NamedTextColor.GRAY); return; }
        var loc = p.getLocation();
        String country = plugin.countryOf(p, site);
        String tag = plugin.countryFromTag(p, site);
        say(sender, "Your country: " + (country == null ? "none (no scoreboard team matches a country on the site)" : country
                + (tag != null ? " (from your tag)" : " (from the site's population lists)")), NamedTextColor.GRAY);
        int depth = BmcWarPlugin.depthUnderground(p);
        for (SiteData.City city : site.cities()) {
            if (!city.area().contains(loc.getX(), loc.getZ())) continue;
            say(sender, "You are in " + city.name() + " (" + city.country() + "), a " + city.kind() + (switch (city.limit()) {
                case ZONE -> " (inside its capture limit)";
                case PROVINCE -> "";
                case CIRCLE -> " (no outline on the site: a round area is used)";
            }) + ".", NamedTextColor.WHITE);
            int max = plugin.engine().rules().maxDepthFor(city);
            if (depth > 0) say(sender, "  You are " + depth + " blocks underground; here up to " + (max < 0 ? "any depth" : max + " blocks") + " counts"
                    + (plugin.engine().rules().tooDeep(city, depth) ? ", so you do NOT count." : "."), plugin.engine().rules().tooDeep(city, depth) ? NamedTextColor.YELLOW : NamedTextColor.GRAY);
            for (SiteData.War war : plugin.handledWars()) {
                int mark = plugin.markers().read(war, city), owner = site.sideOfCity(war, city);
                String holder = mark >= 0 ? war.sides().get(mark).name() : mark == Markers.CONTESTED ? "contested" : mark == Markers.UNCONTROLLED ? "uncontrolled"
                        : owner >= 0 ? war.sides().get(owner).name() + " (its owner)" : "nobody in the war";
                say(sender, "  " + war.name() + ": " + holder, NamedTextColor.GRAY);
                int mine = plugin.sideOf(p, site, war);
                say(sender, "  You fight for: " + (mine >= 0 ? war.sides().get(mine).name() : "no side"), NamedTextColor.GRAY);
            }
            return;
        }
        say(sender, "You are not inside any city's land.", NamedTextColor.GRAY);
        for (SiteData.War war : plugin.handledWars()) {
            int mine = plugin.sideOf(p, site, war);
            say(sender, "  " + war.name() + ", you fight for: " + (mine >= 0 ? war.sides().get(mine).name() : "no side"), NamedTextColor.GRAY);
        }
    }

    private void set(CommandSender sender, String[] args) {
        if (args.length < 3) { say(sender, "Usage: /bmcwar set <city> <red|blue|green|contested|uncontrolled|clear>", NamedTextColor.GRAY); return; }
        SiteData site = plugin.site();
        if (site == null) { say(sender, "The site's data has not arrived yet.", NamedTextColor.GRAY); return; }
        String wanted = args[1].toLowerCase(Locale.ROOT);
        List<SiteData.City> found = new ArrayList<>();
        for (SiteData.City c : site.cities()) if (c.key().equals(wanted) || c.name().toLowerCase(Locale.ROOT).equals(wanted)) found.add(c);
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
        for (SiteData.War w : plugin.handledWars()) { if (war == null || site.sideOfCity(w, city) >= 0) war = w; }
        if (war == null) { say(sender, "No war is being run.", NamedTextColor.RED); return; }
        if (!plugin.markers().write(war, city, state)) { say(sender, "The icon could not be changed (see the console).", NamedTextColor.RED); return; }
        plugin.engine().forget(war, city);
        say(sender, city.name() + " is now " + args[2].toLowerCase(Locale.ROOT) + " in " + war.name() + ".", NamedTextColor.GREEN);
    }

    /**
     * /bmcwar reset [war] confirm: the fronts back to how the war began, with no capture on either side: every city of the war's
     * countries gets the colour of its own country's side again, and every capture under way is dropped.
     */
    private void reset(CommandSender sender, String[] args) {
        SiteData site = plugin.site();
        if (site == null) { say(sender, "The site's data has not arrived yet.", NamedTextColor.GRAY); return; }
        List<SiteData.War> wars = plugin.handledWars();
        if (wars.isEmpty()) { say(sender, "No war is being run.", NamedTextColor.RED); return; }
        boolean confirm = args.length > 1 && args[args.length - 1].equalsIgnoreCase("confirm");
        String id = args.length > 1 && !args[1].equalsIgnoreCase("confirm") ? args[1] : null;
        SiteData.War war = null;
        if (id != null) { for (SiteData.War w : wars) if (w.id().equalsIgnoreCase(id)) war = w; }
        else if (wars.size() == 1) war = wars.get(0);
        if (war == null) {
            say(sender, id == null ? "Several wars are running: /bmcwar reset <war> confirm, with one of: " + String.join(", ", wars.stream().map(SiteData.War::id).toList())
                    : "No running war with the id " + id + ".", NamedTextColor.RED);
            return;
        }
        List<SiteData.City> cities = new ArrayList<>();
        for (SiteData.City c : site.cities()) if (site.sideOfCity(war, c) >= 0) cities.add(c);
        if (!confirm) {
            say(sender, "This puts all " + cities.size() + " cities of " + war.name() + " back with their own side, as at the start of the war, and drops every capture under way.", NamedTextColor.YELLOW);
            say(sender, "To do it: /bmcwar reset " + war.id() + " confirm", NamedTextColor.YELLOW);
            return;
        }
        plugin.engine().reset(war);
        int done = 0, failed = 0;
        for (SiteData.City c : cities) { if (plugin.markers().write(war, c, site.sideOfCity(war, c))) done++; else failed++; }
        say(sender, "Reset: " + done + " cities back with their own side" + (failed > 0 ? ", " + failed + " could not be changed (see the console)" : "") + ".", failed > 0 ? NamedTextColor.YELLOW : NamedTextColor.GREEN);
        Bukkit.getServer().broadcast(Component.text("[" + war.name() + "] ", NamedTextColor.RED).append(Component.text("The fronts were reset: every city is back with its own side.", NamedTextColor.WHITE)));
        plugin.getLogger().info("[" + war.id() + "] fronts reset by " + sender.getName() + ": " + done + " cities, " + failed + " failed");
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) { for (String s : SUBS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s); return out; }
        if (args.length == 2 && args[0].equalsIgnoreCase("set") && plugin.site() != null) {
            for (SiteData.City c : plugin.site().cities()) if (c.name().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)) && out.size() < 40) out.add(c.name());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            for (String s : STATES) if (s.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            for (SiteData.War w : plugin.handledWars()) if (w.id().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(w.id());
            if ("confirm".startsWith(args[1].toLowerCase(Locale.ROOT))) out.add("confirm");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("reset")) {
            if ("confirm".startsWith(args[2].toLowerCase(Locale.ROOT))) out.add("confirm");
        }
        return out;
    }
}
