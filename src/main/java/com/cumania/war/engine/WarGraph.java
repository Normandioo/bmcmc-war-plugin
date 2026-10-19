package com.cumania.war.engine;

import com.cumania.war.site.SiteData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A war's front, worked out the way the site's War mapmode does it (js/warmap.js, "Wars without a map"), naval rules left out:
 *   - its Theater (7.1.2-7.1.4): a Restricted War's "theater", else every city of its Belligerents; the rest is neutral
 *   - who holds each city: its dynmap icon, else its own nation's side
 *   - Warzones (5.5.14-15, 7.8.7): cities joined by a yellow line are one unit, held by whoever holds most of it
 *   - Connections (5.5.8-10), through open Bridges and Straits (7.7.10-14), and across clusters of neutral cities between the
 *     two cities of the war with the fewest neutral ones in between (7.7.3-4); a tie needs Staff's pick ("shortCircuits")
 *   - Encircled: cut off from every Capital (or Supply Capital) its side holds (7.8.9, 7.8.11)
 *   - In Play: Connected to a unit held by a different side (7.8.1); an Encircled pocket only puts In Play a unit whose
 *     capture would reconnect it (7.8.10); a Contested unit, or a Warzone being split, is In Play.
 * No Minecraft classes in here.
 */
public final class WarGraph {
    /** Control of a city outside the war's Theater. */
    public static final int NEUTRAL = -4;

    public static final class Unit {
        public final List<SiteData.City> members = new ArrayList<>();
        String key;
        /** A side, Markers.CONTESTED, Markers.UNCONTROLLED or NEUTRAL. */
        public int control;
        /** A Warzone whose cities are held by different sides: still being fought over. */
        public boolean fighting;
        public boolean encircled, inPlay;
        /** Neighbouring unit -> true when the only Connection to it is across neutral land. */
        public final Map<Unit, Boolean> neighbours = new LinkedHashMap<>();
        /** The units that put this one In Play, and whether that is because capturing this one would break their encirclement. */
        public final List<Unit> by = new ArrayList<>();
        public final List<Unit> byBreaking = new ArrayList<>();
        /** Encircled enemy pockets next to it that do not put it In Play (7.8.10). */
        public final List<Unit> encircledBy = new ArrayList<>();
        final Set<Integer> capitalSides = new HashSet<>();

        public String key() { return key; }
        public boolean warzone() { return members.size() > 1; }
        public boolean neutral() { return control == NEUTRAL; }
        public boolean settlement() { return members.stream().anyMatch(SiteData.City::settlement); }
        public boolean fort() { return members.stream().anyMatch(SiteData.City::fort); }
        public boolean capital() { return members.stream().anyMatch(SiteData.City::capital); }
        /** How long it takes (7.8.5-7): a Warzone counts as a single Fort or Settlement. */
        public SiteData.Category category() {
            return settlement() ? SiteData.Category.SETTLEMENT : fort() ? SiteData.Category.FORT : SiteData.Category.LOCALITY;
        }
        /** "Name" or "Name warzone (n)". */
        public String label() { return members.size() == 1 ? members.get(0).name() : members.get(0).name() + " warzone (" + members.size() + " localities)"; }
    }

    /** A tie for the shortest Connection across neutral land between two pieces of the war: Staff must pick it ("shortCircuits"). */
    public record Tie(int distance, List<String[]> pairs) {}

    private final List<Unit> units = new ArrayList<>();
    private final Map<String, Unit> unitByCity = new HashMap<>();
    private final Map<String, Unit> unitByKey = new HashMap<>();
    private final List<Tie> ties = new ArrayList<>();
    private final List<Integer> noCapital = new ArrayList<>();
    private final List<String> theaterUnknown = new ArrayList<>();
    private boolean hasNetwork;

    public List<Unit> units() { return units; }
    public Unit unitOf(SiteData.City city) { return city == null ? null : unitByCity.get(city.key()); }
    public Unit unit(String key) { return unitByKey.get(key); }
    /** False when the site sent no Connections: then nothing can be worked out, and every unit of the war counts as In Play. */
    public boolean hasNetwork() { return hasNetwork; }
    public List<Tie> ties() { return ties; }
    /** Sides holding no Capital at all (nothing of theirs is reached, so nothing is Encircled). */
    public List<Integer> noCapital() { return noCapital; }
    /** Theater entries that name nothing known (a Hinterland's name, for instance): they are left out. */
    public List<String> theaterUnknown() { return theaterUnknown; }

    public static WarGraph solve(SiteData site, SiteData.War war, Markers markers, boolean encirclement) {
        WarGraph g = new WarGraph();
        SiteData.Network net = site.network();
        g.hasNetwork = net != null;
        Set<String> theater = site.theaterOf(war, g.theaterUnknown);

        // Control of every city
        Map<String, Integer> control = new HashMap<>();
        Map<String, Integer> ownerSide = new HashMap<>();
        for (SiteData.City c : site.cities()) {
            int owner = site.sideOfCity(war, c);
            ownerSide.put(c.key(), owner);
            boolean inTheater = theater != null ? theater.contains(c.key()) : owner >= 0;
            int ctl;
            if (!inTheater) ctl = NEUTRAL;
            else {
                int mark = markers.read(war, c);
                if (mark >= 0) ctl = mark < war.sides().size() ? mark : NEUTRAL;
                else if (mark == Markers.CONTESTED) ctl = Markers.CONTESTED;
                else if (mark == Markers.UNCONTROLLED) ctl = Markers.UNCONTROLLED;
                else ctl = owner >= 0 ? owner : NEUTRAL;   // held by a nation outside this war
            }
            control.put(c.key(), ctl);
        }

        // Warzones: cities joined by yellow lines are one unit (7.8.7)
        Map<String, String> parent = new HashMap<>();
        for (SiteData.City c : site.cities()) parent.put(c.key(), c.key());
        if (net != null) for (String[] w : net.warzones()) {
            if (parent.containsKey(w[0]) && parent.containsKey(w[1])) parent.put(find(parent, w[0]), find(parent, w[1]));
        }
        Map<String, Unit> byRoot = new LinkedHashMap<>();
        for (SiteData.City c : site.cities()) {
            Unit u = byRoot.computeIfAbsent(find(parent, c.key()), k -> { Unit nu = new Unit(); g.units.add(nu); return nu; });
            u.members.add(c);
            g.unitByCity.put(c.key(), u);
        }
        for (Unit u : g.units) {
            List<String> keys = new ArrayList<>();
            for (SiteData.City c : u.members) keys.add(c.key());
            Collections.sort(keys);
            u.key = String.join("|", keys);
            g.unitByKey.put(u.key, u);
            unitControl(u, control);
            for (SiteData.City c : u.members) {
                if (c.capital() && ownerSide.get(c.key()) >= 0) u.capitalSides.add(ownerSide.get(c.key()));
                if (war.supplyCapitals().contains(c.key()) && u.control >= 0) u.capitalSides.add(u.control);
            }
        }
        if (net == null) return g;

        // Connections, through open Bridges and Straits too (5.5.10, 7.7.11)
        Map<String, List<String>> linkEnds = new HashMap<>();
        for (String[] e : net.edges()) {
            Unit a = g.unitByCity.get(e[0]), b = g.unitByCity.get(e[1]);
            if (a != null && b != null) link(a, b, false);
            else if (a != null && net.isLink(e[1])) linkEnds.computeIfAbsent(e[1], k -> new ArrayList<>()).add(e[0]);
            else if (b != null && net.isLink(e[0])) linkEnds.computeIfAbsent(e[0], k -> new ArrayList<>()).add(e[1]);
        }
        for (Map.Entry<String, List<String>> e : linkEnds.entrySet()) {
            boolean open = war.bridges().getOrDefault(e.getKey(), net.linkOpen().getOrDefault(e.getKey(), true));
            if (!open) continue;
            List<String> ends = e.getValue();
            for (int i = 0; i < ends.size(); i++) for (int j = i + 1; j < ends.size(); j++) link(g.unitByCity.get(ends.get(i)), g.unitByCity.get(ends.get(j)), false);
        }

        if (war.autoShortCircuits()) g.shortCircuits(war);

        // Encirclement (7.8.9)
        Set<Integer> sides = new HashSet<>();
        for (Unit u : g.units) if (u.control >= 0) sides.add(u.control);
        if (encirclement) for (int side : sides) {
            List<Unit> own = g.units.stream().filter(u -> u.control == side).toList();
            List<Unit> start = own.stream().filter(u -> u.capitalSides.contains(side)).toList();
            if (start.isEmpty()) { g.noCapital.add(side); continue; }
            Set<Unit> reached = new HashSet<>(start);
            ArrayDeque<Unit> queue = new ArrayDeque<>(start);
            while (!queue.isEmpty()) {
                Unit u = queue.poll();
                for (Unit v : u.neighbours.keySet()) if (v.control == side && reached.add(v)) queue.add(v);
            }
            for (Unit u : own) u.encircled = !reached.contains(u) && !u.neighbours.isEmpty();
        }

        // In Play (7.8.1, 7.8.10)
        for (Unit u : g.units) {
            if (u.neutral()) continue;
            if (u.control == Markers.CONTESTED || u.fighting) u.inPlay = true;
            for (Unit v : u.neighbours.keySet()) {
                int side = v.control;
                if (side < 0 || side == u.control) continue;
                if (!v.encircled) { u.by.add(v); continue; }
                boolean reconnects = u.neighbours.keySet().stream().anyMatch(w -> w != v && w.control == side && !w.encircled);
                if (reconnects) u.byBreaking.add(v); else u.encircledBy.add(v);
            }
            if (!u.by.isEmpty() || !u.byBreaking.isEmpty()) u.inPlay = true;
        }
        return g;
    }

    private static String find(Map<String, String> parent, String k) {
        while (!parent.get(k).equals(k)) { parent.put(k, parent.get(parent.get(k))); k = parent.get(k); }
        return k;
    }

    /** A Warzone held by different sides stays with whoever holds most of it, and is still being fought over. */
    private static void unitControl(Unit u, Map<String, Integer> control) {
        List<Integer> controls = new ArrayList<>();
        for (SiteData.City c : u.members) controls.add(control.get(c.key()));
        Set<Integer> distinct = new HashSet<>(controls);
        if (distinct.size() == 1) { u.control = controls.get(0); return; }
        Map<Integer, Integer> counts = new HashMap<>();
        for (int c : controls) if (c >= 0) counts.merge(c, 1, Integer::sum);
        if (!counts.isEmpty()) {
            u.control = counts.entrySet().stream().max(Comparator.comparingInt((Map.Entry<Integer, Integer> e) -> e.getValue())
                    .thenComparingInt(e -> -controls.indexOf(e.getKey()))).get().getKey();
        } else if (distinct.contains(Markers.CONTESTED)) u.control = Markers.CONTESTED;
        else u.control = controls.stream().filter(c -> c != NEUTRAL).findFirst().orElse(NEUTRAL);
        u.fighting = u.control != NEUTRAL;
    }

    private static void link(Unit a, Unit b, boolean shortCircuit) {
        if (a == null || b == null || a == b) return;
        a.neighbours.merge(b, shortCircuit, (x, y) -> x && y);
        b.neighbours.merge(a, shortCircuit, (x, y) -> x && y);
    }

    /** Connections across clusters of neutral cities (7.7.3-4), as js/warmap.js applyShortCircuits. */
    private void shortCircuits(SiteData.War war) {
        Map<Unit, Unit> parent = new HashMap<>();
        for (Unit u : units) if (!u.neutral()) parent.put(u, u);
        for (Unit u : units) {
            if (u.neutral()) continue;
            for (Unit v : u.neighbours.keySet()) if (!v.neutral()) union(parent, u, v);
        }

        // The neutral clusters
        Map<Unit, List<Unit>> clusterOf = new HashMap<>();
        List<List<Unit>> clusters = new ArrayList<>();
        for (Unit u : units) {
            if (!u.neutral() || clusterOf.containsKey(u)) continue;
            List<Unit> c = new ArrayList<>();
            clusters.add(c);
            clusterOf.put(u, c);
            ArrayDeque<Unit> queue = new ArrayDeque<>(List.of(u));
            while (!queue.isEmpty()) {
                Unit x = queue.poll();
                c.add(x);
                for (Unit v : x.neighbours.keySet()) if (v.neutral() && !clusterOf.containsKey(v)) { clusterOf.put(v, c); queue.add(v); }
            }
        }

        // Staff's picks first
        for (List<String> pair : war.shortCircuits()) {
            Unit a = unitByCity.get(pair.get(0)), b = unitByCity.get(pair.get(1));
            if (a == null || b == null || a == b) continue;
            if (!a.neighbours.containsKey(b)) link(a, b, true);
            if (parent.containsKey(a) && parent.containsKey(b)) union(parent, a, b);
        }

        record Option(Unit a, Unit b, int d) {}
        List<Option> options = new ArrayList<>();
        for (List<Unit> c : clusters) {
            Set<Unit> inCluster = new HashSet<>(c);
            Map<Unit, List<Unit>> border = new LinkedHashMap<>();
            for (Unit m : c) for (Unit v : m.neighbours.keySet()) if (!v.neutral()) border.computeIfAbsent(v, k -> new ArrayList<>()).add(m);
            List<Unit> list = new ArrayList<>(border.keySet());
            if (list.size() < 2) continue;
            for (int i = 0; i < list.size(); i++) {
                Unit a = list.get(i);
                Map<Unit, Integer> dist = clusterDistances(border.get(a), inCluster);
                for (int j = i + 1; j < list.size(); j++) {
                    Unit b = list.get(j);
                    if (find(parent, a) == find(parent, b)) continue;
                    int d = Integer.MAX_VALUE;
                    for (Unit m : border.get(b)) if (dist.containsKey(m)) d = Math.min(d, dist.get(m));
                    if (d != Integer.MAX_VALUE) options.add(new Option(a, b, d));
                }
            }
        }
        options.sort(Comparator.comparingInt(Option::d));
        for (int i = 0; i < options.size();) {
            int d = options.get(i).d();
            int j = i;
            while (j < options.size() && options.get(j).d() == d) j++;
            Map<String, List<Option>> byPair = new LinkedHashMap<>();
            for (Option o : options.subList(i, j)) {
                Unit ra = find(parent, o.a()), rb = find(parent, o.b());
                if (ra == rb) continue;
                int x = units.indexOf(ra), y = units.indexOf(rb);
                byPair.computeIfAbsent(Math.min(x, y) + "|" + Math.max(x, y), k -> new ArrayList<>()).add(o);
            }
            i = j;
            for (List<Option> list : byPair.values()) {
                Option first = list.get(0);
                if (find(parent, first.a()) == find(parent, first.b())) continue;
                if (list.size() == 1) link(first.a(), first.b(), true);
                else {
                    List<String[]> pairs = new ArrayList<>();
                    for (Option o : list) pairs.add(new String[]{o.a().label(), o.b().label()});
                    ties.add(new Tie(d, pairs));
                }
                union(parent, first.a(), first.b());
            }
        }
    }

    private static Unit find(Map<Unit, Unit> parent, Unit u) {
        while (parent.get(u) != u) { parent.put(u, parent.get(parent.get(u))); u = parent.get(u); }
        return u;
    }

    private static void union(Map<Unit, Unit> parent, Unit a, Unit b) {
        Unit ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent.put(ra, rb);
    }

    /** How many of the cluster's cities lie between a border city and each cluster city (counting the one reached). */
    private static Map<Unit, Integer> clusterDistances(List<Unit> starts, Set<Unit> inCluster) {
        Map<Unit, Integer> dist = new HashMap<>();
        ArrayDeque<Unit> queue = new ArrayDeque<>();
        for (Unit m : starts) if (!dist.containsKey(m)) { dist.put(m, 1); queue.add(m); }
        while (!queue.isEmpty()) {
            Unit x = queue.poll();
            for (Unit y : x.neighbours.keySet()) if (inCluster.contains(y) && !dist.containsKey(y)) { dist.put(y, dist.get(x) + 1); queue.add(y); }
        }
        return dist;
    }
}
