package com.cumania.war.news;

import com.cumania.war.site.TerritoryFile;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The news kept across restarts (plugins/BmcWar/news.json): the facts last seen on each map's site, the latest news, and the last news
 * each player read. Safe to use from the download thread and the game thread at once. No Bukkit classes.
 */
public final class NewsStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** What is written in news.json. */
    private static final class Saved {
        long lastId;
        Map<String, List<String>> facts = new HashMap<>();
        List<News> items = new ArrayList<>();
        Map<String, Long> read = new HashMap<>();
    }

    private final Path file;
    private Saved s = new Saved();

    public NewsStore(Path file) { this.file = file; }

    /** Reads news.json; an unreadable file starts over (and is kept aside as news.json.bad). */
    public synchronized void load() throws Exception {
        if (!Files.exists(file)) return;
        try {
            Saved read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
            if (read == null) return;
            if (read.facts == null) read.facts = new HashMap<>();
            if (read.items == null) read.items = new ArrayList<>();
            if (read.read == null) read.read = new HashMap<>();
            s = read;
        } catch (Exception e) {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            throw e;
        }
    }

    public synchronized void save() throws Exception {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");   // written whole first: a crash never leaves half a file
        Files.writeString(tmp, GSON.toJson(s), StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * A new download of a map's site: the news since the last one, numbered and kept (at most keep). The first download of a map only
     * remembers its facts, so a new server, a new map or a lost news.json does not tell the whole diplomacy as news.
     */
    public synchronized List<News> observe(String map, TerritoryFile f, Instant now, int keep) {
        Set<String> facts = NewsDiff.facts(f, now);
        List<String> before = s.facts.put(map, new ArrayList<>(facts));
        if (before == null) return List.of();
        List<News> out = new ArrayList<>();
        for (News n : NewsDiff.diff(new TreeSet<>(before), f, now)) {
            News kept = n.withId(++s.lastId, map, now.toEpochMilli());
            s.items.add(kept);
            out.add(kept);
        }
        while (s.items.size() > Math.max(1, keep)) s.items.remove(0);
        return out;
    }

    /** The latest news, newest first. */
    public synchronized List<News> latest(int n) {
        List<News> out = new ArrayList<>();
        for (int i = s.items.size() - 1; i >= 0 && out.size() < n; i--) out.add(s.items.get(i));
        return out;
    }

    /** How many of the kept news came out after the last one this player read. */
    public synchronized int unread(String player) {
        long seen = s.read.getOrDefault(player, 0L);
        return (int) s.items.stream().filter(n -> n.id() > seen).count();
    }

    /** The player has read everything there is now. */
    public synchronized void markRead(String player) { s.read.put(player, s.lastId); }
}
