package com.cumania.war.news;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The flags of the countries and alliances as characters of the resource pack's flags font, to put one before each name in a text. The names and
 * their characters come from flags.json, which tools/build_pack.py writes together with the pack. No Bukkit classes.
 */
public final class Flags {
    /** One piece of a text: a stretch of text, or the character of a flag (flag = true). */
    public record Part(String text, boolean flag) {}

    private final Map<String, Character> chars = new HashMap<>();
    private final Pattern names;   // null when there are no flags

    /** name -> the flag's character code. */
    public Flags(Map<String, Integer> codes) {
        for (var e : codes.entrySet()) chars.put(e.getKey(), (char) (int) e.getValue());
        List<String> sorted = new ArrayList<>(chars.keySet());
        sorted.sort(Comparator.comparingInt(String::length).reversed());   // "Platine League" before "Platine"
        names = sorted.isEmpty() ? null : Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + String.join("|", sorted.stream().map(Pattern::quote).toList()) + ")(?![\\p{L}\\p{N}])");
    }

    /** The flags in the plugin's flags.json; none when the file is missing or unreadable. */
    public static Flags load() {
        try (var in = Flags.class.getResourceAsStream("/flags.json")) {
            if (in == null) return new Flags(Map.of());
            Map<String, Integer> codes = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<Map<String, Integer>>() {}.getType());
            return new Flags(codes == null ? Map.of() : codes);
        } catch (Exception e) {
            return new Flags(Map.of());
        }
    }

    /** The text in pieces: before every name a country or alliance has a flag for, that flag's character; the name stays in the text that follows it. */
    public List<Part> split(String text) {
        List<Part> out = new ArrayList<>();
        if (names == null) { if (!text.isEmpty()) out.add(new Part(text, false)); return out; }
        StringBuilder current = new StringBuilder();
        Matcher m = names.matcher(text);
        int last = 0;
        while (m.find()) {
            current.append(text, last, m.start());
            if (current.length() > 0) { out.add(new Part(current.toString(), false)); current.setLength(0); }
            out.add(new Part(String.valueOf(chars.get(m.group())), true));
            current.append(m.group());
            last = m.end();
        }
        current.append(text, last, text.length());
        if (current.length() > 0) out.add(new Part(current.toString(), false));
        return out;
    }
}
