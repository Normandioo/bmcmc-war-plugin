package com.cumania.war;

import com.cumania.war.dynmap.DynmapMarkers;
import com.cumania.war.engine.CaptureEngine;
import com.cumania.war.engine.Markers;
import com.cumania.war.site.SiteData;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
    /** What it was made from: the site's worlds list or the config. */
    final BmcWarPlugin.Spec spec;
    /** When its data was last downloaded (0 = not since the start), and why the last try failed (null = it did not). */
    volatile long fetchedAt;
    volatile String error;
    volatile SiteData site;
    Markers markers;
    DynmapMarkers dynmap;
    CaptureEngine engine;

    WarMap(BmcWarPlugin.Spec spec, Path cache) {
        this.spec = spec;
        this.name = spec.name(); this.world = spec.world(); this.url = spec.url(); this.dynmapWorld = spec.dynmapWorld(); this.wars = spec.wars(); this.cache = cache;
    }

    /** Whether this war is one of this map's (the same object, or a war of the same id on its current data). */
    boolean has(SiteData.War war) {
        SiteData s = site;
        if (s == null || war == null) return false;
        for (SiteData.War w : s.wars()) if (w == war) return true;
        return s.war(war.id()) != null;
    }

    void rebuildMarkers() {
        if (dynmap == null || site == null) return;
        dynmap.rebuild(site);
        if (engine != null) for (Map<String, Boolean> links : engine.allBridges().values()) links.forEach(dynmap::writeBridge);   // the staff's bridges (/bmcwar bridge)
    }
}
