package com.cumania.war.engine;

import com.cumania.war.site.SiteData;

import java.util.Map;

/**
 * How the capture works.
 * secondsByKind: seconds to take a city, by its category ("Settlement", "Fort", "Locality", 7.8.5-6) or, as an explicit override,
 * by its type ("Small", "Fort"...); a category not given takes the Laws' time (Settlement 30 s, Fort 15 s, Locality at once).
 * maxDepth / depthByCity: how far below the natural ground a player may be and still count in a city (-1 = any depth);
 * depthByCity is keyed by the city's key, "name(country)" in lower case.
 * inPlay: only a city In Play may be captured (7.8.1); encirclement: work out Encircled pockets (7.8.9-10);
 * requireEngagement: captures only happen during an Engagement (7.8.8).
 */
public record Rules(Map<String, Double> secondsByKind, double defaultSeconds, double capitalMultiplier, boolean resetOnResistance,
                    double graceSeconds, double cooldownSeconds, int minAttackers, int maxDepth, Map<String, Integer> depthByCity,
                    boolean inPlay, boolean encirclement, boolean requireEngagement) {

    /** Without an underground rule, In Play or Engagements: the plain capture rule. */
    public Rules(Map<String, Double> secondsByKind, double defaultSeconds, double capitalMultiplier, boolean resetOnResistance,
                 double graceSeconds, double cooldownSeconds, int minAttackers) {
        this(secondsByKind, defaultSeconds, capitalMultiplier, resetOnResistance, graceSeconds, cooldownSeconds, minAttackers, -1, Map.of());
    }

    public Rules(Map<String, Double> secondsByKind, double defaultSeconds, double capitalMultiplier, boolean resetOnResistance,
                 double graceSeconds, double cooldownSeconds, int minAttackers, int maxDepth, Map<String, Integer> depthByCity) {
        this(secondsByKind, defaultSeconds, capitalMultiplier, resetOnResistance, graceSeconds, cooldownSeconds, minAttackers, maxDepth, depthByCity,
                false, false, false);
    }

    /** The Laws' times (7.8.5-6). */
    public static double lawSeconds(SiteData.Category category) {
        return switch (category) { case SETTLEMENT -> 30; case FORT -> 15; case LOCALITY -> 0; };
    }

    /** Seconds a side must stay in the city, with no one of the holding side inside, to take it. */
    public double secondsFor(SiteData.City city) {
        Double seconds = secondsByKind.get(city.baseKind());
        if (seconds == null) seconds = secondsByKind.get(city.category().label);
        if (seconds == null) seconds = lawSeconds(city.category());
        return city.capital() ? seconds * capitalMultiplier : seconds;
    }

    /** A Warzone counts as a single Fort or Settlement (7.8.7): the longest time of its cities. */
    public double secondsFor(WarGraph.Unit unit) {
        double max = 0;
        for (SiteData.City c : unit.members) max = Math.max(max, secondsFor(c));
        return max;
    }

    /** How many blocks below the natural ground a player may be in this city and still count (-1 = any depth). */
    public int maxDepthFor(SiteData.City city) { return depthByCity.getOrDefault(city.key(), maxDepth); }

    /** True when a player this deep is too far underground to count in this city, neither as attacker nor as resistance. */
    public boolean tooDeep(SiteData.City city, int depth) {
        int max = maxDepthFor(city);
        return max >= 0 && depth > max;
    }
}
