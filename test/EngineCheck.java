import com.cumania.war.engine.*;
import com.cumania.war.site.*;
import java.nio.file.*;
import java.util.*;

/** Plays the capture rule against the site's real territories.json: java EngineCheck <territories.json> */
public class EngineCheck {
    static int failures = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) failures++; }

    static final Map<String, Integer> icons = new HashMap<>();
    static final List<String> writes = new ArrayList<>();
    static final Markers MARKERS = new Markers() {
        public int read(SiteData.War w, SiteData.City c) { return icons.getOrDefault(c.key(), NONE); }
        public boolean write(SiteData.War w, SiteData.City c, int state) { icons.put(c.key(), state); writes.add(c.name() + "=" + state); return true; }
    };

    public static void main(String[] args) throws Exception {
        SiteData site = SiteData.parse(Files.readString(Path.of(args[0])), 40);
        SiteData.War war = site.war("LombriganWar");
        long own = site.cities().stream().filter(SiteData.City::ownOutline).count();
        check(site.cities().size() == 356 && own == 355, "356 cities, " + own + " with their own outline from the SVG");
        check(war != null && war.sides().size() == 2, "LombriganWar has two sides (live in this file: " + (war != null && war.live()) + ")");

        // players -> sides, from the site's own records
        Map<Integer, Integer> bySide = new TreeMap<>();
        String attackerIgn = null, defenderIgn = null;
        var file = TerritoryFile.parse(Files.readString(Path.of(args[0])));
        for (var e : file.players.entrySet()) {
            String ign = e.getValue().ign; if (ign == null) continue;
            int s = site.sideOfPlayer(war, ign); bySide.merge(s, 1, Integer::sum);
            if (s == 0 && attackerIgn == null) attackerIgn = ign;
            if (s == 1 && defenderIgn == null) defenderIgn = ign;
        }
        System.out.println("players by side (-1 = not in the war): " + bySide + "  e.g. attacker " + attackerIgn + ", defender " + defenderIgn);
        check(bySide.getOrDefault(0, 0) > 0 && bySide.getOrDefault(1, 0) > 0, "players are found on both sides");

        // timers by city type
        Map<String, Double> minutes = new HashMap<>(Map.of("Locality", 600.0, "Colonial", 900.0, "Fort", 900.0, "Small", 1200.0, "Medium", 1800.0, "Large", 2700.0));
        Rules rules = new Rules(minutes, 600, 1.5, true, 60, 600, 1);   // seconds
        SiteData.City small = site.cities().stream().filter(c -> c.country().equals("Lombriga") && c.baseKind().equals("Small")).findFirst().orElse(null);
        SiteData.City loc = site.cities().stream().filter(c -> c.country().equals("Lombriga") && c.baseKind().equals("Locality") && !c.capital()).findFirst().orElse(null);
        System.out.println("small=" + small + "\nloc=" + (loc == null ? null : loc.name() + " " + loc.kind()));
        check(small != null && rules.secondsFor(small) == (small.capital() ? 20 * 60 * 1.5 : 20 * 60), "a Small city takes " + (small == null ? "?" : rules.secondsFor(small) / 60) + " min");

        // 1. attackers alone take a city after its time
        CaptureEngine eng = new CaptureEngine(rules, MARKERS);
        double need = rules.secondsFor(small);
        List<CaptureEngine.Present> atk = List.of(new CaptureEngine.Present("a1", 0, small.x(), small.z()), new CaptureEngine.Present("a2", 0, small.x() + 1, small.z()));
        List<CaptureEngine.Event> all = new ArrayList<>();
        for (int t = 0; t < need - 1; t++) all.addAll(eng.tick(site, war, atk, 1));
        check(all.size() == 1 && all.get(0).type() == CaptureEngine.Type.START, "start announced once, nothing else before the time is up");
        var st = eng.statusOf(war, small);
        check(st != null && st.remaining() > 0 && st.remaining() <= 2, "status shows the time left (" + (st == null ? "-" : Math.round(st.remaining())) + " s)");
        all = new ArrayList<>(); for (int t = 0; t < 3; t++) all.addAll(eng.tick(site, war, atk, 1));
        check(all.stream().anyMatch(e -> e.type() == CaptureEngine.Type.CAPTURED && e.side() == 0 && e.from() == 1), "captured by the attackers, from the defenders");
        check(icons.get(small.key()) == 0, "the icon was painted red (side 0)");
        // the cooldown: defenders walking back in do not start anything for a while
        List<CaptureEngine.Present> def = List.of(new CaptureEngine.Present("d1", 1, small.x(), small.z()));
        all = new ArrayList<>(); for (int t = 0; t < 120; t++) all.addAll(eng.tick(site, war, def, 1));
        check(all.isEmpty(), "no recapture during the cooldown");

        // 2. resistance resets the clock, marks contested, and clearing it resumes
        icons.clear(); writes.clear(); eng = new CaptureEngine(rules, MARKERS);
        all = new ArrayList<>(); for (int t = 0; t < 600; t++) all.addAll(eng.tick(site, war, atk, 1));
        double before = eng.statusOf(war, small).seconds();
        List<CaptureEngine.Present> both = new ArrayList<>(atk); both.addAll(def);
        all = new ArrayList<>(); all.addAll(eng.tick(site, war, both, 1)); all.addAll(eng.tick(site, war, both, 1));
        check(all.size() == 1 && all.get(0).type() == CaptureEngine.Type.CONTESTED, "resistance after " + (int) before + " s: contested, announced once");
        check(icons.get(small.key()) == Markers.CONTESTED, "the icon shows contested");
        check(eng.statusOf(war, small).seconds() == 0, "the clock starts over");
        all = eng.tick(site, war, atk, 1);
        check(all.size() == 1 && all.get(0).type() == CaptureEngine.Type.CLEARED, "defenders gone: cleared");
        check(icons.get(small.key()) == Markers.NONE, "the icon goes back to how it was");
        all = new ArrayList<>(); for (int t = 0; t < need + 1; t++) all.addAll(eng.tick(site, war, atk, 1));
        check(all.stream().anyMatch(e -> e.type() == CaptureEngine.Type.CAPTURED), "then the full time after, and it is captured");

        // 3. leaving drops the attempt after the grace time
        icons.clear(); eng = new CaptureEngine(rules, MARKERS);
        for (int t = 0; t < 300; t++) eng.tick(site, war, atk, 1);
        all = new ArrayList<>(); for (int t = 0; t < 59; t++) all.addAll(eng.tick(site, war, List.of(), 1));
        check(all.isEmpty() && eng.statusOf(war, small) != null, "within the grace time the attempt is kept");
        all = new ArrayList<>(); for (int t = 0; t < 3; t++) all.addAll(eng.tick(site, war, List.of(), 1));
        check(all.size() == 1 && all.get(0).type() == CaptureEngine.Type.ABORTED && eng.statusOf(war, small) == null, "after it, it is dropped");

        // 4. a moderator paints the city by hand: that is who holds it
        icons.clear(); eng = new CaptureEngine(rules, MARKERS);
        icons.put(small.key(), 0);   // red already: attackers hold it, so attackers standing there capture nothing
        all = new ArrayList<>(); for (int t = 0; t < need + 10; t++) all.addAll(eng.tick(site, war, atk, 1));
        check(all.isEmpty(), "the side that already holds it (by the icon) does not 'capture' it");
        all = new ArrayList<>(); for (int t = 0; t < need + 1; t++) all.addAll(eng.tick(site, war, def, 1));
        check(all.stream().anyMatch(e -> e.type() == CaptureEngine.Type.CAPTURED && e.side() == 1 && e.from() == 0), "defenders take it back from the red icon");

        // 5. contested by a moderator: one side alone can take it
        icons.clear(); eng = new CaptureEngine(rules, MARKERS); icons.put(small.key(), Markers.CONTESTED);
        all = new ArrayList<>(); for (int t = 0; t < need + 1; t++) all.addAll(eng.tick(site, war, atk, 1));
        check(all.stream().anyMatch(e -> e.type() == CaptureEngine.Type.CAPTURED && e.side() == 0), "a city a moderator left contested goes to the side that stays alone");

        // 6. outside every city nothing happens; different types take different times
        all = eng.tick(site, war, List.of(new CaptureEngine.Present("x", 0, 99999, 99999)), 1);
        check(all.isEmpty(), "standing outside any city does nothing");
        for (String k : List.of("Locality", "Fort", "Small", "Medium", "Large")) {
            var c = site.cities().stream().filter(x -> x.baseKind().equals(k) && !x.capital()).findFirst().orElse(null);
            if (c != null) System.out.println("  " + k + ": " + (int) (rules.secondsFor(c) / 60) + " min (" + c.name() + ")");
        }

        // 7. a city of a country outside the war cannot be taken
        Rules now = new Rules(Map.of("Locality", 0.0, "Colonial", 0.0, "Fort", 15.0, "Small", 30.0, "Medium", 30.0, "Large", 30.0), 0, 1, false, 0, 0, 1);   // config.yml today
        icons.clear(); eng = new CaptureEngine(now, MARKERS);
        SiteData.City neutral = site.cities().stream().filter(c -> site.sideOfCity(war, c) < 0).findFirst().orElse(null);
        all = new ArrayList<>(); for (int t = 0; t < 60; t++) all.addAll(eng.tick(site, war, List.of(new CaptureEngine.Present("a", 0, neutral.x(), neutral.z())), 1));
        check(all.isEmpty() && !icons.containsKey(neutral.key()), "a neutral city (" + neutral.name() + ", " + neutral.country() + ") is left alone");

        // 8. a 0 s city: one line, "captured", not "capturing" too
        icons.clear(); eng = new CaptureEngine(now, MARKERS);
        all = eng.tick(site, war, List.of(new CaptureEngine.Present("a", 0, loc.x(), loc.z())), 1);
        check(all.size() == 1 && all.get(0).type() == CaptureEngine.Type.CAPTURED, "a Locality is taken at once with a single event " + all.stream().map(e -> e.type()).toList());

        // 9. the icon cannot be changed (no marker in dynmap): contested is said once, not every second
        Markers broken = new Markers() {
            public int read(SiteData.War w, SiteData.City c) { return NONE; }
            public boolean write(SiteData.War w, SiteData.City c, int state) { return false; }
        };
        eng = new CaptureEngine(now, broken);
        all = new ArrayList<>(); for (int t = 0; t < 10; t++) all.addAll(eng.tick(site, war, both, 1));
        check(all.size() == 1 && all.get(0).type() == CaptureEngine.Type.CONTESTED, "no marker: contested announced once in 10 s (" + all.size() + ")");

        // 10. shutting down puts back the icon of a city left contested
        icons.clear(); eng = new CaptureEngine(now, MARKERS);
        eng.tick(site, war, atk, 1); eng.tick(site, war, both, 1);
        check(icons.get(small.key()) == Markers.CONTESTED, "contested while both are inside");
        eng.clear();
        check(icons.get(small.key()) == Markers.NONE && eng.statusOf(war, small) == null, "after clear() (shutdown / reload) the icon is back to how it was");

        // 11. a capture limit drawn on the site (zones) replaces the province, for that city only
        var json = com.google.gson.JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        var zone = new com.google.gson.JsonObject();
        zone.addProperty("name", small.name()); zone.addProperty("country", small.country());
        var rings = new com.google.gson.JsonArray(); var ring = new com.google.gson.JsonArray();
        for (int v : new int[]{small.x() - 5, small.z() - 5, small.x() + 5, small.z() - 5, small.x() + 5, small.z() + 5, small.x() - 5, small.z() + 5}) ring.add(v);
        rings.add(ring); zone.add("rings", rings);
        var zones = new com.google.gson.JsonArray(); zones.add(zone); json.add("zones", zones);
        SiteData zoned = SiteData.parse(json.toString(), 40);
        SiteData.City zc = zoned.cities().stream().filter(c -> c.key().equals(small.key())).findFirst().orElse(null);
        check(zc != null && zc.limit() == SiteData.Limit.ZONE && zc.area().contains(small.x(), small.z()) && !zc.area().contains(small.x() + 20, small.z()),
                "a drawn capture limit is used for " + small.name() + " (10x10 square)");
        check(zoned.cities().stream().filter(c -> c.limit() == SiteData.Limit.PROVINCE).count() == own - 1, "every other city keeps its province");
        check(site.cities().stream().noneMatch(c -> c.limit() == SiteData.Limit.ZONE), "without zones in the file nothing changes");

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
