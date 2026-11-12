import com.cumania.war.dynmap.DynmapMarkers;
import com.cumania.war.engine.*;
import com.cumania.war.site.*;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

/** The dynmap adapter against made-up markers named the way the site's map names them: java DynmapCheck <territories.json> */
public class DynmapCheck {
    static int failures = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) failures++; }

    interface Handler { Object call(String method, Object[] args); }

    /** A dynmap interface answering only what the handler says (the real ones are too big to write out). */
    @SuppressWarnings("unchecked")
    static <T> T fake(Class<T> type, Handler h) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> type.getSimpleName();
            default -> h.call(method.getName(), args);
        });
    }

    static MarkerIcon icon(String id) { return fake(MarkerIcon.class, (m, a) -> m.equals("getMarkerIconID") ? id : null); }

    static class Mk {
        final String id, label; final double x, z; MarkerIcon icon; final Marker view;
        Mk(String id, String label, double x, double z, String iconId) {
            this.id = id; this.label = label; this.x = x; this.z = z; this.icon = icon(iconId);
            this.view = fake(Marker.class, (m, a) -> switch (m) {
                case "getMarkerID" -> this.id;
                case "getLabel" -> this.label;
                case "getWorld" -> "world";
                case "getX" -> this.x;
                case "getZ" -> this.z;
                case "getMarkerIcon" -> this.icon;
                case "setMarkerIcon" -> { this.icon = (MarkerIcon) a[0]; yield true; }
                default -> null;
            });
        }
        String iconId() { return icon.getMarkerIconID(); }
    }

    public static void main(String[] args) throws Exception {
        SiteData site = SiteData.parse(Files.readString(Path.of(args[0])), 40);
        SiteData.War war = site.war("LombriganWar");
        var a = site.cities().get(0); var b = site.cities().get(1); var c = site.cities().get(2); var d = site.cities().get(3);
        List<Mk> mks = new ArrayList<>();
        mks.add(new Mk(a.name() + "(" + a.country() + ")", "<b>" + a.name() + "</b>", a.x(), a.z(), "SmallRed"));                         // by id
        mks.add(new Mk("marker__" + b.name() + "-lp-" + b.country() + "-rp-__Small", "x", b.x() + 500, b.z(), "SmallBlue"));            // by the map's own id
        mks.add(new Mk("whatever1", c.name(), c.x() + 500, c.z() + 500, "LocalityContested"));                                           // by name
        mks.add(new Mk("whatever2", "other", d.x() + 3, d.z() - 2, "Fort"));                                                             // nearest within 8 blocks
        Set<Marker> ms = new LinkedHashSet<>();
        for (Mk k : mks) ms.add(k.view);
        Set<String> known = new HashSet<>(List.of("SmallRed", "SmallBlue", "SmallGreen", "Small", "LocalityContested", "Locality", "LocalityRed", "FortRed", "Fort", "FortBlue", "FortContested"));
        MarkerSet set = fake(MarkerSet.class, (m, x) -> m.equals("getMarkers") ? ms : null);
        MarkerAPI mapi = fake(MarkerAPI.class, (m, x) -> switch (m) {
            case "getMarkerSets" -> Set.of(set);
            case "getMarkerIcon" -> known.contains((String) x[0]) ? icon((String) x[0]) : null;
            default -> null;
        });
        DynmapAPI api = fake(DynmapAPI.class, (m, x) -> m.equals("getMarkerAPI") ? mapi : null);
        List<String> warnings = new ArrayList<>();
        DynmapMarkers dm = new DynmapMarkers(api, "world", warnings::add);
        dm.rebuild(site);
        check(dm.read(war, a) == 0, "by id: SmallRed reads as side 0");
        check(dm.read(war, b) == 1, "by the map's own id: SmallBlue reads as side 1");
        check(dm.read(war, c) == Markers.CONTESTED, "by name: LocalityContested reads as contested");
        check(dm.read(war, d) == Markers.NONE, "nearest marker, no war colour: nothing");
        check(dm.write(war, d, 0) && dm.read(war, d) == 0, "painting it red changes its icon to FortRed (" + mks.get(3).iconId() + ")");
        check(dm.write(war, d, Markers.CONTESTED) && mks.get(3).iconId().equals("FortContested"), "contested -> FortContested");
        check(dm.write(war, d, Markers.NONE) && mks.get(3).iconId().equals("Fort"), "clearing takes the colour off -> Fort");
        check(!dm.write(war, d, 2) && warnings.size() == 1, "an icon dynmap does not have is refused with one warning: " + warnings);
        check(!dm.write(war, site.cities().get(10), 0), "a city with no marker is refused");
        // the engine through the adapter
        Rules rules = new Rules(Map.of("Small", 1200.0, "Locality", 600.0, "Fort", 900.0), 600, 1.5, true, 60, 600, 1);
        CaptureEngine eng = new CaptureEngine(rules, dm);
        var p = List.of(new CaptureEngine.Present("x", 1, d.x(), d.z()));
        double need = rules.secondsFor(d);
        var evs = new ArrayList<CaptureEngine.Event>(); for (int t = 0; t < need + 2; t++) evs.addAll(eng.tick(site, war, p, 1));
        check(evs.stream().anyMatch(e -> e.type() == CaptureEngine.Type.CAPTURED) && mks.get(3).iconId().equals("FortBlue"), "a capture through dynmap leaves FortBlue");
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
