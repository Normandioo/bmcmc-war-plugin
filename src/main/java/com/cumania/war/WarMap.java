package com.cumania.war;

import com.cumania.war.dynmap.DynmapMarkers;
import com.cumania.war.engine.CaptureEngine;
import com.cumania.war.engine.Markers;
import com.cumania.war.site.SiteData;

import java.nio.file.Path;
import java.util.List;

/**
 * One map the wars are fought on: a Minecraft world, the site's territories.json for it (its cities, wars and countries), the dynmap
 * world its markers are in, and its own capture engine. Gaia is one; an event map (a simulated war in another world) is another, and
 * both run at the same time, each with its own cities, wars and fronts.
 */
final class WarMap {
    final String name;
    final String world;
    final String url;
    final String dynmapWorld;
    final List<String> wars;
    final Path cache;
    volatile SiteData site;
    Markers markers;
    DynmapMarkers dynmap;
    CaptureEngine engine;

    WarMap(String name, String world, String url, String dynmapWorld, List<String> wars, Path cache) {
        this.name = name; this.world = world; this.url = url; this.dynmapWorld = dynmapWorld; this.wars = wars; this.cache = cache;
    }

    /** Whether this war is one of this map's (the same object, or a war of the same id on its current data). */
    boolean has(SiteData.War war) {
        SiteData s = site;
        if (s == null || war == null) return false;
        for (SiteData.War w : s.wars()) if (w == war) return true;
        return s.war(war.id()) != null;
    }

    void rebuildMarkers() { if (dynmap != null && site != null) dynmap.rebuild(site); }
}
