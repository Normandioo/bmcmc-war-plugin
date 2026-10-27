import com.cumania.war.engine.*;
import com.cumania.war.site.*;

import java.nio.file.*;
import java.util.*;

/**
 * The Law 7 rules against the site's real data with its Connections (docs/territories-network-sample.json):
 *   java WarCheck docs/territories-network-sample.json
 * The front numbers were checked against the site's own War mapmode (js/warmap.js, run in a browser on the same data).
 */
public class WarCheck {
    static int failures = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) failures++; }

    static final Map<String, Integer> icons = new HashMap<>();
    static final Markers MARKERS = new Markers() {
        public int read(SiteData.War w, SiteData.City c) { return icons.getOrDefault(c.key(), NONE); }
        public boolean write(SiteData.War w, SiteData.City c, int state) { if (state == NONE) icons.remove(c.key()); else icons.put(c.key(), state); return true; }
    };

    static Rules rules(boolean inPlay, boolean engagement) {
        return new Rules(Map.of(), 0, 1, false, 0, 0, 1, -1, Map.of(), inPlay, true, engagement);
    }

    static List<CaptureEngine.Event> run(CaptureEngine eng, SiteData site, SiteData.War war, List<CaptureEngine.Present> who, int seconds) {
        List<CaptureEngine.Event> all = new ArrayList<>();
        for (int t = 0; t < seconds; t++) all.addAll(eng.tick(site, war, who, 1));
        return all;
    }

    static CaptureEngine.Present at(SiteData.City c, int side) { return new CaptureEngine.Present("p" + side, side, c.x(), c.z()); }

    static boolean captured(List<CaptureEngine.Event> ev, SiteData.City c) {
        return ev.stream().anyMatch(e -> e.type() == CaptureEngine.Type.CAPTURED && e.unit().contains(c));
    }

    public static void main(String[] args) throws Exception {
        String json = Files.readString(Path.of(args[0]));
        SiteData site = SiteData.parse(json, 40);
        SiteData.War war = site.war("LombriganWar");
        check(site.network() != null && site.network().edges().size() == 449, "449 Connections from the site's map");

        // 1. times by category (7.8.5-6)
        Rules plain = rules(false, false);
        check(plain.secondsFor(site.city("newjubalsteria(greneshire)")) == 30, "a Colonial settlement takes 30 s");
        check(plain.secondsFor(site.city("sakurajiin(greneshire)")) == 15, "a capital that is not a Settlement is a Fort: 15 s (5.6.7)");
        check(plain.secondsFor(site.city("iroquoia(greneshire)")) == 15, "a Locality designated Fort on the site: 15 s");
        check(plain.secondsFor(site.city("cubatao(lombriga)")) == 0, "a plain Locality: at once");
        check(plain.secondsFor(site.city("itapecuru(lombriga)")) == 30, "a Small capital: 30 s");

        // 2. the front, as the site's War mapmode shows it
        icons.clear();
        WarGraph g = WarGraph.solve(site, war, MARKERS, true);
        long inWar = g.units().stream().filter(u -> !u.neutral()).count();
        List<String> inPlay = g.units().stream().filter(u -> u.inPlay).map(u -> u.members.get(0).name()).sorted().toList();
        List<String> encircled = g.units().stream().filter(u -> u.encircled).map(u -> u.members.get(0).name()).sorted().toList();
        check(inWar == 85, "85 localities in the war (" + inWar + ")");
        check(inPlay.equals(List.of("ForestProvince", "ForteDaLuaNova", "NorthernColony", "SouthernProvince")), "In Play as on the site: " + inPlay);
        check(encircled.equals(List.of("FolklandIslands", "ForteDaLuaNova", "NewJubalsteria", "NexusColony", "NorthernColony")), "encircled as on the site (no sea): " + encircled);
        icons.put("forestprovince(eleyetia)", 1); icons.put("itapecuru(lombriga)", Markers.CONTESTED);
        g = WarGraph.solve(site, war, MARKERS, true);
        long ip = g.units().stream().filter(u -> u.inPlay).count(), enc = g.units().stream().filter(u -> u.encircled).count();
        check(ip == 9 && enc == 3, "with ForestProvince taken and Itapecuru contested: 9 In Play, 3 encircled, as on the site (" + ip + ", " + enc + ")");

        // 3. In Play gates the capture (7.8.1)
        SiteData.City itapecuru = site.city("itapecuru(lombriga)"), southern = site.city("southernprovince(lombriga)"), cubatao = site.city("cubatao(lombriga)");
        icons.clear();
        CaptureEngine eng = new CaptureEngine(rules(true, false), MARKERS);
        var ev = run(eng, site, war, List.of(at(itapecuru, 0)), 40);
        check(ev.isEmpty() && !icons.containsKey(itapecuru.key()), "Itapecuru, not In Play, cannot be taken");
        ev = run(eng, site, war, List.of(at(southern, 0)), 2);
        check(captured(ev, southern) && icons.get(southern.key()) == 0, "SouthernProvince, In Play, is taken at once (a Locality)");
        ev = run(eng, site, war, List.of(at(itapecuru, 0)), 31);
        check(captured(ev, itapecuru), "now Itapecuru is next to an attacker's locality: In Play, taken in 30 s");
        icons.clear();
        eng = new CaptureEngine(rules(false, false), MARKERS);
        ev = run(eng, site, war, List.of(at(itapecuru, 0)), 31);
        check(captured(ev, itapecuru), "with rules.in-play off, Itapecuru can be taken anyway");

        // 4. Engagements (7.8.8)
        icons.clear();
        eng = new CaptureEngine(rules(true, true), MARKERS).endOnObjective(false);   // the objective rule is case 10
        ev = run(eng, site, war, List.of(at(southern, 0)), 5);
        check(ev.isEmpty(), "no engagement: nothing is captured");
        eng.startEngagement(site, war, Engagement.Kind.ASSAULT, Set.of(site.city("northerncolony(lombriga)").key()), 20, "test");
        ev = run(eng, site, war, List.of(at(southern, 0)), 5);
        check(ev.isEmpty(), "an Assault on NorthernColony: SouthernProvince is not a target");
        ev = run(eng, site, war, List.of(at(site.city("northerncolony(lombriga)"), 0)), 2);
        check(captured(ev, site.city("northerncolony(lombriga)")), "the Assault's target is taken");
        ev = run(eng, site, war, List.of(), 20 * 60);
        check(ev.stream().anyMatch(e -> e.type() == CaptureEngine.Type.ENGAGEMENT_END) && eng.engagement(war) == null, "the Assault ends by itself after 20 min");
        eng.startEngagement(site, war, Engagement.Kind.STRATEGIC, Set.of(), 60, "test");
        ev = run(eng, site, war, List.of(at(southern, 0)), 2);
        check(captured(ev, southern), "a Strategic Campaign covers the whole Theater");
        eng.stopEngagement(site, war);

        // 4b. an engagement waiting for everyone to be ready: nothing until it begins, and its time runs from then
        icons.clear();
        eng = new CaptureEngine(rules(true, true), MARKERS).endOnObjective(false);   // the objective rule is case 10
        eng.startEngagement(site, war, Engagement.Kind.ASSAULT, Set.of(southern.key()), 20, "test", true);
        ev = run(eng, site, war, List.of(at(southern, 0)), 30 * 60);
        check(ev.isEmpty() && eng.engagement(war).waiting() && !icons.containsKey(southern.key()), "a waiting Assault captures nothing and does not run out (30 min)");
        check(eng.begin(war) != null && !eng.engagement(war).waiting(), "it begins when everyone is ready");
        ev = run(eng, site, war, List.of(at(southern, 0)), 2);
        check(captured(ev, southern), "then its target is taken");
        ev = run(eng, site, war, List.of(), 20 * 60);
        check(ev.stream().anyMatch(e -> e.type() == CaptureEngine.Type.ENGAGEMENT_END), "and it ends 20 min after it began, not after it was called");
        check(eng.begin(war) == null, "nothing to begin once it is over");

        // 5. a Raid: its Fort need not be In Play, and goes back to who held it when the Raid ends (7.17.13)
        icons.clear();
        eng = new CaptureEngine(rules(true, true), MARKERS).endOnObjective(false);   // the objective rule is case 10
        SiteData.City fort = site.city("fortedromcabelha(lombriga)");
        WarGraph.Unit fu = WarGraph.solve(site, war, MARKERS, true).unitOf(fort);
        eng.startEngagement(site, war, Engagement.Kind.RAID, Set.of(fort.key()), 30, "test");
        ev = run(eng, site, war, List.of(at(fort, 0)), 16);
        check(!fu.inPlay && captured(ev, fort) && icons.get(fort.key()) == 0, "a raided Fort (not In Play) is taken in 15 s");
        eng.stopEngagement(site, war);
        check(!icons.containsKey(fort.key()), "and goes back to its holder when the Raid ends");

        // 6. a Warzone is captured as one (7.8.7)
        String wz = json.replace("[\"Cubatao(Lombriga)\",\"Itapecuru(Lombriga)\"]", "[\"Cubatao(Lombriga)\",\"Itapecuru(Lombriga)\",1]");
        SiteData zsite = SiteData.parse(wz, 40);
        check(zsite.network().warzones().size() == 1, "a yellow line between Cubatao and Itapecuru makes a warzone");
        icons.clear();
        eng = new CaptureEngine(rules(false, false), MARKERS);
        ev = run(eng, zsite, war, List.of(at(zsite.city(cubatao.key()), 0)), 29);
        check(ev.stream().noneMatch(e -> e.type() == CaptureEngine.Type.CAPTURED), "standing in Cubatao (a Locality) takes the warzone's 30 s, not 0");
        ev = run(eng, zsite, war, List.of(at(zsite.city(cubatao.key()), 0)), 2);
        check(icons.get(cubatao.key()) == 0 && icons.get(itapecuru.key()) == 0 && ev.stream().filter(e -> e.type() == CaptureEngine.Type.CAPTURED).count() == 1,
                "then both localities change hands together, announced once");
        icons.clear();
        eng = new CaptureEngine(rules(false, false), MARKERS);
        ev = run(eng, zsite, war, List.of(at(zsite.city(cubatao.key()), 0), at(zsite.city(itapecuru.key()), 1)), 3);
        check(icons.get(cubatao.key()) == Markers.CONTESTED && icons.get(itapecuru.key()) == Markers.CONTESTED, "an attacker in one and a defender in the other: the whole warzone is contested");
        ev = run(eng, zsite, war, List.of(), 1);
        check(!icons.containsKey(cubatao.key()) && !icons.containsKey(itapecuru.key()), "both go back when they leave");

        // 7. Theater: a Restricted War over Lombriga only
        String restricted = json.replace("\"id\":\"LombriganWar\"", "\"id\":\"LombriganWar\",\"theater\":\"Lombriga\"");
        SiteData rsite = SiteData.parse(restricted, 40);
        WarGraph rg = WarGraph.solve(rsite, rsite.war("LombriganWar"), MARKERS, true);
        long rIn = rg.units().stream().filter(u -> !u.neutral()).count();
        check(rIn == 7, "a war restricted to Lombriga is fought over its 7 localities (" + rIn + ")");

        // 8. Capitulation (7.9): Lombriga has only Small settlements; taking its capital Itapecuru is 150 points
        icons.clear(); icons.put(itapecuru.key(), 0);
        var caps = Capitulation.of(site, war, WarGraph.solve(site, war, MARKERS, true));
        var lomb = caps.stream().filter(c -> c.nation().equals("Lombriga")).findFirst().orElse(null);
        check(lomb != null && lomb.capitalTaken() && Math.round(lomb.score()) == 150 && lomb.capitulated(), "Lombriga capitulates when Itapecuru falls (" + (lomb == null ? "-" : Math.round(lomb.score())) + ")");

        // 9. a nation that Capitulates is announced once, and the engagement going on ends
        icons.clear();
        eng = new CaptureEngine(rules(true, true), MARKERS).endOnObjective(false);   // the objective rule is case 10
        eng.startEngagement(site, war, Engagement.Kind.STRATEGIC, Set.of(), 60, "test");
        ev = run(eng, site, war, List.of(), 1);
        check(ev.isEmpty(), "nobody has capitulated: no news");
        icons.put(itapecuru.key(), 0);
        ev = run(eng, site, war, List.of(at(southern, 0)), 1);
        var cap = ev.stream().filter(e -> e.type() == CaptureEngine.Type.CAPITULATED).toList();
        check(cap.size() == 1 && cap.get(0).city().country().equals("Lombriga") && cap.get(0).from() == 1 && eng.engagement(war) == null,
                "Lombriga capitulates: said once, and the Strategic Campaign ends");
        check(!captured(ev, southern) && !icons.containsKey(southern.key()), "nothing is captured in the tick the engagement ends");
        ev = run(eng, site, war, List.of(), 3);
        check(ev.stream().noneMatch(e -> e.type() == CaptureEngine.Type.CAPITULATED), "not said again");
        eng = new CaptureEngine(rules(true, true), MARKERS).endOnObjective(false);   // the objective rule is case 10
        eng.startEngagement(site, war, Engagement.Kind.STRATEGIC, Set.of(), 60, "test");
        ev = run(eng, site, war, List.of(), 2);
        check(ev.isEmpty() && eng.engagement(war) != null, "a nation already capitulated when the plugin starts is not news and ends nothing");
        icons.clear();
        eng = new CaptureEngine(rules(true, true), MARKERS).endOnCapitulation(false);
        eng.startEngagement(site, war, Engagement.Kind.STRATEGIC, Set.of(), 60, "test");
        run(eng, site, war, List.of(), 1);
        icons.put(itapecuru.key(), 0);
        ev = run(eng, site, war, List.of(), 1);
        check(ev.stream().anyMatch(e -> e.type() == CaptureEngine.Type.CAPITULATED && e.from() == 0) && eng.engagement(war) != null,
                "with end-on-capitulation off it is said, and the engagement goes on");

        // 10. a Tactical Campaign, an Assault or a Raid ends when one side has taken its objective
        icons.clear();
        eng = new CaptureEngine(rules(false, true), MARKERS);
        eng.startEngagement(site, war, Engagement.Kind.ASSAULT, Set.of(southern.key(), cubatao.key()), 20, "test");
        ev = run(eng, site, war, List.of(at(southern, 0)), 2);
        check(eng.engagement(war) != null && captured(ev, southern), "one of two targets taken: the Assault goes on");
        ev = run(eng, site, war, List.of(at(cubatao, 0)), 3);
        var obj = ev.stream().filter(e -> e.type() == CaptureEngine.Type.OBJECTIVE_TAKEN).toList();
        check(obj.size() == 1 && obj.get(0).side() == 0 && obj.get(0).unit().size() == 2 && eng.engagement(war) == null, "both taken: the Assault is over, won by side 0");
        icons.clear();
        eng = new CaptureEngine(rules(false, true), MARKERS);
        eng.startEngagement(site, war, Engagement.Kind.RAID, Set.of(fort.key()), 30, "test");
        ev = run(eng, site, war, List.of(at(fort, 1)), 20);
        check(ev.isEmpty() && eng.engagement(war) != null, "a Raid's defender standing in its own Fort wins nothing yet");
        ev = run(eng, site, war, List.of(at(fort, 0)), 18);
        check(ev.stream().anyMatch(e -> e.type() == CaptureEngine.Type.OBJECTIVE_TAKEN && e.side() == 0) && eng.engagement(war) == null && !icons.containsKey(fort.key()),
                "the attackers capture the Fort: the Raid is over and the Fort goes back (7.17.10, 7.17.13)");
        icons.clear();
        eng = new CaptureEngine(rules(false, true), MARKERS);
        SiteData.City colony = site.city("northerncolony(lombriga)");   // not a capital: taking it does not make Lombriga capitulate
        SiteData.City own = site.city("forestprovince(eleyetia)");
        int ownSide = site.sideOfCity(war, own);
        eng.startEngagement(site, war, Engagement.Kind.TACTICAL, Set.of(colony.key(), own.key()), 40, "test");
        int lombSide = site.sideOfCity(war, colony);
        check(lombSide >= 0 && ownSide >= 0 && lombSide != ownSide, "NorthernColony and ForestProvince are on opposite sides");
        ev = run(eng, site, war, List.of(at(colony, ownSide)), 1);
        check(captured(ev, colony) && eng.engagement(war) != null, "a Tactical Campaign over one city of each side: NorthernColony (at once) is taken, still on that second");
        ev = run(eng, site, war, List.of(at(colony, ownSide)), 3);
        check(eng.engagement(war) == null && ev.stream().anyMatch(e -> e.type() == CaptureEngine.Type.OBJECTIVE_TAKEN && e.side() == ownSide),
                "next second: one side holds the whole Conflict Area, the Tactical Campaign is over (7.13.8)");
        icons.clear();
        eng = new CaptureEngine(rules(false, true), MARKERS).endOnObjective(false);
        eng.startEngagement(site, war, Engagement.Kind.ASSAULT, Set.of(southern.key()), 20, "test");
        ev = run(eng, site, war, List.of(at(southern, 0)), 4);
        check(eng.engagement(war) != null && ev.stream().noneMatch(e -> e.type() == CaptureEngine.Type.OBJECTIVE_TAKEN), "with end-on-objective off the Assault goes on");

        // 11. resistance resets the clock (7.8.5: 15 s of no enemy presence)
        icons.clear();
        eng = new CaptureEngine(new Rules(Map.of(), 0, 1, true, 0, 0, 1, -1, Map.of(), false, true, false), MARKERS);
        SiteData.City sfort = site.city("sakurajiin(greneshire)");   // a capital that is not a Settlement: a Fort, 15 s
        int fowner = site.sideOfCity(war, sfort), fenemy = 1 - fowner;
        run(eng, site, war, List.of(at(sfort, fenemy)), 12);
        run(eng, site, war, List.of(at(sfort, fenemy), at(sfort, fowner)), 5);
        ev = run(eng, site, war, List.of(at(sfort, fenemy)), 3);
        check(!captured(ev, sfort), "12 s, then defenders came and left: 3 s more do not take the Fort");
        ev = run(eng, site, war, List.of(at(sfort, fenemy)), 12);
        check(captured(ev, sfort), "15 s with no enemy inside do");

        // 12. with the config's times (Settlement / Fort / Locality), a capital drawn as a Locality is still a Fort: 15 s, not 0
        Rules cfg = new Rules(Map.of("Settlement", 30.0, "Fort", 15.0, "Locality", 0.0), 0, 1, true, 0, 0, 1, -1, Map.of(), true, true, true);
        check(cfg.secondsFor(site.city("sakurajiin(greneshire)")) == 15, "a LocalityCapital with the config's times: 15 s (" + cfg.secondsFor(site.city("sakurajiin(greneshire)")) + ")");
        check(cfg.secondsFor(cubatao) == 0 && cfg.secondsFor(itapecuru) == 30, "a plain Locality still 0 s, a Small capital 30 s");

        // 13. the Referee: pause and resume an engagement, undo the latest capture
        icons.clear();
        eng = new CaptureEngine(rules(false, true), MARKERS).endOnObjective(false);
        eng.startEngagement(site, war, Engagement.Kind.ASSAULT, Set.of(itapecuru.key(), southern.key()), 20, "test");
        run(eng, site, war, List.of(), 60);
        check(eng.pause(war) != null && eng.engagement(war).paused() && eng.pause(war) == null, "an Assault is paused (and not twice)");
        ev = run(eng, site, war, List.of(at(itapecuru, 0)), 40 * 60);
        check(ev.stream().noneMatch(e -> e.type() == CaptureEngine.Type.CAPTURED || e.type() == CaptureEngine.Type.ENGAGEMENT_END) && !icons.containsKey(itapecuru.key()),
                "while paused nothing is captured and it does not run out (40 min)");
        check(eng.resume(war) != null && !eng.engagement(war).paused() && Math.round(eng.engagement(war).left(eng.clock(war))) == 19 * 60, "resumed with its 19 min left");
        ev = run(eng, site, war, List.of(at(southern, 0)), 2);
        check(captured(ev, southern) && icons.get(southern.key()) == 0, "then captures go on");
        ev = run(eng, site, war, List.of(at(itapecuru, 0)), 31);
        check(captured(ev, itapecuru) && eng.captures(war).size() == 2, "two captures remembered");
        var undone = eng.undo(war);
        check(undone != null && undone.members().get(0).equals(itapecuru) && !icons.containsKey(itapecuru.key()) && icons.get(southern.key()) == 0,
                "undo puts back the latest only (Itapecuru), SouthernProvince stays taken");
        check(eng.undo(war) != null && !icons.containsKey(southern.key()) && eng.undo(war) == null, "undo again: SouthernProvince back too; then nothing left to undo");

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
