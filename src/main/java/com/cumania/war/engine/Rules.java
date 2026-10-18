package com.cumania.war.engine;

import com.cumania.war.site.SiteData;

import java.util.Map;

/** How the capture works: how long a side must hold a city, by the city's type, and what resistance does. */
public record Rules(Map<String, Double> secondsByKind, double defaultSeconds, double capitalMultiplier, boolean resetOnResistance,
                    double graceSeconds, double cooldownSeconds, int minAttackers) {

    /** Seconds a side must stay in the city, with no one of the holding side inside, to take it. */
    public double secondsFor(SiteData.City city) {
        double seconds = secondsByKind.getOrDefault(city.baseKind(), defaultSeconds);
        if (city.capital()) seconds *= capitalMultiplier;
        return seconds;
    }
}
