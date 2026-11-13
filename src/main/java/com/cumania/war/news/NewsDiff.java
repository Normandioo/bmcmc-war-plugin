package com.cumania.war.news;

import com.cumania.war.site.TerritoryFile;

import java.time.Instant;
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

    /** The calendar the news tell their dates in (the server's, "March 3rd, 868 AES"): set from the config, the same for every map. */
    private static volatile WorldClock clock = WorldClock.DEFAULT;

    public static void setClock(WorldClock c) { clock = c == null ? WorldClock.DEFAULT : c; }

    public static WorldClock clock() { return clock; }

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
                    List<String> d = new ArrayList<>();
                    d.add(tell(k, "Another banner joins the ranks: " + p[1] + " has entered the " + p[0] + "." + membersNow(a),
                            p[1] + " has thrown in its lot with the " + p[0] + "." + membersNow(a),
                            "The " + p[0] + " grows stronger: " + p[1] + " has sworn to stand with its members." + membersNow(a)));
                    if (note != null) d.add(note);
                    out.add(news(News.Type.ALLIANCE_JOIN, p[1] + " joins the " + p[0], d, a == null ? null : a.notes));
                }
                case "truce" -> out.add(news(News.Type.TRUCE, "Truce between " + p[0] + " and " + p[1],
                        List.of(tell(k, "The fighting pauses. " + p[0] + " and " + p[1] + " have agreed to a truce: neither may strike the other" + untilText(p[2]) + ".",
                                "Swords are sheathed, for now. " + p[0] + " and " + p[1] + " have laid down their arms" + untilText(p[2]) + ".",
                                "A fragile silence falls between " + p[0] + " and " + p[1] + ": a truce forbids either from attacking the other" + untilText(p[2]) + "."), until(p[2])), null));
                case "nap" -> out.add(news(News.Type.NAP, "Non-aggression pact between " + p[0] + " and " + p[1],
                        List.of(tell(k, p[0] + " and " + p[1] + " have shaken hands and sworn not to raise arms against each other" + untilText(p[2]) + ".",
                                "A pledge of peace: " + p[0] + " and " + p[1] + " have signed a non-aggression pact" + untilText(p[2]) + ". Neither may attack the other.",
                                "Ink dries on a new treaty. " + p[0] + " and " + p[1] + " have promised that no army of theirs will march on the other" + untilText(p[2]) + "."), until(p[2])), null));
                case "ultimatum" -> out.add(news(News.Type.ULTIMATUM, p[0] + " sends an ultimatum to " + p[1],
                        List.of(tell(k, p[0] + " has sent an ultimatum to " + p[1] + onText(p[2]) + ". The answer now lies with " + p[1] + ".",
                                "A formal demand: " + p[0] + " delivered an ultimatum to " + p[1] + onText(p[2]) + ", which must now decide how to respond.",
                                p[0] + " has issued an ultimatum to " + p[1] + onText(p[2]) + ". " + p[1] + " is expected to reply.")), null));
                case "vassal" -> out.add(news(News.Type.VASSAL, p[1] + " becomes a subject of " + p[0],
                        List.of(tell(k, p[1] + " bows before " + p[0] + ". From now on, it marches under " + p[0] + "'s banner in every war.",
                                p[1] + " has sworn fealty to " + p[0] + ": in wars, it now fights at its overlord's side.",
                                "A crown kneels. " + p[1] + " is now a subject of " + p[0] + " and will follow it into battle.")), null));
                default -> { }
            }
        }
        for (String k : before) {
            if (after.contains(k)) continue;
            String kind = k.substring(0, k.indexOf(':')), rest = k.substring(k.indexOf(':') + 1);
            String[] p = rest.split("\\|", -1);
            switch (kind) {
                case "alliance" -> out.add(news(News.Type.ALLIANCE_END, "The " + rest + " is dissolved",
                        List.of(tell(k, "The " + rest + " is no more. Its members, once bound by oath, now go their own ways.",
                                "Broken seals and torn treaties: the " + rest + " has been dissolved, and its former members stand alone once more.",
                                "An era ends. The " + rest + " has been dissolved, and the oaths that bound its members are void.")), null));
                case "member" -> { if (after.contains("alliance:" + p[0])) out.add(news(News.Type.ALLIANCE_LEAVE, p[1] + " leaves the " + p[0],
                        List.of(tell(k, "Cracks in the pact: " + p[1] + " has walked away from the " + p[0] + "." + membersNow(alliance(f, p[0])),
                                p[1] + " has turned its back on the " + p[0] + "." + membersNow(alliance(f, p[0])),
                                "The " + p[0] + " loses a member: " + p[1] + " has left it." + membersNow(alliance(f, p[0])))), null)); }
                case "vassal" -> out.add(news(News.Type.INDEPENDENCE, p[1] + " is no longer a subject of " + p[0],
                        List.of(tell(k, p[1] + " has broken free! It is no longer a subject of " + p[0] + " and will no longer fight at its side.",
                                "Chains are cast off: " + p[1] + " has left the rule of " + p[0] + ".",
                                p[1] + " stands on its own feet again, no longer a subject of " + p[0] + ".")), null));
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
        boolean type = war.type != null && !war.type.isBlank();
        d.add(sides.size() >= 2
                ? tell(war.id, "The drums of war are beating. " + members(sides.get(0)) + (count(sides.get(0)) > 1 ? " have" : " has") + " declared war on " + members(sides.get(1)) + ", and the " + name(war) + " has begun.",
                        "Steel is drawn and banners raised: " + members(sides.get(0)) + (count(sides.get(0)) > 1 ? " march" : " marches") + " against " + members(sides.get(1)) + ". The " + name(war) + " has begun.",
                        "Diplomacy has failed. " + members(sides.get(0)) + (count(sides.get(0)) > 1 ? " have" : " has") + " declared war on " + members(sides.get(1)) + ", and the " + name(war) + " now begins.")
                        + (type ? " It is a " + war.type + "." : "")
                : "The " + name(war) + " has begun." + (type ? " It is a " + war.type + "." : ""));
        for (TerritoryFile.Side s : sides) d.add((s.name == null ? "Side" : s.name) + ": " + members(s));
        if (war.rule != null && !war.rule.isBlank()) d.add(war.rule);
        return news(News.Type.WAR, title, d, war.notes);
    }

    private static News ended(TerritoryFile.WarInfo war) {
        List<TerritoryFile.Side> sides = nn(war.sides);
        List<String> d = new ArrayList<>();
        String span = span(war.started, war.ended);
        String after = span != null ? " after " + span + " of war" : "";
        String who = sides.size() >= 2 ? members(sides.get(0)) + " against " + members(sides.get(1)) : null;
        d.add(tell(war.id, "The guns fall silent. The " + name(war) + " is over" + after + (who == null ? "." : ": " + who + "."),
                "Peace at last. The " + name(war) + (who == null ? "" : ", which set " + who + ",") + " has come to an end" + after + ".",
                "The banners are lowered and the soldiers go home: the " + name(war) + " is over" + after + "." + (who == null ? "" : " It was " + who + ".")));
        for (TerritoryFile.Side s : sides) d.add((s.name == null ? "Side" : s.name) + ": " + members(s));
        if (war.started != null) d.add("From " + date(war.started) + " to " + date(war.ended));
        return news(News.Type.PEACE, "The " + name(war) + " is over", d, war.notes);
    }

    private static News formed(TerritoryFile.AllianceInfo a) {
        List<String> names = nn(a.members).stream().filter(m -> m != null && m.name != null).map(m -> m.name).toList();
        List<String> d = new ArrayList<>();
        String founders = names.isEmpty() ? "Its founders" : list(names);
        d.add(tell(allianceName(a), "A new power rises. " + founders + " have joined hands to found the " + allianceName(a) + ".",
                "Oaths are sworn and seals pressed: " + founders + " have founded the " + allianceName(a) + ".",
                "The map shifts. " + founders + " now stand together as the " + allianceName(a) + "."));
        if (!names.isEmpty()) d.add("Members: " + list(names));
        if (a.formed != null && !a.formed.isBlank()) d.add("Formed " + a.formed);
        return news(News.Type.ALLIANCE, "The " + allianceName(a) + " is formed", d, a.notes);
    }

    /**
     * The opening text of a news item, in the voice of a war-time newspaper. Which wording is picked follows from the seed (the war's id,
     * the alliance's name, the fact's key), so the same news item always reads the same.
     */
    static String tell(String seed, String... versions) { return versions[Math.floorMod(seed.hashCode(), versions.length)]; }

    private static String untilText(String iso) { return isDate(iso) ? " until " + date(iso) : ""; }
    private static String onText(String iso) { return isDate(iso) ? " on " + date(iso) : ""; }

    /** " It now has 3 members: A, B and C." (or nothing when the alliance is not in the file). */
    private static String membersNow(TerritoryFile.AllianceInfo a) {
        if (a == null) return "";
        List<String> names = nn(a.members).stream().filter(m -> m != null && m.name != null).map(m -> m.name).toList();
        return names.isEmpty() ? "" : " It now has " + names.size() + (names.size() == 1 ? " member: " : " members: ") + list(names) + ".";
    }

    private static int count(TerritoryFile.Side s) { return (int) nn(s.members).stream().filter(m -> m != null && m.name != null).count(); }

    /** How long from one ISO time to another, in the world's units ("11 years"); null when either is missing or not ISO, or it is under a world day. */
    private static String span(String from, String to) {
        try { return WorldClock.span(java.time.Duration.between(Instant.parse(from), Instant.parse(to))); } catch (RuntimeException e) { return null; }
    }

    private static boolean isDate(String iso) {
        try { Instant.parse(iso); return true; } catch (RuntimeException e) { return false; }
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

    /** The server's date for an ISO time ("December 30th, 904 AES"), or the text as it is when it is not an ISO time ("790 AES"). */
    static String date(String iso) {
        try { return clock.format(Instant.parse(iso)); } catch (RuntimeException e) { return iso; }
    }

    private static boolean past(String iso, Instant now) {
        if (iso == null || iso.isBlank()) return false;
        try { return Instant.parse(iso).isBefore(now); } catch (RuntimeException e) { return false; }
    }

    private static <T> List<T> nn(List<T> l) { return l == null ? List.of() : l; }
}
