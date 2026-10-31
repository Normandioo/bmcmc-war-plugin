package com.cumania.war.site;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the plugin needs from the site's territories.json: the cities with their land, the wars with their sides, and which
 * side each player is on (in-game name -> the site's name for them -> their country -> that country's side of the war).
 * No Minecraft classes in here.
 */
public final class SiteData {
    /** Where a city's land comes from: its own capture limit (the site's capture SVG), the province its marker stands in, or a round area. */
    public enum Limit { ZONE, PROVINCE, CIRCLE }

    /** key = "name(country)" in lower case, the same key the site and the mod use. area = the city's land (see {@link Limit}). */
    /** How long a locality takes to Capture (7.8.5-6): a Settlement 30 s, a Fort that is not a Settlement 15 s, anything else at once. */
    public enum Category {
        SETTLEMENT("Settlement"), FORT("Fort"), LOCALITY("Locality");
        public final String label;
        Category(String label) { this.label = label; }
    }

    public record City(String key, String name, String country, String kind, int x, int z, Polygon area, Limit limit, List<String> designations) {
        public City(String key, String name, String country, String kind, int x, int z, Polygon area, Limit limit) {
            this(key, name, country, kind, x, z, area, limit, List.of());
        }
        /** True when the land was drawn on the site (a capture limit or a province), false for the round fallback. */
        public boolean ownOutline() { return limit != Limit.CIRCLE; }
        public boolean capital() { return kind != null && kind.endsWith("Capital"); }
        /** Small, Medium, Large, Locality, Fort, Colonial (a "...Capital" kind counts as its base). */
        public String baseKind() { return kind == null ? "Locality" : capital() ? kind.substring(0, kind.length() - "Capital".length()) : kind; }
        /** A Settlement is Small, Medium, Large or Colonial (5.8.4). */
        public boolean settlement() { return SETTLEMENTS.contains(baseKind()); }
        /** A Fort: drawn as one, designated one on the site (5.5.3, 5.5.6), or a Capital that is not a Settlement (5.6.7). Every Settlement is one too (5.8.15). */
        public boolean fort() {
            return settlement() || "Fort".equals(baseKind()) || capital() || designations.stream().anyMatch(d -> d.equalsIgnoreCase("Fort"));
        }
        public boolean harbor() { return designations.stream().anyMatch(d -> d.equalsIgnoreCase("Harbor")); }
        public Category category() { return settlement() ? Category.SETTLEMENT : fort() ? Category.FORT : Category.LOCALITY; }
    }

    private static final java.util.Set<String> SETTLEMENTS = java.util.Set.of("Small", "Medium", "Large", "Colonial");

    /**
     * sides: the war's sides in the site's order (the first is shown red, the second blue, the third green).
     * theater: what a Restricted War is fought over (localities "Name(Country)" or nations), empty when Unrestricted (7.1.2-7.1.4).
     * shortCircuits: Staff's Connections across neutral land, as city keys; autoShortCircuits: whether the others are worked out (7.7.3-4).
     * supplyCapitals: city keys of the Server's Supply Capitals (7.8.11); bridges: link key -> open, where this war changed one (7.7.10-14).
     */
    public record War(String id, String name, boolean live, boolean ended, List<Side> sides, List<String> theater,
                      List<List<String>> shortCircuits, boolean autoShortCircuits, java.util.Set<String> supplyCapitals, Map<String, Boolean> bridges) {
        public War(String id, String name, boolean live, boolean ended, List<Side> sides) {
            this(id, name, live, ended, sides, List.of(), List.of(), true, java.util.Set.of(), Map.of());
        }
    }

    /** The Connections the site reads from its map: locality and link keys, as lower-case "name(country)". */
    public record Network(Map<String, Boolean> linkOpen, List<String[]> edges, List<String[]> warzones) {
        public boolean isLink(String key) { return linkOpen.containsKey(key); }
    }
    public record Side(String name, Integer rgb, List<String> names) {}   // names = lower-case countries and member names of the side

    private final List<City> cities;
    private final List<War> wars;
    private final Map<String, String> siteNameByIgn;
    private final Map<String, String> countryBySite;
    private final Map<String, String> countryByTag;   // simple(any name of a country) -> its key in the file
    private final Map<String, String> overlordOf;     // country key -> its overlord's key (a vassal belongs to its overlord's side)
    private final Network network;                    // null when the site sends no Connections
    private final Map<String, City> cityByKey = new HashMap<>();
    private final String generated;

    private SiteData(List<City> cities, List<War> wars, Map<String, String> siteNameByIgn, Map<String, String> countryBySite,
                     Map<String, String> countryByTag, Map<String, String> overlordOf, Network network, String generated) {
        this.cities = cities; this.wars = wars; this.siteNameByIgn = siteNameByIgn; this.countryBySite = countryBySite;
        this.countryByTag = countryByTag; this.overlordOf = overlordOf; this.network = network; this.generated = generated;
        for (City c : cities) cityByKey.putIfAbsent(c.key(), c);
    }

    /** The Connections from the site, or null when it sends none (then In Play cannot be worked out). */
    public Network network() { return network; }

    /** The city with this key ("name(country)", any case), or null. */
    public City city(String key) { return key == null ? null : cityByKey.get(key.toLowerCase(Locale.ROOT)); }

    /** The country key a country's name or key resolves to, or null. */
    public String countryKey(String name) { return countryOfTag(name); }

    /** The country's overlord's key, or null. */
    public String overlordOf(String country) {
        String k = countryOfTag(country);
        return k == null ? null : overlordOf.get(k);
    }

    /**
     * The cities a war is fought over (7.1.2-7.1.4): a Restricted War's "theater" entries ("Name(Country)" localities, nations,
     * "@Nation" = with its subjects), or null when the war is Unrestricted (then: every city of its Belligerents).
     * unknown collects the entries that name nothing the plugin knows (a Hinterland's name, for instance).
     */
    public java.util.Set<String> theaterOf(War war, List<String> unknown) {
        if (war.theater().isEmpty()) return null;
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String raw : war.theater()) {
            String t = raw.trim();
            City c = city(t);
            if (c != null) { out.add(c.key()); continue; }
            boolean subjects = t.startsWith("@");
            String nation = countryOfTag(subjects ? t.substring(1) : t);
            if (nation == null) { if (unknown != null) unknown.add(t); continue; }
            for (City x : cities) {
                String k = countryOfTag(x.country());
                if (nation.equals(k)) { out.add(x.key()); continue; }
                if (!subjects) continue;
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (String o = k == null ? null : overlordOf.get(k); o != null && seen.add(o); o = overlordOf.get(o)) {
                    if (o.equals(nation)) { out.add(x.key()); break; }
                }
            }
        }
        return out;
    }

    /** A name the way tags and the site are compared: lower case, no accents, letters and digits only ("§c[Santana] " -> "santana"). */
    public static String simple(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s.replaceAll("§.", ""), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** The country (its key in the site's file) a tag names, by the country's key, official name or display name; null when none. */
    public String countryOfTag(String tag) {
        String s = simple(tag);
        return s.isEmpty() ? null : countryByTag.get(s);
    }

    public List<City> cities() { return cities; }
    public List<War> wars() { return wars; }
    public String generated() { return generated; }

    public static String key(String name, String country) { return (name + "(" + country + ")").toLowerCase(Locale.ROOT); }

    private static final java.util.regex.Pattern WAR_COLOUR = java.util.regex.Pattern.compile("(Red|Blue|Green|Contested|Uncontrolled)$");

    /**
     * The city's type without a war colour: the site's marker ids sometimes carry the dynmap colour ("SmallRed", "LargeCapitalRed",
     * "FortBlue"), which would hide the type ("SmallRed" is not "Small") and the capital. The colour belongs on the dynmap icon only.
     */
    public static String plainKind(String kind) { return kind == null ? null : WAR_COLOUR.matcher(kind).replaceFirst(""); }

    public static SiteData parse(String json, int fallbackRadius) {
        TerritoryFile file = TerritoryFile.parse(json);
        List<TerritoryFile.Province> provinces = file.provinces == null ? List.of() : file.provinces;
        List<Polygon> outlines = new ArrayList<>();
        for (TerritoryFile.Province p : provinces) outlines.add(new Polygon(p.rings == null ? List.of() : p.rings));

        Map<String, Polygon> zones = new HashMap<>();   // capture limits drawn for the city itself
        if (file.zones != null) for (TerritoryFile.Zone z : file.zones) {
            if (z == null || z.name == null || z.country == null || z.rings == null || z.rings.isEmpty()) continue;
            zones.put(key(z.name, z.country), new Polygon(z.rings));
        }

        List<City> cities = new ArrayList<>();
        if (file.localities != null) for (TerritoryFile.Locality l : file.localities) {
            String k = key(l.name, l.country);
            String kind = plainKind(l.kind);
            List<String> des = l.designations == null ? List.of() : List.copyOf(l.designations);
            Polygon zone = zones.get(k);
            if (zone != null) { cities.add(new City(k, l.name, l.country, kind, l.x, l.z, zone, Limit.ZONE, des)); continue; }
            Polygon own = null;
            for (Polygon p : outlines) if (p.contains(l.x, l.z)) { own = p; break; }   // otherwise the province the city stands in is its land
            cities.add(new City(k, l.name, l.country, kind, l.x, l.z, own != null ? own : Polygon.circle(l.x, l.z, fallbackRadius), own != null ? Limit.PROVINCE : Limit.CIRCLE, des));
        }

        Network network = null;
        if (file.network != null && file.network.edges != null && !file.network.edges.isEmpty()) {
            Map<String, Boolean> linkOpen = new HashMap<>();
            if (file.network.links != null) for (Map.Entry<String, TerritoryFile.Link> e : file.network.links.entrySet()) {
                if (e.getValue() != null) linkOpen.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().open);
            }
            List<String[]> edges = new ArrayList<>(), warzones = new ArrayList<>();
            for (List<Object> e : file.network.edges) {
                if (e == null || e.size() < 2 || e.get(0) == null || e.get(1) == null) continue;
                String a = e.get(0).toString().toLowerCase(Locale.ROOT), b = e.get(1).toString().toLowerCase(Locale.ROOT);
                edges.add(new String[]{a, b});
                if (e.size() > 2 && e.get(2) instanceof Number n && n.intValue() == 1) warzones.add(new String[]{a, b});
            }
            network = new Network(Map.copyOf(linkOpen), List.copyOf(edges), List.copyOf(warzones));
        }

        List<War> wars = new ArrayList<>();
        if (file.war != null && file.war.wars != null) for (TerritoryFile.WarInfo w : file.war.wars) {
            List<Side> sides = new ArrayList<>();
            if (w.sides != null) for (TerritoryFile.Side s : w.sides) {
                List<String> names = new ArrayList<>();
                if (s.members != null) for (TerritoryFile.Member m : s.members) {
                    if (m.name != null) names.add(m.name.toLowerCase(Locale.ROOT));
                    if (m.countries != null) for (String c : m.countries) names.add(c.toLowerCase(Locale.ROOT));
                }
                sides.add(new Side(s.name, parseColor(s.color), List.copyOf(names)));
            }
            List<String> theater = new ArrayList<>();
            if (w.theater != null) for (String t : w.theater.split(",")) if (!t.isBlank()) theater.add(t.trim());
            List<List<String>> shorts = new ArrayList<>();
            if (w.shortCircuits != null) for (List<String> p : w.shortCircuits) {
                if (p != null && p.size() == 2 && p.get(0) != null && p.get(1) != null) shorts.add(List.of(p.get(0).toLowerCase(Locale.ROOT), p.get(1).toLowerCase(Locale.ROOT)));
            }
            java.util.Set<String> supply = new java.util.HashSet<>();
            if (w.supplyCapitals != null) for (List<String> l : w.supplyCapitals.values()) if (l != null) for (String s : l) if (s != null) supply.add(s.toLowerCase(Locale.ROOT));
            Map<String, Boolean> bridges = new HashMap<>();
            if (w.bridges != null) for (Map.Entry<String, String> e : w.bridges.entrySet()) {
                if (e.getValue() != null) bridges.put(e.getKey().toLowerCase(Locale.ROOT), !simple(e.getValue()).equals("closed"));
            }
            wars.add(new War(w.id, w.name, w.live, isPast(w.ended), List.copyOf(sides), List.copyOf(theater), List.copyOf(shorts),
                    w.shortCircuit == null || w.shortCircuit, java.util.Set.copyOf(supply), Map.copyOf(bridges)));
        }

        Map<String, String> byIgn = new HashMap<>();
        if (file.players != null) for (Map.Entry<String, TerritoryFile.Player> e : file.players.entrySet()) {
            String ign = e.getValue() == null ? null : e.getValue().ign;
            if (ign != null && !ign.isBlank()) byIgn.put(ign.trim().toLowerCase(Locale.ROOT), e.getKey());
        }
        Map<String, String> countryBySite = new HashMap<>();
        if (file.countries != null) for (Map.Entry<String, TerritoryFile.Country> e : file.countries.entrySet()) {
            if (e.getValue() == null || e.getValue().population == null) continue;
            for (String person : e.getValue().population) if (person != null) countryBySite.putIfAbsent(person.trim().toLowerCase(Locale.ROOT), e.getKey());
        }
        Map<String, String> byTag = new HashMap<>();
        if (file.countries != null) for (Map.Entry<String, TerritoryFile.Country> e : file.countries.entrySet()) {   // the key first: it wins over a look-alike name
            byTag.putIfAbsent(simple(e.getKey()), e.getKey());
        }
        if (file.countries != null) for (Map.Entry<String, TerritoryFile.Country> e : file.countries.entrySet()) {
            TerritoryFile.Country c = e.getValue();
            if (c == null) continue;
            if (c.name != null) byTag.putIfAbsent(simple(c.name), e.getKey());
            if (c.info != null) for (String k : List.of("displayName", "officialName")) if (c.info.get(k) != null) byTag.putIfAbsent(simple(c.info.get(k)), e.getKey());
        }
        byTag.remove("");
        Map<String, String> overlords = new HashMap<>();
        if (file.countries != null) for (Map.Entry<String, TerritoryFile.Country> e : file.countries.entrySet()) {
            TerritoryFile.Country c = e.getValue();
            if (c == null || c.diplomacy == null || c.diplomacy.overlord == null || c.diplomacy.overlord.isEmpty()) continue;
            String over = byTag.get(simple(c.diplomacy.overlord.get(0)));   // the site writes the overlord's display name
            if (over != null && !over.equals(e.getKey())) overlords.put(e.getKey(), over);
        }
        return new SiteData(List.copyOf(cities), List.copyOf(wars), byIgn, countryBySite, byTag, overlords, network, file.generated == null ? "" : file.generated);
    }

    private static boolean isPast(String iso) {
        if (iso == null || iso.isBlank()) return false;
        try { return java.time.Instant.parse(iso).isBefore(java.time.Instant.now()); } catch (RuntimeException e) { return false; }
    }

    private static Integer parseColor(String hex) {
        if (hex == null) return null;
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        try { return h.length() == 6 ? Integer.parseInt(h, 16) : null; } catch (NumberFormatException e) { return null; }
    }

    public War war(String id) {
        for (War w : wars) if (w.id() != null && w.id().equalsIgnoreCase(id)) return w;
        return null;
    }

    /** The side of the war a country (or a member's name, like "Eleyetia") is listed on, or -1. Names are compared {@link #simple}. */
    public static int sideOfCountry(War war, String country) {
        String c = simple(country);
        if (c.isEmpty()) return -1;
        for (int i = 0; i < war.sides().size(); i++) for (String n : war.sides().get(i).names()) if (simple(n).equals(c)) return i;
        return -1;
    }

    /**
     * The side a country fights on: its own, or else its overlord's, and so on up (a vassal like Santana fights for Greneshire
     * even when the war lists only Greneshire). -1 when neither it nor any overlord is in the war.
     */
    public int sideOfNation(War war, String country) {
        String c = country;
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (c != null && seen.add(c)) {
            int side = sideOfCountry(war, c);
            if (side >= 0) return side;
            String key = countryByTag.getOrDefault(simple(c), c);
            c = overlordOf.get(key);
        }
        return -1;
    }

    public int sideOfCity(War war, City city) { return sideOfNation(war, city.country()); }

    /** The country the site's population lists put this player in (in-game name -> the site's name -> the country), or null. */
    public String countryOfPlayer(String ign) {
        if (ign == null) return null;
        String site = siteNameByIgn.get(ign.trim().toLowerCase(Locale.ROOT));
        return countryBySite.get((site != null ? site : ign).trim().toLowerCase(Locale.ROOT));
    }

    /** Which side this player fights on by the site's population lists. -1 when they are not in the war. */
    public int sideOfPlayer(War war, String ign) { return sideOfNation(war, countryOfPlayer(ign)); }
}
