import com.cumania.war.dynmap.DynmapMarkers;
import com.cumania.war.engine.*;
import com.cumania.war.site.*;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.*;
import java.nio.file.*;
import java.util.*;

/** The dynmap adapter against made-up markers named the way the site's map names them. Uses the stub API in the test setup. */
public class DynmapCheck {
    static int failures = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) failures++; }

    static class Icon implements MarkerIcon { final String id; Icon(String id) { this.id = id; } public String getMarkerIconID() { return id; } }
    static class Mk implements Marker {
        final String id, label, world; final double x, z; MarkerIcon icon;
        Mk(String id, String label, double x, double z, String icon) { this.id = id; this.label = label; this.world = "world"; this.x = x; this.z = z; this.icon = new Icon(icon); }
        public String getMarkerID() { return id; } public String getLabel() { return label; } public String getWorld() { return world; }
        public double getX() { return x; } public double getZ() { return z; } public MarkerIcon getMarkerIcon() { return icon; }
        public boolean setMarkerIcon(MarkerIcon i) { icon = i; return true; }
    }

    public static void main(String[] args) throws Exception {
        SiteData site = SiteData.parse(Files.readString(Path.of(args[0])), 40);
        SiteData.War war = site.war("LombriganWar");
        var a = site.cities().get(0); var b = site.cities().get(1); var c = site.cities().get(2); var d = site.cities().get(3);
        Set<Marker> ms = new LinkedHashSet<>();
        ms.add(new Mk(a.name() + "(" + a.country() + ")", "<b>" + a.name() + "</b>", a.x(), a.z(), "SmallRed"));                         // by id
        ms.add(new Mk("marker__" + b.name() + "-lp-" + b.country() + "-rp-__Small", "x", b.x() + 500, b.z(), "SmallBlue"));            // by the map's own id
        ms.add(new Mk("whatever1", c.name(), c.x() + 500, c.z() + 500, "LocalityContested"));                                           // by name
        ms.add(new Mk("whatever2", "other", d.x() + 3, d.z() - 2, "Fort"));                                                             // nearest within 8 blocks
        Set<String> known = new HashSet<>(List.of("SmallRed", "SmallBlue", "SmallGreen", "Small", "LocalityContested", "Locality", "LocalityRed", "FortRed", "Fort", "FortBlue", "FortContested"));
        MarkerAPI mapi = new MarkerAPI() {
            public Set<MarkerSet> getMarkerSets() { return Set.of((MarkerSet) () -> ms); }
            public MarkerIcon getMarkerIcon(String id) { return known.contains(id) ? new Icon(id) : null; }
        };
        List<String> warnings = new ArrayList<>();
        DynmapMarkers dm = new DynmapMarkers(() -> mapi, "world", warnings::add);
        dm.rebuild(site);
        check(dm.read(war, a) == 0, "by id: SmallRed reads as side 0");
        check(dm.read(war, b) == 1, "by the map's own id: SmallBlue reads as side 1");
        check(dm.read(war, c) == Markers.CONTESTED, "by name: LocalityContested reads as contested");
        check(dm.read(war, d) == Markers.NONE, "nearest marker, no war colour: nothing");
        check(dm.write(war, d, 0) && dm.read(war, d) == 0, "painting it red changes its icon to FortRed (" + ((Mk) ms.toArray()[3]).icon.getMarkerIconID() + ")");
        check(dm.write(war, d, Markers.CONTESTED) && ((Mk) ms.toArray()[3]).icon.getMarkerIconID().equals("FortContested"), "contested -> FortContested");
        check(dm.write(war, d, Markers.NONE) && ((Mk) ms.toArray()[3]).icon.getMarkerIconID().equals("Fort"), "clearing takes the colour off -> Fort");
        check(!dm.write(war, d, 2) && warnings.size() == 1, "an icon dynmap does not have is refused with one warning: " + warnings);
        check(!dm.write(war, site.cities().get(10), 0), "a city with no marker is refused");
        // the engine through the adapter
        Rules rules = new Rules(Map.of("Small", 1200.0, "Locality", 600.0, "Fort", 900.0), 600, 1.5, true, 60, 600, 1);
        CaptureEngine eng = new CaptureEngine(rules, dm);
        var p = List.of(new CaptureEngine.Present("x", 1, d.x(), d.z()));
        double need = rules.secondsFor(d);
        var evs = new ArrayList<CaptureEngine.Event>(); for (int t = 0; t < need + 2; t++) evs.addAll(eng.tick(site, war, p, 1));
        check(evs.stream().anyMatch(e -> e.type() == CaptureEngine.Type.CAPTURED) && ((Mk) ms.toArray()[3]).icon.getMarkerIconID().equals("FortBlue"), "a capture through dynmap leaves FortBlue");
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED"); System.exit(failures == 0 ? 0 : 1);
    }
}
