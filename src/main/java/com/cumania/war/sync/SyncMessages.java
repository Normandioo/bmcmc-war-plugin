package com.cumania.war.sync;

import com.cumania.war.engine.Markers;
import com.cumania.war.site.SiteData;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The messages this plugin sends to the BMC Atlas client mod on the "bmcatlas:sync" channel, so the mod does not have to keep asking
 * dynmap and the site. Small JSON texts (UTF-8):
 *
 *   {"t":"hello","v":1}
 *       Sent once a player's mod opens the channel: this server has the plugin.
 *
 *   {"t":"map","v":1,"map":"gaia","site":"https://.../territories.json","generated":"2026-11-10T...",
 *    "wars":[{"id":"w1","cities":{"name(country)":0,...},"bridges":{"link(country)":true,...}}]}
 *       One per map, sent again whenever any of it changes. "generated" is the site data the plugin is using: when it differs from
 *       the mod's copy, the mod downloads territories.json once. "cities" holds, for each city of the war's countries whose icon has a
 *       war colour, the side that holds it (0, 1, 2) or -2 (contested) / -3 (uncontrolled); a city that is not listed is held by its
 *       own country, exactly like an icon with no war colour. "bridges" are the bridges and straits the staff opened or closed.
 *
 * No Bukkit classes here, so the messages can be checked without a server (test/SyncCheck.java).
 */
public final class SyncMessages {
    private SyncMessages() {}

    public static final String CHANNEL = "bmcatlas:sync";
    public static final int VERSION = 1;

    public static String hello() {
        JsonObject o = new JsonObject();
        o.addProperty("t", "hello");
        o.addProperty("v", VERSION);
        return o.toString();
    }

    /** The state of one map: its site data and, for each war it runs, who holds each city and the staff's bridges. */
    public static String map(String mapKey, String siteUrl, SiteData site, List<SiteData.War> wars, Markers markers,
                             Function<SiteData.War, Map<String, Boolean>> bridges) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "map");
        o.addProperty("v", VERSION);
        o.addProperty("map", mapKey);
        o.addProperty("site", siteUrl);
        o.addProperty("generated", site.generated() == null ? "" : site.generated());
        com.google.gson.JsonArray ws = new com.google.gson.JsonArray();
        for (SiteData.War war : wars) {
            JsonObject w = new JsonObject();
            w.addProperty("id", war.id());
            JsonObject cities = new JsonObject();
            if (markers != null) for (SiteData.City c : site.cities()) {
                if (site.sideOfCity(war, c) < 0) continue;   // only the cities of the war's countries are played for
                int state = markers.read(war, c);
                if (state == Markers.NONE) continue;          // no war colour: its own country holds it
                cities.addProperty(c.key(), state);
            }
            w.add("cities", cities);
            JsonObject b = new JsonObject();
            Map<String, Boolean> links = bridges == null ? null : bridges.apply(war);
            if (links != null) links.forEach(b::addProperty);
            w.add("bridges", b);
            ws.add(w);
        }
        o.add("wars", ws);
        return o.toString();
    }
}
