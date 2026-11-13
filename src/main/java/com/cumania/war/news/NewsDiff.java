package com.cumania.war.news;

import com.cumania.war.site.TerritoryFile;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Works out the diplomatic news from the site's territories.json. The diplomacy is turned into "facts" (a war going on, a member of an
 * alliance, a truce in force, a subject and its overlord...); a fact that appears or disappears between two downloads is news.
 * Only the facts are kept between downloads (and restarts), so the news never depend on how the site writes the rest. No Bukkit classes.
 */
public final class NewsDiff {
    private NewsDiff() {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    /** The facts the news are worked out from. Keys hold names, not only ids, so a fact gone can still be told by name. */
    public static Set<String> facts(TerritoryFile f, Instant now) {
        Set<String> out = new TreeSet<>();
        TerritoryFile.WarData w = f.war;
        if (w != null) {
            for (TerritoryFile.WarInfo war : nn(w.wars)) {
                if (war == null || war.id == null) continue;
                out.add((past(war.ended, now) ? "warend:" : "war:") + war.id);
            }
            for (TerritoryFile.AllianceInfo a : nn(w.alliances)) {
                String name = allianceName(a);
                if (name == null) continue;
                out.add("alliance:" + name);
                for (TerritoryFile.Member m : nn(a.members)) if (m != null && m.name != null) out.add("member:" + name + "|" + m.name);
            }
            for (TerritoryFile.Truce t : nn(w.truces))
                if (t != null && t.a != null && t.b != null && !past(t.until, now) && (t.from == null || past(t.from, now))) out.add("truce:" + t.a + "|" + t.b + "|" + t.until);
            for (TerritoryFile.Nap n : nn(w.naps))
                if (n != null && n.a != null && n.b != null && !past(n.until, now)) out.add("nap:" + n.a + "|" + n.b + "|" + n.until);
            for (TerritoryFile.Ultimatum u : nn(w.ultimatums))
                if (u != null && u.from != null && u.to != null) out.add("ultimatum:" + u.from + "|" + u.to + "|" + u.sent);
        }
        if (f.countries != null) for (Map.Entry<String, TerritoryFile.Country> e : f.countries.entrySet()) {
            TerritoryFile.Country c = e.getValue();
            if (c == null || c.diplomacy == null) continue;
            for (String over : nn(c.diplomacy.overlord)) if (over != null && !over.isBlank()) out.add("vassal:" + over + "|" + countryName(e.getKey(), c));
        }
        return out;
    }

    /**
     * The news between the facts of the last download (before) and this one. A war that ends tells "peace" only when it was seen going on
     * (a war the Staff adds already over is history, not news); the members of an alliance that is dissolved do not each "leave" it.
     */
    public static List<News> diff(Set<String> before, TerritoryFile f, Instant now) {
        Set<String> after = facts(f, now);
        List<News> out = new ArrayList<>();
        for (String k : after) {
            if (before.contains(k)) continue;
            String kind = k.substring(0, k.indexOf(':')), rest = k.substring(k.indexOf(':') + 1);
            String[] p = rest.split("\\|", -1);
            switch (kind) {
                case "war" -> { var war = war(f, rest); if (war != null) out.add(declared(war)); }
                case "warend" -> { var war = war(f, rest); if (war != null && before.contains("war:" + rest)) out.add(ended(war)); }
                case "alliance" -> { var a = alliance(f, rest); if (a != null) out.add(formed(a)); }
                case "member" -> {
                    if (!before.contains("alliance:" + p[0])) continue;   // a new alliance: its members came with it
                    var a = alliance(f, p[0]);
                    String note = a == null ? null : nn(a.members).stream().filter(m -> m != null && p[1].equals(m.name) && m.note != null).map(m -> m.note).findFirst().orElse(null);
                    out.add(news(News.Type.ALLIANCE_JOIN, p[1] + " joins the " + p[0], note == null ? List.of() : List.of(note), a == null ? null : a.notes));
                }
                case "truce" -> out.add(news(News.Type.TRUCE, "Truce between " + p[0] + " and " + p[1], List.of(until(p[2])), null));
                case "nap" -> out.add(news(News.Type.NAP, "Non-aggression pact between " + p[0] + " and " + p[1], List.of(until(p[2])), null));
                case "ultimatum" -> out.add(news(News.Type.ULTIMATUM, p[0] + " sends an ultimatum to " + p[1],
                        "null".equals(p[2]) ? List.of() : List.of("Sent " + date(p[2])), null));
                case "vassal" -> out.add(news(News.Type.VASSAL, p[1] + " becomes a subject of " + p[0], List.of(p[1] + " now fights on " + p[0] + "'s side."), null));
                default -> { }
            }
        }
        for (String k : before) {
            if (after.contains(k)) continue;
            String kind = k.substring(0, k.indexOf(':')), rest = k.substring(k.indexOf(':') + 1);
            String[] p = rest.split("\\|", -1);
            switch (kind) {
                case "alliance" -> out.add(news(News.Type.ALLIANCE_END, "The " + rest + " is dissolved", List.of(), null));
                case "member" -> { if (after.contains("alliance:" + p[0])) out.add(news(News.Type.ALLIANCE_LEAVE, p[1] + " leaves the " + p[0], List.of(), null)); }
                case "vassal" -> out.add(news(News.Type.INDEPENDENCE, p[1] + " is no longer a subject of " + p[0], List.of(), null));
                default -> { }   // a truce or pact that ran out, an ultimatum taken off, a war deleted: not news
            }
        }
        out.sort(java.util.Comparator.comparing(News::type));   // the most important first (wars, then peace, alliances...), as Type lists them
        return out;
    }

    private static News declared(TerritoryFile.WarInfo war) {
        List<TerritoryFile.Side> sides = nn(war.sides);
        String title = sides.size() >= 2 ? members(sides.get(0)) + (sides.get(0).members != null && sides.get(0).members.size() > 1 ? " declare" : " declares")
                + " war on " + members(sides.get(1)) : "The " + name(war) + " begins";
        List<String> d = new ArrayList<>();
        d.add("War: " + name(war) + (war.type == null || war.type.isBlank() ? "" : " (" + war.type + ")"));
        for (TerritoryFile.Side s : sides) d.add((s.name == null ? "Side" : s.name) + ": " + members(s));
        if (war.rule != null && !war.rule.isBlank()) d.add(war.rule);
        return news(News.Type.WAR, title, d, war.notes);
    }

    private static News ended(TerritoryFile.WarInfo war) {
        List<String> d = new ArrayList<>();
        for (TerritoryFile.Side s : nn(war.sides)) d.add((s.name == null ? "Side" : s.name) + ": " + members(s));
        if (war.started != null) d.add("From " + date(war.started) + " to " + date(war.ended));
        return news(News.Type.PEACE, "The " + name(war) + " is over", d, war.notes);
    }

    private static News formed(TerritoryFile.AllianceInfo a) {
        List<String> names = nn(a.members).stream().filter(m -> m != null && m.name != null).map(m -> m.name).toList();
        List<String> d = new ArrayList<>();
        if (!names.isEmpty()) d.add("Members: " + list(names));
        if (a.formed != null && !a.formed.isBlank()) d.add("Formed " + a.formed);
        return news(News.Type.ALLIANCE, "The " + allianceName(a) + " is formed", d, a.notes);
    }

    private static News news(News.Type type, String title, List<String> details, String notes) {
        return new News(0, null, type, title, List.copyOf(details), notes == null || notes.isBlank() ? null : notes.strip(), 0);
    }

    private static TerritoryFile.WarInfo war(TerritoryFile f, String id) {
        if (f.war == null) return null;
        for (TerritoryFile.WarInfo w : nn(f.war.wars)) if (w != null && id.equals(w.id)) return w;
        return null;
    }

    // ----- The Staff's posts: the map's news.json (/news post of the site's Discord bot), next to its territories.json -----

    /** A news the Staff posted; posted = ISO UTC. */
    public static final class Post { public String id, title, text, posted; }

    /** What news.json holds. */
    private static final class PostsFile { List<Post> news; }

    /** The posts in a news.json (oldest first); an empty list when it has none. */
    public static List<Post> parsePosts(String json) throws com.google.gson.JsonSyntaxException {
        PostsFile f = new com.google.gson.Gson().fromJson(json, PostsFile.class);
        if (f == null || f.news == null) return List.of();
        return f.news.stream().filter(p -> p != null && p.id != null && p.title != null && !p.title.isBlank()).toList();
    }

    public static Set<String> postFacts(List<Post> posts) {
        Set<String> out = new TreeSet<>();
        for (Post p : posts) out.add("post:" + p.id);
        return out;
    }

    /** The posts not seen before. A post deleted is not news. */
    public static List<News> posts(Set<String> before, List<Post> posts) {
        List<News> out = new ArrayList<>();
        for (Post p : posts) if (!before.contains("post:" + p.id)) out.add(news(News.Type.POST, p.title.strip(), List.of(), p.text));
        return out;
    }

    private static TerritoryFile.AllianceInfo alliance(TerritoryFile f, String name) {
        if (f.war == null) return null;
        for (TerritoryFile.AllianceInfo a : nn(f.war.alliances)) if (name.equals(allianceName(a))) return a;
        return null;
    }

    private static String allianceName(TerritoryFile.AllianceInfo a) { return a == null ? null : a.name != null ? a.name : a.id; }

    private static String name(TerritoryFile.WarInfo w) { return w.name != null && !w.name.isBlank() ? w.name : w.id; }

    /** The name the site shows for a country (its overlord list uses the display name too). */
    private static String countryName(String key, TerritoryFile.Country c) {
        String shown = c.info == null ? null : c.info.get("displayName");
        return shown != null && !shown.isBlank() ? shown : c.name != null && !c.name.isBlank() ? c.name : key;
    }

    private static String members(TerritoryFile.Side s) {
        List<String> names = nn(s.members).stream().filter(m -> m != null && m.name != null).map(m -> m.name).toList();
        return names.isEmpty() ? (s.name == null ? "?" : s.name) : list(names);
    }

    /** "A", "A and B", "A, B and C". */
    static String list(List<String> names) {
        if (names.size() <= 1) return String.join("", names);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    private static String until(String iso) { return iso == null || iso.equals("null") ? "No end date" : "Until " + date(iso); }

    /** "15 Nov 2026 23:59 UTC", or the text as it is when it is not an ISO time ("790 AES"). */
    static String date(String iso) {
        try { return DATE.format(Instant.parse(iso)); } catch (RuntimeException e) { return iso; }
    }

    private static boolean past(String iso, Instant now) {
        if (iso == null || iso.isBlank()) return false;
        try { return Instant.parse(iso).isBefore(now); } catch (RuntimeException e) { return false; }
    }

    private static <T> List<T> nn(List<T> l) { return l == null ? List.of() : l; }
}
