package com.cumania.war.dynmap;

import com.cumania.war.engine.Markers;
import com.cumania.war.site.SiteData;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.Marker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerIcon;
import org.dynmap.markers.MarkerSet;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The war's map, kept in dynmap the way the site and the mod read it: each city has a marker whose icon's name ends in the colour of the
 * side that holds it (Red = first side, Blue = second, Green = third, Contested, Uncontrolled). The marker of a city is found like the site
 * does it (js/warlive.js): by its id "Name(Country)" or the map's "marker__Name-lp-Country-rp-__Type", by its name, or the nearest one within a few blocks.
 */
public final class DynmapMarkers implements Markers {
    private static final Pattern MAP_ID = Pattern.compile("^marker__(.+?)-lp-(.+?)-rp-_*([A-Za-z]+)$");
    private static final Pattern SUFFIX = Pattern.compile("(Red|Blue|Green|Contested|Uncontrolled)$");
    private static final String[] SIDE_SUFFIX = {"Red", "Blue", "Green"};
    private static final int SNAP_BLOCKS = 8;

    private final DynmapAPI api;
    private final String world;
    private final Consumer<String> warn;
    private final Map<String, Marker> byCity = new HashMap<>();
    private final Set<String> warned = new HashSet<>();

    public DynmapMarkers(DynmapAPI api, String world, Consumer<String> warn) { this.api = api; this.world = world; this.warn = warn; }

    /** Looks every city's marker up again (markers are edited by hand, and the site's data changes). */
    public void rebuild(SiteData site) {
        MarkerAPI markers = api.getMarkerAPI();
        byCity.clear();
        if (markers == null) return;
        Map<String, Marker> byId = new HashMap<>(), byMapId = new HashMap<>(), byName = new HashMap<>();
        java.util.List<Marker> all = new java.util.ArrayList<>();
        for (MarkerSet set : markers.getMarkerSets()) for (Marker m : set.getMarkers()) {
            if (m.getWorld() != null && !m.getWorld().equals(world)) continue;
            all.add(m);
            String id = m.getMarkerID() == null ? "" : m.getMarkerID();
            byId.putIfAbsent(id.toLowerCase(Locale.ROOT), m);
            Matcher mid = MAP_ID.matcher(id);
            if (mid.matches()) byMapId.putIfAbsent(SiteData.key(mid.group(1), mid.group(2)), m);
            byName.putIfAbsent(clean(m.getLabel()).toLowerCase(Locale.ROOT), m);
            byName.putIfAbsent(id.toLowerCase(Locale.ROOT), m);
        }
        for (SiteData.City c : site.cities()) {
            Marker m = byId.get(c.key());
            if (m == null) m = byMapId.get(c.key());
            if (m == null) m = byName.get(c.name().toLowerCase(Locale.ROOT));
            if (m == null) {
                double best = SNAP_BLOCKS * SNAP_BLOCKS + 1;
                for (Marker o : all) {
                    double d = (o.getX() - c.x()) * (o.getX() - c.x()) + (o.getZ() - c.z()) * (o.getZ() - c.z());
                    if (d < best) { best = d; m = o; }
                }
            }
            if (m != null) byCity.put(c.key(), m);
        }
    }

    private static String clean(String raw) { return raw == null ? "" : raw.replaceAll("<[^>]*>", "").replaceAll("§.", "").trim(); }

    private static String iconId(Marker m) { MarkerIcon icon = m.getMarkerIcon(); return icon == null ? "" : icon.getMarkerIconID(); }

    @Override public int read(SiteData.War war, SiteData.City city) {
        Marker m = byCity.get(city.key());
        if (m == null) return NONE;
        Matcher s = SUFFIX.matcher(iconId(m));
        if (!s.find()) return NONE;
        return switch (s.group(1)) {
            case "Contested" -> CONTESTED;
            case "Uncontrolled" -> UNCONTROLLED;
            case "Red" -> war.sides().size() > 0 ? 0 : NONE;
            case "Blue" -> war.sides().size() > 1 ? 1 : NONE;
            default -> war.sides().size() > 2 ? 2 : NONE;
        };
    }

    @Override public boolean write(SiteData.War war, SiteData.City city, int state) {
        Marker m = byCity.get(city.key());
        if (m == null) { warnOnce("No dynmap marker found for " + city.name() + " (" + city.country() + "): its colour cannot be changed."); return false; }
        String suffix = switch (state) {
            case CONTESTED -> "Contested";
            case UNCONTROLLED -> "Uncontrolled";
            case NONE -> "";
            default -> state >= 0 && state < SIDE_SUFFIX.length ? SIDE_SUFFIX[state] : null;
        };
        if (suffix == null) { warnOnce("Side " + state + " has no icon colour (only three sides are supported)."); return false; }
        String base = SUFFIX.matcher(iconId(m)).replaceFirst("");
        MarkerIcon icon = api.getMarkerAPI().getMarkerIcon(base + suffix);
        if (icon == null) { warnOnce("Dynmap has no icon called \"" + base + suffix + "\" (needed for " + city.name() + ")."); return false; }
        m.setMarkerIcon(icon);
        return true;
    }

    private void warnOnce(String message) { if (warned.add(message)) warn.accept(message); }
}
