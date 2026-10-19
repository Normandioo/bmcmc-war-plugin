package com.cumania.war.engine;

import com.cumania.war.site.SiteData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The capture rule (7.8), done by the clock: a side that stays inside a locality's land for its time, with nobody of the side
 * holding it inside, takes it. Anyone of the holding side inside is resistance: the locality shows as Contested. Whoever holds a
 * locality is what its dynmap icon says, so a moderator changing the icon by hand still counts.
 * A Warzone (7.8.7) is captured as one: standing in any of its localities counts, and all of them change hands together.
 * Only a locality of the war's Theater may be captured, only while In Play (7.8.1, when the rule is on and the site sends its
 * Connections), and only during an Engagement that covers it (7.8.8, when the rule is on).
 * No Minecraft classes in here: the plugin feeds it the players' positions once a second.
 */
public final class CaptureEngine {
    /** depth = blocks below the natural ground (0 on the surface, in a building or under water). */
    public record Present(String player, int side, double x, double z, int depth) {
        public Present(String player, int side, double x, double z) { this(player, side, x, z, 0); }
    }

    public enum Type { START, CONTESTED, CLEARED, ABORTED, CAPTURED, ENGAGEMENT_END }

    /**
     * side = the side taking it (CAPTURED, START) or the one that was; from = who held it. city = the unit's first locality,
     * unit = all of its localities (more than one for a Warzone). ENGAGEMENT_END has no city.
     */
    public record Event(Type type, SiteData.War war, SiteData.City city, int side, int from, List<SiteData.City> unit) {
        public Event(Type type, SiteData.War war, SiteData.City city, int side, int from) { this(type, war, city, side, from, city == null ? List.of() : List.of(city)); }
        public String label() { return city == null ? "" : unit.size() > 1 ? city.name() + " warzone" : city.name(); }
    }

    /** How a capture stands, for the action bar and /bmcwar status. */
    public record Status(SiteData.City city, int side, double seconds, double required, boolean contested, int size) {
        public double remaining() { return Math.max(0, required - seconds); }
    }

    /** Why a locality cannot be captured right now (null = it can). */
    public enum Blocked { NOT_IN_WAR, NOT_IN_PLAY, NO_ENGAGEMENT, WAITING, NOT_A_TARGET }

    private static final class Progress {
        int side; double seconds; double absent; boolean contested;
        boolean shown;                                          // the icons really were switched to contested
        int holder = -1;                                        // who held it when it became contested
        final Map<String, Integer> restore = new HashMap<>();   // city key -> its icon before it was marked contested
        SiteData.War war; List<SiteData.City> members;          // to put the icons back on shutdown / reload
    }

    private final Rules rules;
    private final Markers markers;
    private final Map<String, Map<String, Progress>> active = new HashMap<>();   // war id -> unit key -> progress
    private final Map<String, Double> lockedUntil = new HashMap<>();             // war id + unit key -> war clock
    private final Map<String, Double> clocks = new HashMap<>();                  // war id -> seconds run
    private final Map<String, WarGraph> graphs = new HashMap<>();                // war id -> the front as of the last tick
    private final Map<String, Engagement> engagements = new HashMap<>();         // war id -> the Engagement going on

    public CaptureEngine(Rules rules, Markers markers) { this.rules = rules; this.markers = markers; }

    public Rules rules() { return rules; }

    /** The war's clock: seconds it has been ticked. */
    public double clock(SiteData.War war) { return clocks.getOrDefault(war.id(), 0.0); }

    /** The front as of the last tick (or worked out now, when the war has not been ticked yet). */
    public WarGraph graph(SiteData site, SiteData.War war) {
        WarGraph g = graphs.get(war.id());
        return g != null ? g : WarGraph.solve(site, war, markers, rules.encirclement());
    }

    // ----- Engagements -----

    public Engagement engagement(SiteData.War war) { return engagements.get(war.id()); }

    /** Starts an Engagement (a Raid's targets are remembered as they are now, to be put back at its end). Replaces one going on. */
    public Engagement startEngagement(SiteData site, SiteData.War war, Engagement.Kind kind, Set<String> targets, double minutes, String by) {
        return startEngagement(site, war, kind, targets, minutes, by, false);
    }

    /** waiting = opened but held until begin() (everyone ready): nothing is captured and its clock does not run meanwhile. */
    public Engagement startEngagement(SiteData site, SiteData.War war, Engagement.Kind kind, Set<String> targets, double minutes, String by, boolean waiting) {
        double now = clock(war);
        Map<String, Integer> restore = new HashMap<>();
        Map<SiteData.City, Integer> cities = new LinkedHashMap<>();
        if (kind == Engagement.Kind.RAID) for (String k : targets) {
            SiteData.City c = site.city(k);
            if (c == null) continue;
            int mark = markers.read(war, c);
            restore.put(k, mark);
            cities.put(c, mark);
        }
        Engagement e = new Engagement(kind, Set.copyOf(targets), now, Double.POSITIVE_INFINITY, Map.copyOf(restore), by, true, minutes);
        if (!waiting) e = e.begun(now);
        engagements.put(war.id(), e);
        warsSeen.put(war.id(), war);
        if (cities.isEmpty()) raidRestore.remove(war.id()); else raidRestore.put(war.id(), cities);
        return e;
    }

    /** Ends the Engagement: captures under way stop, and a Raid's targets go back to who held them before it (7.17.13). */
    public Engagement stopEngagement(SiteData site, SiteData.War war) {
        Engagement e = engagements.remove(war.id());
        if (e == null) return null;
        Map<String, Progress> mine = active.remove(war.id());
        if (mine != null) for (Progress pr : mine.values()) if (pr.contested && pr.shown) restore(pr);
        Map<SiteData.City, Integer> raid = raidRestore.remove(war.id());
        if (raid != null) for (Map.Entry<SiteData.City, Integer> r : raid.entrySet()) markers.write(war, r.getKey(), r.getValue());
        return e;
    }

    /** The Engagement waiting for everyone to be ready begins now: captures are allowed and its clock starts. Null when none is waiting. */
    public Engagement begin(SiteData.War war) {
        Engagement e = engagements.get(war.id());
        if (e == null || !e.waiting()) return null;
        Engagement b = e.begun(clock(war));
        engagements.put(war.id(), b);
        return b;
    }

    /** Why this unit cannot be captured now, or null when it can. */
    public Blocked blocked(WarGraph g, WarGraph.Unit u, Engagement e) {
        if (u == null || u.neutral()) return Blocked.NOT_IN_WAR;
        if (rules.requireEngagement() && e == null) return Blocked.NO_ENGAGEMENT;
        if (e != null && e.waiting()) return Blocked.WAITING;
        if (e != null && u.members.stream().noneMatch(m -> e.covers(m.key()))) return Blocked.NOT_A_TARGET;
        boolean raid = e != null && e.kind() == Engagement.Kind.RAID;   // a Raid's Fort need not be In Play (7.17.13)
        if (rules.inPlay() && g.hasNetwork() && !raid && !u.inPlay) return Blocked.NOT_IN_PLAY;
        return null;
    }

    // ----- The clock -----

    /** One step of dt seconds: who stands where, in a war. Returns what happened. */
    public List<Event> tick(SiteData site, SiteData.War war, List<Present> present, double dt) {
        double clock = clocks.merge(war.id(), dt, Double::sum);
        List<Event> events = new ArrayList<>();
        Engagement eng = engagements.get(war.id());
        if (eng != null && eng.endsAt() <= clock) {
            stopEngagement(site, war);
            events.add(new Event(Type.ENGAGEMENT_END, war, null, -1, -1, List.of()));
            eng = null;
        }
        WarGraph g = WarGraph.solve(site, war, markers, rules.encirclement());
        graphs.put(war.id(), g);
        int sides = war.sides().size();

        Map<String, int[]> counts = new LinkedHashMap<>();
        for (Present p : present) {
            if (p.side() < 0 || p.side() >= sides) continue;
            for (SiteData.City city : site.cities()) {
                if (!city.area().contains(p.x(), p.z())) continue;
                if (rules.tooDeep(city, p.depth())) break;   // too far underground for this city: neither attacker nor resistance
                WarGraph.Unit u = g.unitOf(city);
                if (blocked(g, u, eng) != null) break;       // outside the war, not In Play, or not fought over now
                counts.computeIfAbsent(u.key(), k -> new int[sides])[p.side()]++;
                break;   // a place is in one city's land
            }
        }
        Map<String, Progress> mine = active.computeIfAbsent(war.id(), k -> new HashMap<>());
        List<String> keys = new ArrayList<>(counts.keySet());
        for (String k : mine.keySet()) if (!counts.containsKey(k)) keys.add(k);

        for (String key : keys) {
            WarGraph.Unit u = g.unit(key);
            Progress pr = mine.get(key);
            if (u == null || blocked(g, u, eng) != null) {   // the map changed, or the unit is no longer fought over
                if (pr != null) {
                    if (pr.contested && pr.shown) restore(pr);
                    mine.remove(key);
                    if (u != null) events.add(event(Type.ABORTED, war, u, pr.side, pr.holder));
                }
                continue;
            }
            int[] here = counts.getOrDefault(key, new int[sides]);
            if (pr != null && pr.contested && pr.shown && u.members.stream().anyMatch(c -> markers.read(war, c) != Markers.CONTESTED)) {
                mine.remove(key); pr = null;   // a moderator repainted it: that stands
            }
            int holder = pr != null && pr.contested ? pr.holder : Math.max(-1, u.control);   // contested / uncontrolled: nobody holds it

            int resisting = holder >= 0 ? here[holder] : 0;
            List<Integer> attackers = new ArrayList<>();
            for (int s = 0; s < sides; s++) if (s != holder && here[s] > 0) attackers.add(s);

            if (attackers.isEmpty() || (attackers.size() == 1 && here[attackers.get(0)] < rules.minAttackers())) {
                // no one hostile inside: a capture under way waits a little, then is dropped
                if (pr == null) continue;
                pr.absent += dt;
                if (pr.absent >= rules.graceSeconds()) {
                    if (pr.contested && pr.shown) restore(pr);
                    mine.remove(key);
                    events.add(event(Type.ABORTED, war, u, pr.side, holder));
                }
                continue;
            }
            if (lockedUntil.getOrDefault(war.id() + "|" + key, 0.0) > clock) continue;   // just taken, or the icon could not be changed

            if (resisting > 0 || attackers.size() > 1) {
                // resistance (or several sides at once): contested
                if (pr == null) { pr = progress(war, u, attackers.get(0)); mine.put(key, pr); }
                pr.absent = 0;
                if (rules.resetOnResistance()) pr.seconds = 0;
                if (!pr.contested) {
                    pr.contested = true;
                    pr.holder = holder;
                    pr.restore.clear();
                    for (SiteData.City c : u.members) pr.restore.put(c.key(), markers.read(war, c));
                    pr.shown = paint(war, u, Markers.CONTESTED) > 0;
                    events.add(event(Type.CONTESTED, war, u, pr.side, holder));
                }
                continue;
            }

            int attacker = attackers.get(0);
            boolean wasContested = pr != null && pr.contested;
            Event start = null;
            if (pr == null || pr.side != attacker || wasContested) {
                if (wasContested) {   // the resistance is gone: back to the holder's colour, and the clock runs on
                    if (pr.shown) restore(pr);
                    events.add(event(Type.CLEARED, war, u, attacker, holder));
                }
                if (pr == null || pr.side != attacker) {
                    pr = progress(war, u, attacker); mine.put(key, pr);
                    start = event(Type.START, war, u, attacker, holder);
                    events.add(start);
                } else {
                    pr.contested = false;
                    pr.shown = false;
                    if (rules.resetOnResistance()) pr.seconds = 0;
                }
            }
            pr.absent = 0;
            pr.seconds += dt;
            if (pr.seconds >= rules.secondsFor(u)) {
                mine.remove(key);
                if (paint(war, u, attacker) > 0) {
                    lockedUntil.put(war.id() + "|" + key, clock + rules.cooldownSeconds());
                    if (start != null) events.remove(start);   // taken the moment they arrived (a 0 s locality): just say it was captured
                    events.add(event(Type.CAPTURED, war, u, attacker, holder));
                } else {
                    lockedUntil.put(war.id() + "|" + key, clock + 300);   // the icon could not be changed: do not try every second
                }
            }
        }
        return events;
    }

    private static Event event(Type type, SiteData.War war, WarGraph.Unit u, int side, int from) {
        return new Event(type, war, u.members.get(0), side, from, List.copyOf(u.members));
    }

    /** Paints every locality of the unit; how many could be painted. */
    private int paint(SiteData.War war, WarGraph.Unit u, int state) {
        int ok = 0;
        for (SiteData.City c : u.members) if (markers.write(war, c, state)) ok++;
        return ok;
    }

    private void restore(Progress pr) {
        for (SiteData.City c : pr.members) markers.write(pr.war, c, pr.restore.getOrDefault(c.key(), Markers.NONE));
    }

    // ----- What is going on -----

    private String unitKey(SiteData.War war, SiteData.City city) {
        WarGraph g = graphs.get(war.id());
        WarGraph.Unit u = g == null ? null : g.unitOf(city);
        return u != null ? u.key() : city.key();
    }

    /** The capture under way in this city (or its Warzone), or null. */
    public Status statusOf(SiteData.War war, SiteData.City city) {
        Map<String, Progress> mine = active.get(war.id());
        Progress pr = mine == null ? null : mine.get(unitKey(war, city));
        return pr == null ? null : status(pr);
    }

    private Status status(Progress pr) {
        double required = 0;
        for (SiteData.City c : pr.members) required = Math.max(required, rules.secondsFor(c));
        return new Status(pr.members.get(0), pr.side, pr.seconds, required, pr.contested, pr.members.size());
    }

    /** All captures under way in a war. */
    public List<Status> statuses(SiteData site, SiteData.War war) {
        List<Status> out = new ArrayList<>();
        Map<String, Progress> mine = active.get(war.id());
        if (mine != null) for (Progress pr : mine.values()) out.add(status(pr));
        return out;
    }

    /** A moderator set the icon by hand: whatever was under way there no longer applies. */
    public void forget(SiteData.War war, SiteData.City city) {
        Map<String, Progress> mine = active.get(war.id());
        if (mine != null) mine.remove(unitKey(war, city));
    }

    /**
     * Drops everything under way (shutdown, reload), putting back the icons of everything this engine had marked contested, and the
     * targets of a Raid going on: otherwise they would stay as they are in dynmap and the next start would read them as a moderator's call.
     */
    public void clear() {
        for (Map<String, Progress> mine : active.values()) for (Progress pr : mine.values()) if (pr.contested && pr.shown) restore(pr);
        active.clear();
        for (Map.Entry<String, Map<SiteData.City, Integer>> raid : raidRestore.entrySet()) {
            SiteData.War war = warsSeen.get(raid.getKey());
            if (war != null) for (Map.Entry<SiteData.City, Integer> r : raid.getValue().entrySet()) markers.write(war, r.getKey(), r.getValue());
        }
        raidRestore.clear();
        engagements.clear();
    }

    private final Map<String, SiteData.War> warsSeen = new HashMap<>();
    private final Map<String, Map<SiteData.City, Integer>> raidRestore = new HashMap<>();   // war id -> a Raid's targets as they were

    /** The fronts are reset by hand: every capture under way in this war is dropped (its icons are about to be repainted), and nothing stays locked. */
    public void reset(SiteData.War war) {
        active.remove(war.id());
        lockedUntil.keySet().removeIf(k -> k.startsWith(war.id() + "|"));
    }

    private Progress progress(SiteData.War war, WarGraph.Unit u, int side) {
        warsSeen.put(war.id(), war);
        Progress pr = new Progress(); pr.war = war; pr.members = List.copyOf(u.members); pr.side = side; return pr;
    }
}
