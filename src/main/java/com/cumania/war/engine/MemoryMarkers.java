package com.cumania.war.engine;

import com.cumania.war.site.SiteData;

import java.util.HashMap;
import java.util.Map;

/** Keeps who holds what in memory only: for running without dynmap (nothing is shown on the map, and it is forgotten on restart). */
public final class MemoryMarkers implements Markers {
    private final Map<String, Integer> icons = new HashMap<>();

    @Override public int read(SiteData.War war, SiteData.City city) { return icons.getOrDefault(war.id() + "|" + city.key(), NONE); }

    @Override public boolean write(SiteData.War war, SiteData.City city, int state) {
        if (state == NONE) icons.remove(war.id() + "|" + city.key()); else icons.put(war.id() + "|" + city.key(), state);
        return true;
    }
}
