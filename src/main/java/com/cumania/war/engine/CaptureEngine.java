package com.cumania.war.engine;

import com.cumania.war.site.SiteData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The capture rule, as the moderators play it by hand today, done by the clock: a side that stays inside a city's land for the
 * city's time, with nobody of the side holding it inside, takes it. Anyone of the holding side inside is resistance: the city shows
 * as contested and the time starts over. Whoever holds a city is what its dynmap icon says, so a moderator changing the icon by hand
 * still counts. No Minecraft classes in here: the plugin feeds it the players' positions once a second.
 */
public final class CaptureEngine {
    public record Present(String player, int side, double x, double z) {}

    public enum Type { START, CONTESTED, CLEARED, ABORTED, CAPTURED }

    /** side = the side taking it (CAPTURED, START) or the one that was; from = who held it. */
    public record Event(Type type, SiteData.War war, SiteData.City city, int side, int from) {}

    /** How a city's capture stands, for the action bar and /bmcwar status. */
    public record Status(SiteData.City city, int side, double seconds, double required, boolean contested) {
        public double remaining() { return Math.max(0, required - seconds); }
    }

    private static final class Progress {
        int side; double seconds; double absent; boolean contested;
        boolean shown;                // the icon really was switched to contested (false when dynmap could not do it)
        int restore = Markers.NONE;   // what the icon showed before this engine marked the city contested
        SiteData.War war; SiteData.City city;   // to put the icon back on shutdown / reload
    }

    private final Rules rules;
    private final Markers markers;
    private final Map<String, Map<String, Progress>> active = new HashMap<>();   // war id -> city key -> progress
    private final Map<String, Double> lockedUntil = new HashMap<>();             // war id + city key -> engine time
    private double clock;

    public CaptureEngine(Rules rules, Markers markers) { this.rules = rules; this.markers = markers; }

    /** One step of dt seconds: who stands where, in a war. Returns what happened. */
    public List<Event> tick(SiteData site, SiteData.War war, List<Present> present, double dt) {
        clock += dt;
        List<Event> events = new ArrayList<>();
        int sides = war.sides().size();
        Map<String, int[]> counts = new HashMap<>();
        Map<String, SiteData.City> byKey = new HashMap<>();
        for (Present p : present) {
            if (p.side() < 0 || p.side() >= sides) continue;
            for (SiteData.City city : site.cities()) {
                if (!city.area().contains(p.x(), p.z())) continue;
                if (site.sideOfCity(war, city) < 0) break;   // a city of a country outside the war cannot be taken in it
                counts.computeIfAbsent(city.key(), k -> new int[sides])[p.side()]++;
                byKey.put(city.key(), city);
                break;   // a place is in one city's land
            }
        }
        Map<String, Progress> mine = active.computeIfAbsent(war.id(), k -> new HashMap<>());
        List<String> keys = new ArrayList<>(counts.keySet());
        for (String k : mine.keySet()) if (!counts.containsKey(k)) keys.add(k);

        for (String key : keys) {
            SiteData.City city = byKey.get(key);
            if (city == null) for (SiteData.City c : site.cities()) if (c.key().equals(key)) { city = c; break; }
            if (city == null) { mine.remove(key); continue; }
            int[] here = counts.getOrDefault(key, new int[sides]);
            int owner = site.sideOfCity(war, city);
            int mark = markers.read(war, city);
            Progress pr = mine.get(key);
            if (pr != null && pr.contested && pr.shown && mark != Markers.CONTESTED) { mine.remove(key); pr = null; }   // a moderator repainted it: that stands
            int base = pr != null && pr.contested ? pr.restore : mark;                                        // contested by us: who held it before
            int holder = base >= 0 ? base : base == Markers.NONE ? owner : -1;                                // contested / uncontrolled by hand: nobody holds it

            int resisting = holder >= 0 ? here[holder] : 0;
            List<Integer> attackers = new ArrayList<>();
            for (int s = 0; s < sides; s++) if (s != holder && here[s] > 0) attackers.add(s);

            if (attackers.isEmpty() || (attackers.size() == 1 && here[attackers.get(0)] < rules.minAttackers())) {
                // no one hostile inside: a capture under way waits a little, then is dropped
                if (pr == null) continue;
                pr.absent += dt;
                if (pr.absent >= rules.graceSeconds()) {
                    if (pr.contested && pr.shown) markers.write(war, city, pr.restore);
                    mine.remove(key);
                    events.add(new Event(Type.ABORTED, war, city, pr.side, holder));
                }
                continue;
            }
            if (lockedUntil.getOrDefault(war.id() + "|" + key, 0.0) > clock) continue;   // just taken, or the icon could not be changed: left alone for a while

            if (resisting > 0 || attackers.size() > 1) {
                // resistance (or several sides at once): contested, and the time starts over
                if (pr == null) { pr = progress(war, city, attackers.get(0)); mine.put(key, pr); }
                pr.absent = 0;
                if (rules.resetOnResistance()) pr.seconds = 0;
                if (!pr.contested) {
                    pr.restore = mark;
                    pr.contested = true;
                    pr.shown = markers.write(war, city, Markers.CONTESTED);
                    events.add(new Event(Type.CONTESTED, war, city, pr.side, holder));
                }
                continue;
            }

            int attacker = attackers.get(0);
            boolean wasContested = pr != null && pr.contested;
            Event start = null;
            if (pr == null || pr.side != attacker || wasContested) {
                if (wasContested) {   // the resistance is gone: back to the holder's colour, and the clock runs from now
                    if (pr.shown) markers.write(war, city, pr.restore);
                    events.add(new Event(Type.CLEARED, war, city, attacker, holder));
                }
                if (pr == null || pr.side != attacker) {
                    pr = progress(war, city, attacker); mine.put(key, pr);
                    start = new Event(Type.START, war, city, attacker, holder);
                    events.add(start);
                } else {
                    pr.contested = false;
                    pr.shown = false;
                    if (rules.resetOnResistance()) pr.seconds = 0;
                }
            }
            pr.absent = 0;
            pr.seconds += dt;
            if (pr.seconds >= rules.secondsFor(city)) {
                mine.remove(key);
                if (markers.write(war, city, attacker)) {
                    lockedUntil.put(war.id() + "|" + key, clock + rules.cooldownSeconds());
                    if (start != null) events.remove(start);   // taken the moment they arrived (a 0 s city): just say it was captured
                    events.add(new Event(Type.CAPTURED, war, city, attacker, holder));
                } else {
                    lockedUntil.put(war.id() + "|" + key, clock + 300);   // the icon could not be changed: do not try every second
                }
            }
        }
        return events;
    }

    /** The capture under way in this city, or null. */
    public Status statusOf(SiteData.War war, SiteData.City city) {
        Map<String, Progress> mine = active.get(war.id());
        Progress pr = mine == null ? null : mine.get(city.key());
        return pr == null ? null : new Status(city, pr.side, pr.seconds, rules.secondsFor(city), pr.contested);
    }

    /** All captures under way in a war, with their cities. */
    public List<Status> statuses(SiteData site, SiteData.War war) {
        List<Status> out = new ArrayList<>();
        Map<String, Progress> mine = active.get(war.id());
        if (mine == null) return out;
        for (SiteData.City c : site.cities()) {
            Progress pr = mine.get(c.key());
            if (pr != null) out.add(new Status(c, pr.side, pr.seconds, rules.secondsFor(c), pr.contested));
        }
        return out;
    }

    /** A moderator set the icon by hand: whatever was under way there no longer applies. */
    public void forget(SiteData.War war, SiteData.City city) {
        Map<String, Progress> mine = active.get(war.id());
        if (mine != null) mine.remove(city.key());
    }

    /**
     * Drops everything under way (shutdown, reload, or the war ended), putting back the icon of every city this engine had marked
     * contested: otherwise it would stay contested in dynmap and the next start would read it as a moderator's call.
     */
    public void clear() {
        for (Map<String, Progress> mine : active.values())
            for (Progress pr : mine.values()) if (pr.contested && pr.shown) markers.write(pr.war, pr.city, pr.restore);
        active.clear();
    }

    private static Progress progress(SiteData.War war, SiteData.City city, int side) {
        Progress pr = new Progress(); pr.war = war; pr.city = city; pr.side = side; return pr;
    }
}
