package com.cumania.war.dynmap;

import com.cumania.war.engine.Markers;
import com.cumania.war.site.SiteData;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.Marker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerIcon;
import org.dynmap.markers.MarkerSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Draws a war map on dynmap from the site's data, for a world whose dynmap has nothing drawn by hand (an event map):
 *   - "War territories": each nation's land on its war's Strategic Map, in the nation's colour;
 *   - "Capture limits": each city's limit, filled with the colour of the side that holds it (yellow while contested);
 *   - "Cities": a marker per city with the site's icons (SmallRed, LocalityBlue...), which the plugin then recolours as cities change hands.
 * The territories and limits are drawn again whenever the site's data changes and are not saved by dynmap; the city markers are
 * saved (their icon is who holds the city) and only created when a city has none yet.
 */
public final class DynmapLayers {
    private static final String TERRITORIES = "bmcwar.territories", LIMITS = "bmcwar.limits", CITIES = "bmcwar.cities";
    private static final String[] SIDE_SUFFIX = {"Red", "Blue", "Green"};
    private static final int[] SIDE_RGB = {0xD83A3A, 0x3A6FD8, 0x3AAE5A};
    private static final int CONTESTED_RGB = 0xF2C624, WHITE = 0xFFFFFF;

    private final DynmapAPI api;
    private final String world;
    private final Consumer<String> warn;
    private final Set<String> warned = new HashSet<>();
    private String builtFor;   // the site data last drawn
    private final Map<String, List<AreaMarker>> limitsByCity = new HashMap<>();

    public DynmapLayers(DynmapAPI api, String world, Consumer<String> warn) { this.api = api; this.world = world; this.warn = warn; }

    private MarkerSet set(MarkerAPI m, String id, String label, boolean persistent, int priority) {
        MarkerSet s = m.getMarkerSet(id);
        if (s == null) s = m.createMarkerSet(id, label, null, persistent);
        if (s == null) return null;
        s.setMarkerSetLabel(label);
        s.setLayerPriority(priority);
        return s;
    }

    private static int sideRgb(SiteData.War war, int side) {
        if (side < 0 || side >= war.sides().size()) return 0x888888;
        Integer rgb = war.sides().get(side).rgb();
        return rgb != null ? rgb : SIDE_RGB[side % SIDE_RGB.length];
    }

    private static double[][] split(int[] ring) {
        int n = ring.length / 2;
        double[] x = new double[n], z = new double[n];
        for (int i = 0; i < n; i++) { x[i] = ring[2 * i]; z[i] = ring[2 * i + 1]; }
        return new double[][]{x, z};
    }

    private static String safe(String s) { return s.replaceAll("[^A-Za-z0-9_()-]", "_"); }

    /**
     * Draws the layers for these wars, unless they were drawn for the same data already. hasMarker tells whether a city already has a
     * marker somewhere in this world's dynmap (then none is added). True when the city markers changed (look them up again).
     */
    public boolean build(SiteData site, List<SiteData.War> wars, Predicate<SiteData.City> hasMarker) {
        MarkerAPI m = api.getMarkerAPI();
        if (m == null || site == null) return false;
        String key = site.generated() + "|" + String.join(",", wars.stream().map(SiteData.War::id).toList());
        boolean again = !key.equals(builtFor);
        builtFor = key;

        if (again) {
            // the nations' land
            MarkerSet terr = set(m, TERRITORIES, "War territories", false, 1);
            if (terr != null) {
                for (AreaMarker a : new ArrayList<>(terr.getAreaMarkers())) if (world.equals(a.getWorld())) a.deleteMarker();
                for (SiteData.War war : wars) for (SiteData.Area area : site.territories(war)) {
                    int side = site.sideOfNation(war, area.country());
                    Integer rgb = site.countryColor(area.country());
                    int colour = rgb != null ? rgb : sideRgb(war, side);
                    int i = 0;
                    for (int[] ring : area.rings()) {
                        double[][] xz = split(ring);
                        AreaMarker a = terr.createAreaMarker("t_" + safe(world) + "_" + safe(war.id()) + "_" + safe(area.country()) + "_" + i++, area.country(), false, world, xz[0], xz[1], false);
                        if (a == null) continue;
                        a.setFillStyle(0.35, colour);
                        a.setLineStyle(2, 0.9, colour);
                    }
                }
            }
            // the capture limits (their fill follows who holds the city: refresh())
            MarkerSet lim = set(m, LIMITS, "Capture limits", false, 3);
            limitsByCity.clear();
            if (lim != null) {
                for (AreaMarker a : new ArrayList<>(lim.getAreaMarkers())) if (world.equals(a.getWorld())) a.deleteMarker();
                for (SiteData.City c : site.cities()) {
                    List<AreaMarker> mine = new ArrayList<>();
                    int i = 0;
                    for (int[] ring : c.area().rings()) {
                        double[][] xz = split(ring);
                        AreaMarker a = lim.createAreaMarker("z_" + safe(world) + "_" + safe(c.key()) + "_" + i++, c.name() + " (" + c.country() + ")", false, world, xz[0], xz[1], false);
                        if (a == null) continue;
                        a.setLineStyle(1, 0.6, WHITE);
                        a.setFillStyle(0.0, WHITE);
                        mine.add(a);
                    }
                    limitsByCity.put(c.key(), mine);
                }
            }
        }

        // the cities that have no marker yet get one, in the colour of the side that owns them
        boolean added = false;
        MarkerSet cities = set(m, CITIES, "Cities", true, 5);
        if (cities != null) for (SiteData.City c : site.cities()) {
            if (hasMarker.test(c)) continue;
            String id = c.name() + "(" + c.country() + ")";
            if (cities.findMarker(id) != null) continue;
            int side = -1;
            for (SiteData.War war : wars) { side = site.sideOfCity(war, c); if (side >= 0) break; }
            String base = c.kind() == null ? "Locality" : c.kind();
            MarkerIcon icon = m.getMarkerIcon(base + (side >= 0 && side < SIDE_SUFFIX.length ? SIDE_SUFFIX[side] : "Uncontrolled"));
            if (icon == null) icon = m.getMarkerIcon(base);
            if (icon == null) { warnOnce("Dynmap has no icon \"" + base + "Red/Blue/...\" for " + c.name() + ": its marker uses the default icon and cannot change colour."); icon = m.getMarkerIcon("default"); }
            Marker made = cities.createMarker(id, c.name(), world, c.x() + 0.5, 64, c.z() + 0.5, icon, true);
            if (made != null) added = true;
        }
        return added;
    }

    /** Fills each city's capture limit with the colour of the side holding it (a little stronger when taken from its owner, yellow while contested). */
    public void refresh(SiteData site, SiteData.War war, Markers markers) {
        for (SiteData.City c : site.cities()) {
            List<AreaMarker> mine = limitsByCity.get(c.key());
            if (mine == null || mine.isEmpty()) continue;
            int owner = site.sideOfCity(war, c);
            if (owner < 0) continue;   // not in this war
            int held = markers.read(war, c);
            int holder = held >= 0 ? held : held == Markers.NONE ? owner : held;
            int colour; double opacity;
            if (holder == Markers.CONTESTED) { colour = CONTESTED_RGB; opacity = 0.5; }
            else if (holder == Markers.UNCONTROLLED) { colour = 0x888888; opacity = 0.3; }
            else { colour = sideRgb(war, holder); opacity = holder == owner ? 0.15 : 0.45; }
            for (AreaMarker a : mine) if (a.getFillColor() != colour || Math.abs(a.getFillOpacity() - opacity) > 1e-6) a.setFillStyle(opacity, colour);
        }
    }

    /** Takes the drawn layers off (the saved city markers stay: they hold who holds each city). */
    public void clear() {
        MarkerAPI m = api.getMarkerAPI();
        if (m == null) return;
        for (String id : List.of(TERRITORIES, LIMITS)) {
            MarkerSet s = m.getMarkerSet(id);
            if (s != null) for (AreaMarker a : new ArrayList<>(s.getAreaMarkers())) if (world.equals(a.getWorld())) a.deleteMarker();
        }
        limitsByCity.clear();
        builtFor = null;
    }

    private void warnOnce(String message) { if (warned.add(message)) warn.accept(message); }
}
