package com.cumania.war;

import com.cumania.war.site.SiteData;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Where the maps come from: the site's worlds list (maps.json) with the config's "maps" on top, and the territories.json of each map,
 * downloaded off the game thread and kept in a cache file so the plugin starts with the last data while the new one arrives.
 */
final class MapSource {
    /** One world of the site's list. */
    record SiteWorld(String key, String name, String territories, String world, String dynmapWorld) {}
    /** What a map is made of; two maps with the same spec are the same map. */
    record Spec(String name, String world, String url, String dynmapWorld, List<String> wars, String source) {}

    private final BmcWarPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    /** The site's worlds list as last read: each world it publishes a territories.json for. */
    private volatile List<SiteWorld> siteWorlds = List.of();
    private volatile String siteWorldsError;

    MapSource(BmcWarPlugin plugin) { this.plugin = plugin; }

    HttpClient http() { return http; }
    List<SiteWorld> siteWorlds() { return siteWorlds; }
    String siteWorldsError() { return siteWorldsError; }

    private String worldsUrl() { return plugin.getConfig().getString("site.worlds", "").trim(); }
    private Path worldsCache() { return plugin.getDataFolder().toPath().resolve("worlds-cache.json"); }

    void loadWorldsCache() {
        if (worldsUrl().isEmpty() || !Files.exists(worldsCache())) return;
        try { siteWorlds = parseWorlds(Files.readString(worldsCache())); } catch (Exception e) { plugin.getLogger().warning("Ignoring the cached worlds list: " + e); }
    }

    static List<SiteWorld> parseWorlds(String json) {
        var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        List<SiteWorld> out = new ArrayList<>();
        // maps.json (the site's list, read by the Xaero's mod too): {"maps": [{key, name, territories, dynmapWorld?, world?, ...}]}
        var list = root.has("maps") ? root.getAsJsonArray("maps") : root.getAsJsonArray("worlds");
        for (var e : list) {
            var o = e.getAsJsonObject();
            String key = o.get("key").getAsString();
            java.util.function.Function<String, String> str = k -> o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
            // the Minecraft world: "world", else the dynmap world's name, else (null) this server's main world
            String world = str.apply("world") != null ? str.apply("world") : str.apply("dynmapWorld");
            out.add(new SiteWorld(key, str.apply("name") != null ? str.apply("name") : key, str.apply("territories"), world,
                    str.apply("dynmapWorld") != null ? str.apply("dynmapWorld") : world));
        }
        return out;
    }

    /** The maps to run: every world on the site's list (unless the config turns it off or changes it), plus the config's own. */
    List<Spec> specs() {
        var c = plugin.getConfig();
        ConfigurationSection ms = c.getConfigurationSection("maps");
        List<Spec> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        String index = worldsUrl();
        if (!index.isEmpty()) for (SiteWorld w : siteWorlds) {
            // the config's entry for this world: by its key, else one with the same territories.json or the same Minecraft world
            String own = w.territories() == null ? null : URI.create(index).resolve(w.territories()).toString();
            String key = null;
            if (ms != null) for (String k : ms.getKeys(false)) if (k.equalsIgnoreCase(w.key())) key = k;
            if (key == null && ms != null) for (String k : ms.getKeys(false)) {
                ConfigurationSection m = ms.getConfigurationSection(k);
                if (m != null && !done.contains(k.toLowerCase(Locale.ROOT)) && (own != null && own.equalsIgnoreCase(m.getString("site", "")) || w.world() != null && w.world().equalsIgnoreCase(m.getString("world", k)))) { key = k; break; }
            }
            ConfigurationSection o = key == null ? null : ms.getConfigurationSection(key);
            done.add(w.key().toLowerCase(Locale.ROOT));
            if (key != null) done.add(key.toLowerCase(Locale.ROOT));
            if (o != null && !o.getBoolean("enabled", true)) continue;
            String url = o != null && o.getString("site", "").length() > 0 ? o.getString("site") : w.territories() == null ? null : URI.create(index).resolve(w.territories()).toString();
            if (url == null) continue;
            String main = Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().get(0).getName();   // a map that names no world is in the main one
            String world = o != null && o.contains("world") ? o.getString("world") : w.world() != null ? w.world() : main;
            out.add(new Spec(key != null ? key : w.key(), world, url, o != null && o.contains("dynmap-world") ? o.getString("dynmap-world") : w.dynmapWorld() != null ? w.dynmapWorld() : world,
                    o != null ? o.getStringList("wars") : List.of(), "site"));
        }
        if (ms != null) for (String name : ms.getKeys(false)) {
            ConfigurationSection m = ms.getConfigurationSection(name);
            if (m == null || done.contains(name.toLowerCase(Locale.ROOT)) || !m.getBoolean("enabled", true)) continue;
            String url = m.getString("site", "");
            if (url.isBlank()) {
                if (index.isEmpty() || siteWorldsError == null && !siteWorlds.isEmpty())
                    plugin.getLogger().warning("maps." + name + " has no \"site\" (its territories.json) and the site's worlds list has no world called " + name + ": left out.");
                continue;
            }
            String world = m.getString("world", name);
            out.add(new Spec(name, world, url, m.getString("dynmap-world", world), m.getStringList("wars"), "config"));
        }
        return out;
    }

    /** Downloads the worlds list and every map's site data (off the game thread) and swaps each in when it reads. */
    void fetchSite() {
        if (!downloading.compareAndSet(false, true)) return;   // a slow site must not pile one round of downloads on top of the last
        try {
            fetchWorlds();
            for (WarMap m : new ArrayList<>(plugin.maps())) if (BmcWarPlugin.worldOf(m) != null) fetch(m);   // a map whose world is not on this server downloads nothing
        } finally { downloading.set(false); }
    }

    private final java.util.concurrent.atomic.AtomicBoolean downloading = new java.util.concurrent.atomic.AtomicBoolean();

    /** The site's worlds list, read again: a world added or removed there becomes a map here, or stops being one. */
    private void fetchWorlds() {
        String url = worldsUrl();
        if (url.isEmpty()) return;
        try {
            HttpResponse<java.io.InputStream> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "bmc-war").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) { response.body().close(); throw new IllegalStateException("HTTP " + response.statusCode()); }
            String text = body(response);
            List<SiteWorld> read = parseWorlds(text);
            siteWorldsError = null;
            Files.createDirectories(worldsCache().getParent());
            Files.writeString(worldsCache(), text);
            if (read.equals(siteWorlds)) return;
            siteWorlds = read;
            CompletableFuture<Void> done = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> { try { plugin.syncMaps(false); } finally { done.complete(null); } });
            done.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (!String.valueOf(e).equals(siteWorldsError)) plugin.getLogger().warning("Could not read the site's worlds list from " + url + ": " + e + " (the last one read is kept).");
            siteWorldsError = String.valueOf(e);
        }
    }

    /** The most a download may be, zipped or not: a site that answers with something huge must not fill the server's memory. */
    static final int MAX_BODY = 16 * 1024 * 1024;

    /** The text of a download (read from a stream), unzipped when the server sent it zipped, and never more than MAX_BODY. */
    static String body(HttpResponse<java.io.InputStream> r) throws java.io.IOException {
        try (var raw = r.body()) {
            var in = r.headers().firstValue("Content-Encoding").orElse("").equalsIgnoreCase("gzip") ? new java.util.zip.GZIPInputStream(raw) : raw;
            byte[] b = in.readNBytes(MAX_BODY + 1);
            if (b.length > MAX_BODY) throw new java.io.IOException("the answer is bigger than " + MAX_BODY / 1024 / 1024 + " MB");
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    static String sha1(String text) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    void fetch(WarMap m) {
        String url = m.url;
        SiteData site = m.site;
        Path cache = m.cache;
        try {
            boolean first = m.fetchedAt == 0;   // the first download after the start always goes through: the cache it started from may be old
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "bmc-war").header("Accept-Encoding", "gzip");
            if (!first && m.etag != null) request.header("If-None-Match", m.etag);
            HttpResponse<java.io.InputStream> raw = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (raw.statusCode() == 304) { raw.body().close(); m.fetchedAt = System.currentTimeMillis(); m.error = null; return; }   // nothing changed
            if (raw.statusCode() != 200) { raw.body().close(); throw new IllegalStateException("HTTP " + raw.statusCode()); }
            String text = body(raw);
            String hash = sha1(text);
            m.etag = raw.headers().firstValue("ETag").orElse(null);
            if (!first && hash.equals(m.bodyHash)) { m.fetchedAt = System.currentTimeMillis(); m.error = null; return; }   // the same data: nothing to work out again
            SiteData parsed = SiteData.parse(text, plugin.fallbackRadius());
            m.bodyHash = hash;
            Files.createDirectories(cache.getParent());
            Files.writeString(cache, text);
            if (parsed.network() == null && plugin.getConfig().getBoolean("rules.in-play", true) && (site == null || site.network() != null))
                plugin.getLogger().warning("[" + m.name + "] The site's data has no Connections yet (\"network\" in territories.json): In Play cannot be worked out, so every locality of a war counts as In Play.");
            m.site = parsed;
            m.fetchedAt = System.currentTimeMillis();
            m.error = null;
            plugin.news().observe(m, text);
            Bukkit.getScheduler().runTask(plugin, () -> { plugin.forgetHeights(m); m.rebuildMarkers(); });
        } catch (Exception e) {
            if (!String.valueOf(e).equals(m.error)) plugin.getLogger().warning("[" + m.name + "] Could not update the site's data from " + url + ": " + e);
            m.error = String.valueOf(e);
        }
    }
}
