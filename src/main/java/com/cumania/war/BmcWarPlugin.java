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
    private volatile SiteData site;
    private CaptureEngine engine;
    private Markers markers;
    private DynmapMarkers dynmap;
    private Path cache;
    private long lastTick;
    private int rebuildCountdown;

    @Override public void onEnable() {
        saveDefaultConfig();
        cache = getDataFolder().toPath().resolve("territories-cache.json");
        WarCommand command = new WarCommand(this);
        var cmd = getCommand("bmcwar");
        if (cmd != null) { cmd.setExecutor(command); cmd.setTabCompleter(command); }
        setup();
        lastTick = System.nanoTime();
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 40L, 20L);
        long minutes = Math.max(1, getConfig().getLong("site.refresh-minutes", 5));
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::fetchSite, 20L, minutes * 60 * 20L);
        if (Files.exists(cache)) {   // start from the last download while the new one arrives
            try { site = SiteData.parse(Files.readString(cache), fallbackRadius()); rebuildMarkers(); } catch (Exception e) { getLogger().warning("Ignoring the cached site data: " + e); }
        }
    }

    @Override public void onDisable() {
        if (engine != null) engine.clear();   // cities this plugin left contested go back to who held them
    }

    /** Builds the engine from the config. Called on start and on /bmcwar reload. */
    void setup() {
        if (engine != null) engine.clear();   // put back the icons of the captures under way before starting over
        var c = getConfig();
        Map<String, Double> seconds = new HashMap<>();
        ConfigurationSection section = c.getConfigurationSection("capture.seconds");
        if (section != null) for (String k : section.getKeys(false)) seconds.put(k, section.getDouble(k));
        Rules rules = new Rules(seconds, c.getDouble("capture.default-seconds", 0), c.getDouble("capture.capital-multiplier", 1),
                !"pause".equalsIgnoreCase(c.getString("capture.on-resistance", "pause")), Math.max(0, c.getDouble("capture.grace-seconds", 0)),
                Math.max(0, c.getDouble("capture.cooldown-seconds", 0)), Math.max(1, c.getInt("capture.min-attackers", 1)),
                c.getInt("capture.underground.max-depth", 10), depthByCity(c.getConfigurationSection("capture.underground.cities")));
        var plugin = Bukkit.getPluginManager().getPlugin("dynmap");
        if (plugin instanceof org.dynmap.DynmapAPI api) {
            dynmap = new DynmapMarkers(api, c.getString("dynmap.world", "world"), m -> getLogger().warning(m));
            markers = dynmap;
        } else {
            dynmap = null;
            markers = new MemoryMarkers();
            getLogger().warning("Dynmap was not found: captures work, but nothing is shown on the map and everything is forgotten on restart.");
        }
        engine = new CaptureEngine(rules, markers);
        rebuildMarkers();
    }

    /** capture.underground.cities: "Name(Country)": depth -> keyed like a city ("name(country)" in lower case). */
    private static Map<String, Integer> depthByCity(ConfigurationSection section) {
        Map<String, Integer> out = new HashMap<>();
        if (section != null) for (String k : section.getKeys(false)) out.put(k.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT), section.getInt(k));
        return out;
    }

    private int fallbackRadius() { return getConfig().getInt("capture.fallback-radius", 40); }

    private void rebuildMarkers() { if (dynmap != null && site != null) dynmap.rebuild(site); }

    /** Downloads the site's data (off the game thread) and swaps it in when it reads. */
    void fetchSite() {
        String url = getConfig().getString("site.url", "https://bmcmc.online/gaia/territories.json");
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "bmc-war").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
            SiteData parsed = SiteData.parse(response.body(), fallbackRadius());
            Files.createDirectories(cache.getParent());
            Files.writeString(cache, response.body());
            site = parsed;
            Bukkit.getScheduler().runTask(this, this::rebuildMarkers);
        } catch (Exception e) {
            getLogger().warning("Could not update the site's data from " + url + ": " + e);
        }
    }

    SiteData site() { return site; }
    CaptureEngine engine() { return engine; }
    Markers markers() { return markers; }

    /** The wars this plugin runs: the ones listed in the config, or every war the site marks live that has not ended. */
    List<SiteData.War> handledWars() {
        SiteData s = site;
        List<SiteData.War> out = new ArrayList<>();
        if (s == null) return out;
        List<String> ids = getConfig().getStringList("wars");
        for (SiteData.War w : s.wars()) {
            if (w.ended() || w.sides().size() < 2) continue;
            if (ids.isEmpty() ? w.live() : ids.stream().anyMatch(id -> id.equalsIgnoreCase(w.id()))) out.add(w);
        }
        return out;
    }

    private boolean eligible(Player p, String world) {
        if (!p.getWorld().getName().equals(world) || p.isDead()) return false;
        GameMode mode = p.getGameMode();
        if (mode == GameMode.SPECTATOR) return false;
        if (p.hasPermission("bmcwar.bypass")) return false;
        // flying (creative flight / fly) or gliding (elytra) players count for nothing, neither as attackers nor as resistance
        if (getConfig().getBoolean("capture.ignore-flying", true) && (p.isFlying() || p.isGliding())) return false;
        // riding a vehicle (the planes of MTVehicles, for one) high above the ground counts for nothing either
        int air = getConfig().getInt("capture.ignore-vehicle-above-blocks", 3);
        if (air > 0 && p.isInsideVehicle() && airborne(p, air)) return false;
        return !p.hasMetadata("vanished");
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
        SiteData s = site;
        if (s == null || engine == null) return;
        long now = System.nanoTime();
        double dt = Math.max(0.5, Math.min(5, (now - lastTick) / 1e9));
        lastTick = now;
        if (--rebuildCountdown <= 0) { rebuildCountdown = 60; rebuildMarkers(); }   // markers are edited by hand: look them up again every minute
        String world = getConfig().getString("world", "world");
        for (SiteData.War war : handledWars()) {
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
            if (getConfig().getBoolean("announce.actionbar", true)) for (var e : who.entrySet()) actionBar(s, war, e.getKey(), e.getValue());
        }
    }

    private void actionBar(SiteData s, SiteData.War war, CaptureEngine.Present at, Player player) {
        for (SiteData.City city : s.cities()) {
            if (!city.area().contains(at.x(), at.z())) continue;
            var st = engine.statusOf(war, city);
            if (st == null) return;
            if (st.contested()) player.sendActionBar(Component.text(city.name() + ": contested", NamedTextColor.YELLOW));
            else player.sendActionBar(Component.text("Capturing " + city.name() + ": " + clock(st.remaining()) + " left", sideColor(war, st.side())));
            return;
        }
    }

    static String clock(double seconds) { int s = (int) Math.ceil(seconds); return String.format("%d:%02d", s / 60, s % 60); }

    TextColor sideColor(SiteData.War war, int side) {
        if (side < 0 || side >= war.sides().size()) return NamedTextColor.GRAY;
        Integer rgb = war.sides().get(side).rgb();
        return TextColor.color(rgb != null ? rgb : DEFAULT_SIDE_COLORS[side % DEFAULT_SIDE_COLORS.length]);
    }

    private Component side(SiteData.War war, int index) {
        return index < 0 || index >= war.sides().size() ? Component.text("nobody", NamedTextColor.GRAY) : Component.text(war.sides().get(index).name(), sideColor(war, index));
    }

    /** The chat line for what happened, if the config wants it said. */
    private void announce(CaptureEngine.Event e) {
        var c = getConfig();
        String where = e.city().name() + " (" + e.city().country() + ")";
        Component prefix = Component.text("[" + e.war().name() + "] ", NamedTextColor.RED);
        Component line = null;
        switch (e.type()) {
            case START -> { if (c.getBoolean("announce.start", true)) line = prefix.append(side(e.war(), e.side())).append(Component.text(" is capturing " + where, NamedTextColor.GRAY)); }
            case CONTESTED -> { if (c.getBoolean("announce.contested", true)) line = prefix.append(Component.text(where + " is contested", NamedTextColor.YELLOW)); }
            case ABORTED -> { if (c.getBoolean("announce.aborted", false)) line = prefix.append(Component.text("The capture of " + where + " was abandoned", NamedTextColor.GRAY)); }
            case CAPTURED -> { if (c.getBoolean("announce.captured", true)) line = prefix.append(Component.text(where + " was captured by ", NamedTextColor.WHITE)).append(side(e.war(), e.side())); }
            case CLEARED -> { }
        }
        getLogger().info("[" + e.war().id() + "] " + e.type() + " " + where + " side=" + e.side() + " from=" + e.from());
        if (line != null) Bukkit.getServer().broadcast(line);
    }
}
