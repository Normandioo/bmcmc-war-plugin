package com.cumania.war;

import com.cumania.war.dynmap.DynmapMarkers;
import com.cumania.war.engine.CaptureEngine;
import com.cumania.war.engine.MemoryMarkers;
import com.cumania.war.engine.Markers;
import com.cumania.war.engine.Rules;
import com.cumania.war.site.SiteData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Team;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wars on the server: a side that stays inside a city's land (its province outline from the site's SVG) for the city's time, with nobody
 * of the holding side inside, takes it, and the city's icon in dynmap changes colour, which is what the site and the Xaero's mod read.
 * Cities, wars, sides and who plays for whom all come from the site's territories.json.
 */
public final class BmcWarPlugin extends JavaPlugin {
    private static final int[] DEFAULT_SIDE_COLORS = {0xD83A3A, 0x3A6FD8, 0x3AAE5A};

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final List<WarMap> maps = new ArrayList<>();
    private long lastTick;
    private int rebuildCountdown;

    @Override public void onEnable() {
        saveDefaultConfig();
        ready = new ReadyCheck(this);
        Bukkit.getPluginManager().registerEvents(ready, this);
        table = new CapitulationTable(this);
        Bukkit.getPluginManager().registerEvents(table, this);
        WarCommand command = new WarCommand(this);
        var cmd = getCommand("bmcwar");
        if (cmd != null) { cmd.setExecutor(command); cmd.setTabCompleter(command); }
        setup();
        lastTick = System.nanoTime();
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 40L, 20L);
        long minutes = Math.max(1, getConfig().getLong("site.refresh-minutes", 5));
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::fetchSite, 20L, minutes * 60 * 20L);
    }

    private ReadyCheck ready;
    ReadyCheck ready() { return ready; }
    private CapitulationTable table;
    CapitulationTable table() { return table; }

    @Override public void onDisable() {
        if (ready != null) ready.stopAll();
        if (table != null) table.stopAll();
        for (WarMap m : maps) if (m.engine != null) m.engine.clear();   // cities this plugin left contested go back to who held them
    }

    /** Builds the engine from the config. Called on start and on /bmcwar reload. */
    void setup() {
        for (WarMap m : maps) if (m.engine != null) m.engine.clear();   // put back the icons of the captures under way before starting over
        var c = getConfig();
        Map<String, Double> seconds = new HashMap<>();
        ConfigurationSection section = c.getConfigurationSection("capture.seconds");
        List<String> old = new ArrayList<>();
        if (section != null) for (String k : section.getKeys(false)) {
            String cat = switch (k.toLowerCase(java.util.Locale.ROOT)) { case "settlement" -> "Settlement"; case "fort" -> "Fort"; case "locality" -> "Locality"; default -> null; };
            if (cat != null) seconds.put(cat, section.getDouble(k)); else old.add(k);
        }
        if (!old.isEmpty()) getLogger().warning("capture.seconds now goes by Settlement / Fort / Locality (Laws 7.8.5-6); the old keys " + old
                + " are ignored and the Laws' times are used (Settlement 30 s, Fort 15 s, Locality at once). Delete plugins/BmcWar/config.yml to get the new one.");
        Rules rules = new Rules(seconds, 0, c.getDouble("capture.capital-multiplier", 1),
                !"pause".equalsIgnoreCase(c.getString("capture.on-resistance", "pause")), Math.max(0, c.getDouble("capture.grace-seconds", 0)),
                Math.max(0, c.getDouble("capture.cooldown-seconds", 0)), Math.max(1, c.getInt("capture.min-attackers", 1)),
                c.getInt("capture.underground.max-depth", 10), depthByCity(c.getConfigurationSection("capture.underground.cities")),
                c.getBoolean("rules.in-play", true), c.getBoolean("rules.encirclement", true), c.getBoolean("rules.require-engagement", true));
        // the maps: the old single-map keys (site.url, world, dynmap.world, wars) still work when there is no "maps" section
        List<WarMap> old2 = new ArrayList<>(maps);
        maps.clear();
        Path folder = getDataFolder().toPath();
        ConfigurationSection ms = c.getConfigurationSection("maps");
        if (ms != null && !ms.getKeys(false).isEmpty()) {
            for (String name : ms.getKeys(false)) {
                ConfigurationSection m = ms.getConfigurationSection(name);
                if (m == null) continue;
                String world = m.getString("world", name);
                String url = m.getString("site");
                if (url == null || url.isBlank()) { getLogger().warning("maps." + name + " has no \"site\" (its territories.json): left out."); continue; }
                maps.add(new WarMap(name, world, url, m.getString("dynmap-world", world), m.getStringList("wars"), folder.resolve("territories-cache-" + name + ".json")));
            }
        }
        if (maps.isEmpty()) {
            maps.add(new WarMap("main", c.getString("world", "world"), c.getString("site.url", "https://bmcmc.online/gaia/territories.json"),
                    c.getString("dynmap.world", "world"), c.getStringList("wars"), folder.resolve("territories-cache.json")));
        }
        var plugin = Bukkit.getPluginManager().getPlugin("dynmap");
        if (!(plugin instanceof org.dynmap.DynmapAPI)) getLogger().warning("Dynmap was not found: captures work, but nothing is shown on the map and everything is forgotten on restart.");
        for (WarMap m : maps) {
            if (plugin instanceof org.dynmap.DynmapAPI api) {
                m.dynmap = new DynmapMarkers(api, m.dynmapWorld, w -> getLogger().warning("[" + m.name + "] " + w));
                m.markers = m.dynmap;
            } else {
                m.dynmap = null;
                m.markers = new MemoryMarkers();
            }
            m.engine = new CaptureEngine(rules, m.markers);
            for (WarMap o : old2) if (o.url.equals(m.url)) m.site = o.site;   // keep what was downloaded while the new data arrives
            if (m.site == null && Files.exists(m.cache)) {   // start from the last download while the new one arrives
                try { m.site = SiteData.parse(Files.readString(m.cache), fallbackRadius()); } catch (Exception e) { getLogger().warning("[" + m.name + "] Ignoring the cached site data: " + e); }
            }
            m.rebuildMarkers();
            if (Bukkit.getWorld(m.world) == null) getLogger().warning("[" + m.name + "] There is no world called \"" + m.world + "\" on the server (yet): nobody can capture there until it exists.");
        }
        getLogger().info("War maps: " + String.join(", ", maps.stream().map(m -> m.name + " (world " + m.world + ")").toList()));
    }

    List<WarMap> maps() { return maps; }

    /** The map whose world the player is in, or null. */
    WarMap mapAt(Player p) {
        String w = p.getWorld().getName();
        for (WarMap m : maps) if (m.world.equals(w)) return m;
        return null;
    }

    /** The map a war belongs to (null when none has it any more). */
    WarMap mapOf(SiteData.War war) {
        for (WarMap m : maps) { SiteData s = m.site; if (s != null) for (SiteData.War w : s.wars()) if (w == war) return m; }
        for (WarMap m : maps) if (m.has(war)) return m;
        return null;
    }

    /** The map the sender stands on, else the first one with data. */
    WarMap mapFor(org.bukkit.command.CommandSender sender) {
        if (sender instanceof Player p) { WarMap m = mapAt(p); if (m != null && m.site != null) return m; }
        for (WarMap m : maps) if (m.site != null) return m;
        return maps.isEmpty() ? null : maps.get(0);
    }

    SiteData site(SiteData.War war) { WarMap m = mapOf(war); return m == null ? null : m.site; }
    CaptureEngine engine(SiteData.War war) { WarMap m = mapOf(war); return m == null ? maps.get(0).engine : m.engine; }
    Markers markers(SiteData.War war) { WarMap m = mapOf(war); return m == null ? maps.get(0).markers : m.markers; }
    /** The rules (the same on every map). */
    Rules rules() { return maps.get(0).engine.rules(); }

    /** A war's id, with its map's name when another map has a war of the same id ("map:id"). */
    String warId(SiteData.War war) {
        WarMap mine = mapOf(war);
        for (WarMap m : maps) if (m != mine && m.site != null && m.site.war(war.id()) != null) return (mine == null ? "?" : mine.name) + ":" + war.id();
        return war.id();
    }

    /** The running wars a text names: their id, or "map:id". */
    List<SiteData.War> warsNamed(String text) {
        List<SiteData.War> out = new ArrayList<>();
        for (SiteData.War w : handledWars()) {
            WarMap m = mapOf(w);
            if (w.id().equalsIgnoreCase(text) || m != null && (m.name + ":" + w.id()).equalsIgnoreCase(text)) out.add(w);
        }
        return out;
    }

    /** capture.underground.cities: "Name(Country)": depth -> keyed like a city ("name(country)" in lower case). */
    private static Map<String, Integer> depthByCity(ConfigurationSection section) {
        Map<String, Integer> out = new HashMap<>();
        if (section != null) for (String k : section.getKeys(false)) out.put(k.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT), section.getInt(k));
        return out;
    }

    private int fallbackRadius() { return getConfig().getInt("capture.fallback-radius", 40); }

    /** Downloads every map's site data (off the game thread) and swaps each in when it reads. */
    void fetchSite() { for (WarMap m : new ArrayList<>(maps)) fetch(m); }

    private void fetch(WarMap m) {
        String url = m.url;
        SiteData site = m.site;
        Path cache = m.cache;
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "bmc-war").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
            SiteData parsed = SiteData.parse(response.body(), fallbackRadius());
            Files.createDirectories(cache.getParent());
            Files.writeString(cache, response.body());
            if (parsed.network() == null && getConfig().getBoolean("rules.in-play", true) && (site == null || site.network() != null))
                getLogger().warning("[" + m.name + "] The site's data has no Connections yet (\"network\" in territories.json): In Play cannot be worked out, so every locality of a war counts as In Play.");
            m.site = parsed;
            Bukkit.getScheduler().runTask(this, m::rebuildMarkers);
        } catch (Exception e) {
            getLogger().warning("[" + m.name + "] Could not update the site's data from " + url + ": " + e);
        }
    }

    /** The wars this plugin runs, on every map: the ones listed for the map, or every war its site marks live that has not ended. */
    List<SiteData.War> handledWars() {
        List<SiteData.War> out = new ArrayList<>();
        for (WarMap m : maps) out.addAll(handledWars(m));
        return out;
    }

    List<SiteData.War> handledWars(WarMap m) {
        SiteData s = m.site;
        List<SiteData.War> out = new ArrayList<>();
        if (s == null) return out;
        for (SiteData.War w : s.wars()) {
            if (w.ended() || w.sides().size() < 2) continue;
            if (m.wars.isEmpty() ? w.live() : m.wars.stream().anyMatch(id -> id.equalsIgnoreCase(w.id()))) out.add(w);
        }
        return out;
    }

    private boolean eligible(Player p, String world) { return excluded(p, world) == null; }

    /** Since when each player has been flying or gliding without a break (System.currentTimeMillis()). */
    private final Map<java.util.UUID, Long> airSince = new HashMap<>();
    /** Players who asked for /bmcwar debug: whether they count is shown above their hotbar every second. */
    final java.util.Set<java.util.UUID> debug = new java.util.HashSet<>();

    /**
     * Why this player does not count for captures right now, or null when they do. Jumping, falling or being launched (a wind
     * charge, a mace) is ordinary fighting and counts; only flying (fly) or gliding (elytra) for more than capture.air-grace-seconds
     * in a row does not, so an elytra opened by mistake on a jump does not take anyone out of a capture.
     */
    String excluded(Player p, String world) {
        if (!p.getWorld().getName().equals(world)) return "not in the war's world";
        if (p.isDead()) return "dead";
        GameMode mode = p.getGameMode();
        if (mode == GameMode.SPECTATOR) return "spectator";
        if (p.hasPermission("bmcwar.bypass")) return "has bmcwar.bypass";
        if (p.hasMetadata("vanished")) return "vanished";
        if (getConfig().getBoolean("capture.ignore-flying", true) && (p.isFlying() || p.isGliding())) {
            long now = System.currentTimeMillis();
            long since = airSince.computeIfAbsent(p.getUniqueId(), k -> now);
            double grace = getConfig().getDouble("capture.air-grace-seconds", 3);
            if (now - since >= grace * 1000) return p.isGliding() ? "gliding (elytra)" : "flying";
        } else airSince.remove(p.getUniqueId());
        // riding a vehicle (the planes of MTVehicles, for one) high above the ground counts for nothing either
        int air = getConfig().getInt("capture.ignore-vehicle-above-blocks", 3);
        if (air > 0 && p.isInsideVehicle() && airborne(p, air)) return "in a vehicle off the ground";
        return null;
    }

    /** For /bmcwar where and /bmcwar debug: whether the player counts now, and why not (including too deep and no side). */
    String countsLine(Player p) {
        WarMap map = mapAt(p);
        SiteData s = map == null ? null : map.site;
        String why = map == null ? "not in a war map's world (" + String.join(", ", maps.stream().map(m -> m.world).toList()) + ")" : excluded(p, map.world);
        if (why == null && s != null && map.engine != null) {
            CaptureEngine engine = map.engine;
            List<SiteData.War> wars = handledWars(map);
            if (!wars.isEmpty() && wars.stream().allMatch(w -> sideOf(p, s, w) < 0)) why = "no side in the war (tag: " + (countryFromTag(p, s) == null ? "none" : countryFromTag(p, s)) + ")";
            if (why == null) {
                int depth = depthUnderground(p);
                for (SiteData.City c : s.cities()) {
                    if (!c.area().contains(p.getLocation().getX(), p.getLocation().getZ())) continue;
                    if (engine.rules().tooDeep(c, depth)) why = depth + " blocks underground (limit " + engine.rules().maxDepthFor(c) + " here)";
                    break;
                }
            }
        }
        return why == null ? "You count for captures." : "You do NOT count: " + why + ".";
    }

    /** True when there is nothing but air in the `blocks` blocks under the player (a vehicle on the ground has a block right below it). */
    private static boolean airborne(Player p, int blocks) {
        var loc = p.getLocation();
        var world = loc.getWorld();
        if (world == null) return false;
        int x = loc.getBlockX(), z = loc.getBlockZ();
        int y = loc.getBlockY();
        for (int i = 0; i <= blocks; i++) if (!world.getBlockAt(x, y - i, z).getType().isAir()) return false;
        return true;
    }

    // ----- Which country a player is in: their scoreboard team (the "tag"), checked against the site -----

    /** The player's scoreboard team: on the scoreboard they see, else the server's main one. */
    private static Team teamOf(Player p) {
        Team t = p.getScoreboard().getEntryTeam(p.getName());
        if (t == null) t = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(p.getName());
        return t;
    }

    /**
     * The country the player's tag names: the team's name, display name, prefix or suffix (whole, or one of its bracketed parts,
     * like "Santana" in "[Admin] [Santana]"), through players.team-aliases first, then the site's country names. Null when none matches.
     */
    String countryFromTag(Player p, SiteData s) {
        Team t = teamOf(p);
        if (t == null) return null;
        var plain = PlainTextComponentSerializer.plainText();
        List<String> texts = new ArrayList<>(List.of(t.getName(), plain.serialize(t.displayName()), plain.serialize(t.prefix()), plain.serialize(t.suffix())));
        ConfigurationSection aliases = getConfig().getConfigurationSection("players.team-aliases");
        for (String text : texts) {
            List<String> tries = new ArrayList<>();
            tries.add(text);
            for (String part : text.split("[\\[\\]()|:<>{}]")) if (!part.isBlank()) tries.add(part);
            for (String tag : tries) {
                if (aliases != null) for (String k : aliases.getKeys(false)) {
                    if (SiteData.simple(k).equals(SiteData.simple(tag)) && !SiteData.simple(tag).isEmpty()) {
                        String c = s.countryOfTag(aliases.getString(k));
                        if (c != null) return c;
                    }
                }
                String c = s.countryOfTag(tag);
                if (c != null) return c;
            }
        }
        return null;
    }

    /** players.side-from: team (default), site, or team-then-site. */
    private String sideFrom() { return getConfig().getString("players.side-from", "team").toLowerCase(java.util.Locale.ROOT); }

    /** The country the player plays for, by their tag and/or the site, as players.side-from says. Null when none. */
    String countryOf(Player p, SiteData s) {
        String mode = sideFrom();
        String country = mode.equals("site") ? null : countryFromTag(p, s);
        if (country == null && !mode.equals("team")) country = s.countryOfPlayer(p.getName());
        return country;
    }

    /** The side of the war the player fights on: their country's, or its overlord's (a vassal fights for its overlord). -1 when none. */
    int sideOf(Player p, SiteData s, SiteData.War war) { return s.sideOfNation(war, countryOf(p, s)); }

    // ----- Underground -----

    /** Ground that is not built: stone, dirt, sand, gravel, clay, terracotta, ores... A building's blocks, logs, water and ice are not. */
    private static boolean naturalGround(Material m) {
        if (Tag.BASE_STONE_OVERWORLD.isTagged(m) || Tag.DIRT.isTagged(m) || Tag.SAND.isTagged(m) || Tag.TERRACOTTA.isTagged(m)) return true;
        if (m.name().endsWith("_ORE")) return true;
        return switch (m) {
            case GRAVEL, CLAY, SANDSTONE, RED_SANDSTONE, CALCITE, DRIPSTONE_BLOCK, SMOOTH_BASALT, BEDROCK, SUSPICIOUS_GRAVEL, SUSPICIOUS_SAND -> true;
            default -> false;
        };
    }

    /**
     * How many blocks below the natural ground the player is: from the top of their column down, the first block of natural ground
     * above their feet is the ground; a building's roof and floors are passed over, so someone inside a house, on a tower or in a boat
     * is at depth 0, someone in a basement a few blocks, someone in a cave or a tunnel under a mountain many.
     */
    static int depthUnderground(Player p) {
        var loc = p.getLocation();
        World w = loc.getWorld();
        if (w == null) return 0;
        int x = loc.getBlockX(), z = loc.getBlockZ(), feet = loc.getBlockY();
        int top = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        for (int y = top; y > feet; y--) if (naturalGround(w.getBlockAt(x, y, z).getType())) return y - feet;
        return 0;
    }

    /** Once a second: who stands where, and what the clock does about it. */
    private void tick() {
        long now = System.nanoTime();
        double dt = Math.max(0.5, Math.min(5, (now - lastTick) / 1e9));
        lastTick = now;
        boolean rebuild = --rebuildCountdown <= 0;
        if (rebuild) rebuildCountdown = 60;
        for (WarMap map : maps) {
            SiteData s = map.site;
            if (s == null || map.engine == null) continue;
            if (rebuild) map.rebuildMarkers();   // markers are edited by hand: look them up again every minute
            tick(map, s, dt);
        }
        if (table != null) table.tick();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!debug.contains(p.getUniqueId())) continue;
            String line = countsLine(p);
            p.sendActionBar(Component.text("[BmcWar] " + line + (p.isFlying() ? " (flying)" : p.isGliding() ? " (gliding)" : p.isOnGround() ? "" : " (in the air)"),
                    line.startsWith("You count") ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        }
    }

    private void tick(WarMap map, SiteData s, double dt) {
        String world = map.world;
        CaptureEngine engine = map.engine;
        for (SiteData.War war : handledWars(map)) {
            List<CaptureEngine.Present> present = new ArrayList<>();
            Map<CaptureEngine.Present, Player> who = new HashMap<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!eligible(p, world)) continue;
                int side = sideOf(p, s, war);
                if (side < 0) continue;
                var loc = p.getLocation();
                var at = new CaptureEngine.Present(p.getName(), side, loc.getX(), loc.getZ(), depthUnderground(p));
                present.add(at); who.put(at, p);
            }
            for (CaptureEngine.Event e : engine.tick(s, war, present, dt)) announce(e);
            if (getConfig().getBoolean("announce.actionbar", true)) for (var e : who.entrySet()) actionBar(engine, s, war, e.getKey(), e.getValue());
        }
    }

    private void actionBar(CaptureEngine engine, SiteData s, SiteData.War war, CaptureEngine.Present at, Player player) {
        for (SiteData.City city : s.cities()) {
            if (!city.area().contains(at.x(), at.z())) continue;
            var st = engine.statusOf(war, city);
            if (st == null) return;
            String name = st.size() > 1 ? st.city().name() + " warzone" : city.name();
            if (st.contested()) player.sendActionBar(Component.text(name + ": contested", NamedTextColor.YELLOW));
            else player.sendActionBar(Component.text("Capturing " + name + ": " + clock(st.remaining()) + " left", sideColor(war, st.side())));
            return;
        }
    }

    static String clock(double seconds) { int s = (int) Math.ceil(seconds); return String.format("%d:%02d", s / 60, s % 60); }

    TextColor sideColor(SiteData.War war, int side) {
        if (side < 0 || side >= war.sides().size()) return NamedTextColor.GRAY;
        Integer rgb = war.sides().get(side).rgb();
        return TextColor.color(rgb != null ? rgb : DEFAULT_SIDE_COLORS[side % DEFAULT_SIDE_COLORS.length]);
    }

    Component side(SiteData.War war, int index) {
        return index < 0 || index >= war.sides().size() ? Component.text("nobody", NamedTextColor.GRAY) : Component.text(war.sides().get(index).name(), sideColor(war, index));
    }

    /** Minutes an Engagement of this kind lasts at most (engagement.minutes.<kind>, the Laws' times by default). */
    double engagementMinutes(com.cumania.war.engine.Engagement.Kind kind) {
        return getConfig().getDouble("engagement.minutes." + kind.name().toLowerCase(java.util.Locale.ROOT), kind.lawMinutes);
    }

    /** Says something about a war to everyone. */
    void broadcast(SiteData.War war, Component text) {
        Bukkit.getServer().broadcast(Component.text("[" + war.name() + "] ", NamedTextColor.RED).append(text));
    }

    /** The chat line for what happened, if the config wants it said. */
    private void announce(CaptureEngine.Event e) {
        var c = getConfig();
        if (e.type() == CaptureEngine.Type.ENGAGEMENT_END) {
            if (ready != null) ready.stop(e.war());
            getLogger().info("[" + e.war().id() + "] engagement over (time)");
            broadcast(e.war(), Component.text("The engagement is over: its time ran out. Captures stop until the next one.", NamedTextColor.GOLD));
            return;
        }
        String where = e.label() + " (" + e.city().country() + ")";
        Component prefix = Component.text("[" + e.war().name() + "] ", NamedTextColor.RED);
        Component line = null;
        switch (e.type()) {
            case START -> { if (c.getBoolean("announce.start", true)) line = prefix.append(side(e.war(), e.side())).append(Component.text(" is capturing " + where, NamedTextColor.GRAY)); }
            case CONTESTED -> { if (c.getBoolean("announce.contested", true)) line = prefix.append(Component.text(where + " is contested", NamedTextColor.YELLOW)); }
            case ABORTED -> { if (c.getBoolean("announce.aborted", false)) line = prefix.append(Component.text("The capture of " + where + " was abandoned", NamedTextColor.GRAY)); }
            case CAPTURED -> { if (c.getBoolean("announce.captured", true)) line = prefix.append(Component.text(where + " was captured by ", NamedTextColor.WHITE)).append(side(e.war(), e.side())); }
            default -> { }
        }
        getLogger().info("[" + e.war().id() + "] " + e.type() + " " + where + " side=" + e.side() + " from=" + e.from());
        if (line != null) Bukkit.getServer().broadcast(line);
    }
}
