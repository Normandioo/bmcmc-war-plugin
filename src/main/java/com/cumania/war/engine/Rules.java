package com.cumania.war.engine;

import com.cumania.war.site.SiteData;

import java.util.Map;

/**
 * How the capture works: how long a side must hold a city, by the city's type, and what resistance does.
 * maxDepth / depthByCity: how far below the natural ground a player may be and still count in a city (-1 = any depth);
 * depthByCity is keyed by the city's key, "name(country)" in lower case.
 */
public record Rules(Map<String, Double> secondsByKind, double defaultSeconds, double capitalMultiplier, boolean resetOnResistance,
                    double graceSeconds, double cooldownSeconds, int minAttackers, int maxDepth, Map<String, Integer> depthByCity) {

    /** Without an underground rule: depth never matters. */
    public Rules(Map<String, Double> secondsByKind, double defaultSeconds, double capitalMultiplier, boolean resetOnResistance,
                 double graceSeconds, double cooldownSeconds, int minAttackers) {
        this(secondsByKind, defaultSeconds, capitalMultiplier, resetOnResistance, graceSeconds, cooldownSeconds, minAttackers, -1, Map.of());
    }

    /** Seconds a side must stay in the city, with no one of the holding side inside, to take it. */
    public double secondsFor(SiteData.City city) {
        double seconds = secondsByKind.getOrDefault(city.baseKind(), defaultSeconds);
        if (city.capital()) seconds *= capitalMultiplier;
        return seconds;
    }

    /** How many blocks below the natural ground a player may be in this city and still count (-1 = any depth). */
    public int maxDepthFor(SiteData.City city) { return depthByCity.getOrDefault(city.key(), maxDepth); }

    /** True when a player this deep is too far underground to count in this city, neither as attacker nor as resistance. */
    public boolean tooDeep(SiteData.City city, int depth) {
        int max = maxDepthFor(city);
        return max >= 0 && depth > max;
    }
}
