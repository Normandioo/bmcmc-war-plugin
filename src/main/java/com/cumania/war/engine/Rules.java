package com.cumania.war.engine;

import com.cumania.war.site.SiteData;

import java.util.Map;

/**
 * How the capture works.
 * secondsByKind: seconds to take a city, by its category ("Settlement", "Fort", "Locality", 7.8.5-6) or, as an explicit override,
 * by its type ("Small", "Fort"...); a category not given takes the Laws' time (Settlement 30 s, Fort 15 s, Locality at once).
 * inPlay: only a city In Play may be captured (7.8.1); encirclement: work out Encircled pockets (7.8.9-10);
 * requireEngagement: captures only happen during an Engagement (7.8.8).
 */
public record Rules(Map<String, Double> secondsByKind, double defaultSeconds, double capitalMultiplier, boolean resetOnResistance,
                    double graceSeconds, double cooldownSeconds, int minAttackers,
                    boolean inPlay, boolean encirclement, boolean requireEngagement) {

    /** Without In Play, Encirclement or Engagements: the plain capture rule. */
    public Rules(Map<String, Double> secondsByKind, double defaultSeconds, double capitalMultiplier, boolean resetOnResistance,
                 double graceSeconds, double cooldownSeconds, int minAttackers) {
        this(secondsByKind, defaultSeconds, capitalMultiplier, resetOnResistance, graceSeconds, cooldownSeconds, minAttackers, false, false, false);
    }

    /** The Laws' times (7.8.5-6). */
    public static double lawSeconds(SiteData.Category category) {
        return switch (category) { case SETTLEMENT -> 30; case FORT -> 15; case LOCALITY -> 0; };
    }

    /** Seconds a side must stay in the city, with no one of the holding side inside, to take it. */
    public double secondsFor(SiteData.City city) {
        // the category first: a capital drawn as a Locality is a Fort (5.6.7), and must not get the "Locality" time by its kind's name
        Double seconds = secondsByKind.get(city.category().label);
        if (seconds == null) seconds = secondsByKind.get(city.baseKind());
        if (seconds == null) seconds = lawSeconds(city.category());
        return city.capital() ? seconds * capitalMultiplier : seconds;
    }

    /** A Warzone counts as a single Fort or Settlement (7.8.7): the longest time of its cities. */
    public double secondsFor(WarGraph.Unit unit) {
        double max = 0;
        for (SiteData.City c : unit.members) max = Math.max(max, secondsFor(c));
        return max;
    }
}
