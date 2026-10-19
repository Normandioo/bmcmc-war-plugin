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
    public record City(String key, String name, String country, String kind, int x, int z, Polygon area, Limit limit) {
        /** True when the land was drawn on the site (a capture limit or a province), false for the round fallback. */
        public boolean ownOutline() { return limit != Limit.CIRCLE; }
        public boolean capital() { return kind != null && kind.endsWith("Capital"); }
        /** Small, Medium, Large, Locality, Fort, Colonial (a "...Capital" kind counts as its base). */
        public String baseKind() { return kind == null ? "Locality" : capital() ? kind.substring(0, kind.length() - "Capital".length()) : kind; }
    }

    /** sides: the war's sides in the site's order (the first is shown red, the second blue, the third green). */
    public record War(String id, String name, boolean live, boolean ended, List<Side> sides) {}
    public record Side(String name, Integer rgb, List<String> names) {}   // names = lower-case countries and member names of the side

    private final List<City> cities;
    private final List<War> wars;
    private final Map<String, String> siteNameByIgn;
    private final Map<String, String> countryBySite;
    private final String generated;

    private SiteData(List<City> cities, List<War> wars, Map<String, String> siteNameByIgn, Map<String, String> countryBySite, String generated) {
        this.cities = cities; this.wars = wars; this.siteNameByIgn = siteNameByIgn; this.countryBySite = countryBySite; this.generated = generated;
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
            Polygon zone = zones.get(k);
            if (zone != null) { cities.add(new City(k, l.name, l.country, kind, l.x, l.z, zone, Limit.ZONE)); continue; }
            Polygon own = null;
            for (Polygon p : outlines) if (p.contains(l.x, l.z)) { own = p; break; }   // otherwise the province the city stands in is its land
            cities.add(new City(k, l.name, l.country, kind, l.x, l.z, own != null ? own : Polygon.circle(l.x, l.z, fallbackRadius), own != null ? Limit.PROVINCE : Limit.CIRCLE));
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
            wars.add(new War(w.id, w.name, w.live, isPast(w.ended), List.copyOf(sides)));
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
        return new SiteData(List.copyOf(cities), List.copyOf(wars), byIgn, countryBySite, file.generated == null ? "" : file.generated);
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

    /** The side of the war a country (or a member's name, like "Eleyetia") belongs to, or -1. */
    public static int sideOfCountry(War war, String country) {
        if (country == null) return -1;
        String c = country.toLowerCase(Locale.ROOT);
        for (int i = 0; i < war.sides().size(); i++) if (war.sides().get(i).names().contains(c)) return i;
        return -1;
    }

    public int sideOfCity(War war, City city) { return sideOfCountry(war, city.country()); }

    /** Which side this player fights on: their in-game name -> the site's name -> the country they belong to -> its side. -1 when they are not in the war. */
    public int sideOfPlayer(War war, String ign) {
        if (ign == null) return -1;
        String site = siteNameByIgn.get(ign.trim().toLowerCase(Locale.ROOT));
        String country = countryBySite.get((site != null ? site : ign).trim().toLowerCase(Locale.ROOT));
        return sideOfCountry(war, country);
    }
}
