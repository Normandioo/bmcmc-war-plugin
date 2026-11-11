import com.cumania.war.engine.Markers;
import com.cumania.war.engine.MemoryMarkers;
import com.cumania.war.site.SiteData;
import com.cumania.war.sync.SyncMessages;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Checks the messages sent to the BMC Atlas mod (SyncMessages) against the site's real data, without a server:
 *   javac -d out -cp gson.jar $(find src/main/java/com/cumania/war/site src/main/java/com/cumania/war/engine src/main/java/com/cumania/war/sync -name '*.java') test/SyncCheck.java
 *   java -cp out:gson.jar SyncCheck docs/territories-network-sample.json
 */
public final class SyncCheck {
    private static int ok, failed;

    private static void check(String what, boolean pass) {
        if (pass) ok++; else failed++;
        System.out.println((pass ? "ok   " : "FAIL ") + what);
    }

    public static void main(String[] args) throws Exception {
        SiteData site = SiteData.parse(Files.readString(Path.of(args[0])), 40);
        SiteData.War war = site.wars().stream().filter(w -> w.sides().size() >= 2).findFirst().orElseThrow();
        SiteData.City own0 = null, own1 = null, other1 = null, neutral = null;
        for (SiteData.City c : site.cities()) {
            int side = site.sideOfCity(war, c);
            if (side == 0 && own0 == null) own0 = c;
            else if (side == 1 && own1 == null) own1 = c;
            else if (side == 1 && other1 == null) other1 = c;
            else if (side < 0 && neutral == null) neutral = c;
        }
        System.out.println("war " + war.id() + ": " + own0.key() + ", " + own1.key() + ", " + other1.key() + ", neutral " + neutral.key());

        JsonObject hello = JsonParser.parseString(SyncMessages.hello()).getAsJsonObject();
        check("hello says what it is and its version", hello.get("t").getAsString().equals("hello") && hello.get("v").getAsInt() == SyncMessages.VERSION);

        Markers m = new MemoryMarkers();
        m.write(war, own0, 0);                     // its own side's colour
        m.write(war, own1, 0);                     // taken by side 0
        m.write(war, other1, Markers.CONTESTED);
        m.write(war, neutral, 1);                  // a neutral country's city never counts, whatever its icon
        Map<String, Boolean> bridges = Map.of("bridge(somewhere)", false);
        String text = SyncMessages.map("gaia", "https://example/territories.json", site, List.of(war), m, w -> bridges);
        JsonObject msg = JsonParser.parseString(text).getAsJsonObject();
        check("map message has its map, site and generated", msg.get("t").getAsString().equals("map") && msg.get("map").getAsString().equals("gaia")
                && msg.get("site").getAsString().equals("https://example/territories.json") && msg.has("generated"));
        JsonObject w = msg.getAsJsonArray("wars").get(0).getAsJsonObject();
        JsonObject cities = w.getAsJsonObject("cities");
        check("war id", w.get("id").getAsString().equals(war.id()));
        check("own colour is sent", cities.has(own0.key()) && cities.get(own0.key()).getAsInt() == 0);
        check("taken city is sent with its captor", cities.get(own1.key()).getAsInt() == 0);
        check("contested is -2", cities.get(other1.key()).getAsInt() == Markers.CONTESTED);
        check("neutral country's city is left out", !cities.has(neutral.key()));
        check("only the painted cities are sent", cities.size() == 3);
        check("staff bridges are sent", w.getAsJsonObject("bridges").get("bridge(somewhere)").getAsBoolean() == false);
        check("same state, same text (sent only when it changes)", text.equals(SyncMessages.map("gaia", "https://example/territories.json", site, List.of(war), m, x -> bridges)));
        m.write(war, own1, Markers.NONE);
        String after = SyncMessages.map("gaia", "https://example/territories.json", site, List.of(war), m, x -> bridges);
        check("a city given back changes the text and leaves the list", !after.equals(text) && !JsonParser.parseString(after).getAsJsonObject()
                .getAsJsonArray("wars").get(0).getAsJsonObject().getAsJsonObject("cities").has(own1.key()));
        check("no markers: wars still listed, no cities", JsonParser.parseString(SyncMessages.map("gaia", "u", site, List.of(war), null, null)).getAsJsonObject()
                .getAsJsonArray("wars").get(0).getAsJsonObject().getAsJsonObject("cities").size() == 0);
        check("message stays small", text.length() < 32_000);

        // versions (update notices)
        check("version from a release tag", "1.2.5".equals(com.cumania.war.sync.Versions.versionIn("v1.2.5-build.160")));
        check("version from a 26.x jar", "1.2.4".equals(com.cumania.war.sync.Versions.versionIn("1.2.4+mc26.x")));
        check("numbers, not text: 1.2.10 > 1.2.9", com.cumania.war.sync.Versions.newer("1.2.10", "1.2.9"));
        check("same version is not newer", !com.cumania.war.sync.Versions.newer("0.19.0", "0.19.0"));
        check("older is not newer", !com.cumania.war.sync.Versions.newer("1.2.4", "1.2.6"));
        var rel = com.cumania.war.sync.Versions.release("{\"tag_name\":\"v1.2.6-build.170\",\"html_url\":\"https://github.com/x/releases/tag/v1.2.6-build.170\"}");
        check("GitHub release read", rel != null && rel.version().equals("1.2.6") && rel.page().endsWith("v1.2.6-build.170"));
        check("unreadable release is null", com.cumania.war.sync.Versions.release("not json") == null);
        var hello2 = com.cumania.war.sync.Versions.modHello("{\"t\":\"hello\",\"v\":1,\"mod\":\"1.2.6\",\"selfCheck\":true}");
        check("mod hello read", hello2 != null && hello2.version().equals("1.2.6") && hello2.selfCheck());
        check("mod hello without selfCheck", !com.cumania.war.sync.Versions.modHello("{\"t\":\"hello\",\"mod\":\"1.2.6\"}").selfCheck());
        check("other messages are not a mod hello", com.cumania.war.sync.Versions.modHello("{\"t\":\"map\"}") == null);

        System.out.println(ok + "/" + (ok + failed) + " ok");
        if (failed > 0) System.exit(1);
    }
}
