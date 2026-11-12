import com.cumania.war.engine.*;
import com.cumania.war.site.*;
import java.nio.file.*;
import java.util.*;

/** A reload builds a new engine for the same map: the engagement under way goes on. java ReloadCheck <territories.json> */
public class ReloadCheck {
    static int failures = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) failures++; }

    public static void main(String[] args) throws Exception {
        SiteData site = SiteData.parse(Files.readString(Path.of(args[0])), 40);
        SiteData.War war = site.wars().stream().filter(w -> w.sides().size() >= 2).findFirst().orElseThrow();
        Rules rules = new Rules(Map.of(), 0, 1, false, 0, 0, 1, false, true, true);
        Markers markers = new MemoryMarkers();

        CaptureEngine old = new CaptureEngine(rules, markers);
        old.startEngagement(site, war, Engagement.Kind.STRATEGIC, Set.of(), 60, "test");
        old.tick(site, war, List.of(), 5);
        double clock = old.clock(war);
        old.releaseCaptures();
        check(old.engagement(war) != null, "letting go of the captures keeps the engagement");

        CaptureEngine fresh = new CaptureEngine(rules, markers);
        fresh.inherit(old);
        check(fresh.engagement(war) != null && fresh.engagement(war).kind() == Engagement.Kind.STRATEGIC, "the new engine goes on with the engagement");
        check(fresh.clock(war) == clock, "and with the war's clock");

        old.clear();
        check(old.engagement(war) == null && fresh.engagement(war) != null, "clearing the old engine does not touch the new one");

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
