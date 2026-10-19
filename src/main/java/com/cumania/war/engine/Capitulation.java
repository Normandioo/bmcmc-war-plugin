package com.cumania.war.engine;

import com.cumania.war.site.SiteData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Capitulation (7.9), as the site scores a war map: each nation of the war is scored as one Mainland (the plugin does not know
 * where Hinterlands are). Its Small, Medium and Large Settlements share 150 points, split equally between the types it has and then
 * between the settlements of each type (7.9.2-7.9.5); Colonial ones are worth nothing (7.9.10); with no Settlement at all, its
 * Capital is worth 150. Its score is the value of its settlements held by another side (7.9.6); it Capitulates over 100 with its
 * Capital taken (7.9.7).
 */
public final class Capitulation {
    public record Score(String nation, int side, double score, int taken, int total, SiteData.City capital, boolean capitalTaken) {
        public boolean capitulated() { return score > 100 && capitalTaken; }
    }

    private static final List<String> VALUED = List.of("Small", "Medium", "Large");

    public static List<Score> of(SiteData site, SiteData.War war, WarGraph g) {
        Map<String, List<SiteData.City>> byNation = new LinkedHashMap<>();
        for (SiteData.City c : site.cities()) {
            WarGraph.Unit u = g.unitOf(c);
            if (u == null || u.neutral()) continue;
            byNation.computeIfAbsent(c.country(), k -> new ArrayList<>()).add(c);
        }
        List<Score> out = new ArrayList<>();
        for (Map.Entry<String, List<SiteData.City>> e : byNation.entrySet()) {
            int side = site.sideOfNation(war, e.getKey());
            if (side < 0) continue;
            List<SiteData.City> own = e.getValue();
            List<SiteData.City> settlements = own.stream().filter(c -> VALUED.contains(c.baseKind())).toList();
            SiteData.City capital = own.stream().filter(SiteData.City::capital).findFirst().orElse(null);
            List<String> types = VALUED.stream().filter(t -> settlements.stream().anyMatch(c -> c.baseKind().equals(t))).toList();
            double score = 0;
            int taken = 0;
            if (types.isEmpty()) {
                if (capital != null && lost(g, capital, side)) score = 150;
            } else {
                for (String t : types) {
                    List<SiteData.City> ofType = settlements.stream().filter(c -> c.baseKind().equals(t)).toList();
                    for (SiteData.City c : ofType) if (lost(g, c, side)) { score += 150.0 / types.size() / ofType.size(); taken++; }
                }
            }
            out.add(new Score(e.getKey(), side, score, taken, settlements.size(), capital, capital != null && lost(g, capital, side)));
        }
        return out;
    }

    private static boolean lost(WarGraph g, SiteData.City c, int side) {
        WarGraph.Unit u = g.unitOf(c);
        return u != null && u.control >= 0 && u.control != side;
    }
}
