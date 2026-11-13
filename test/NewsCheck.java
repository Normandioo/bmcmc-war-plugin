import com.cumania.war.news.News;
import com.cumania.war.news.NewsDiff;
import com.cumania.war.news.NewsStore;
import com.cumania.war.site.TerritoryFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Checks the diplomatic news (NewsDiff, NewsStore) against the site's real data, without a server: ./gradlew runNewsCheck */
public final class NewsCheck {
    private static int ok, failed;

    private static void check(String what, boolean pass) {
        if (pass) ok++; else failed++;
        System.out.println((pass ? "ok   " : "FAIL ") + what);
    }

    private static String json;
    private static TerritoryFile fresh() { return TerritoryFile.parse(json); }

    private static List<News> diff(TerritoryFile before, TerritoryFile after, Instant now) {
        return NewsDiff.diff(NewsDiff.facts(before, now), after, now);
    }

    private static boolean one(List<News> l, News.Type type, String title) {
        return l.size() == 1 && l.get(0).type() == type && l.get(0).title().equals(title);
    }

    private static TerritoryFile.Member member(String name, String... countries) {
        TerritoryFile.Member m = new TerritoryFile.Member();
        m.name = name;
        m.countries = List.of(countries);
        return m;
    }

    private static TerritoryFile.Side side(String name, TerritoryFile.Member... members) {
        TerritoryFile.Side s = new TerritoryFile.Side();
        s.name = name;
        s.members = List.of(members);
        return s;
    }

    public static void main(String[] args) throws Exception {
        json = Files.readString(Path.of(args[0]));
        Instant now = Instant.parse("2026-11-12T12:00:00Z");
        TerritoryFile base = fresh();
        Set<String> facts = NewsDiff.facts(base, now);
        check("the sample's diplomacy gives facts (" + facts.size() + ")", facts.size() > 10);
        check("same file twice: no news", NewsDiff.diff(facts, fresh(), now).isEmpty());

        // a war declared, with the Staff's text
        TerritoryFile f = fresh();
        TerritoryFile.WarInfo w = new TerritoryFile.WarInfo();
        w.id = "FordWar"; w.name = "War of the Ford"; w.type = "Limited War"; w.notes = "Greneshire says Santana closed the bridge.";
        w.sides = List.of(side("Attackers", member("Greneshire"), member("Cumania")), side("Defenders", member("Santana")));
        f.war.wars = new ArrayList<>(f.war.wars); f.war.wars.add(w);
        List<News> l = diff(base, f, now);
        check("war declared", one(l, News.Type.WAR, "Greneshire and Cumania declare war on Santana"));
        check("war news carries the Staff's notes", l.get(0).notes().equals(w.notes));
        check("war news lists the sides", l.get(0).details().contains("Attackers: Greneshire and Cumania") && l.get(0).details().contains("Defenders: Santana"));

        // the war ends; an end date still to come is not the end yet
        TerritoryFile g = fresh(); g.war.wars = new ArrayList<>(f.war.wars);
        w.ended = "2026-12-01T00:00:00Z";
        check("an end date to come: no news", diff(f, g, now).isEmpty());
        w.ended = "2026-11-12T11:00:00Z";
        TerritoryFile going = fresh(); going.war.wars = new ArrayList<>(f.war.wars);
        going.war.wars.set(going.war.wars.size() - 1, copyWar(w, null));
        l = diff(going, g, now);
        check("war over", one(l, News.Type.PEACE, "The War of the Ford is over"));
        check("a war added already over is history, not news", diff(base, g, now).stream().noneMatch(n -> n.type() == News.Type.PEACE || n.type() == News.Type.WAR));

        // alliances
        f = fresh();
        TerritoryFile.AllianceInfo a = new TerritoryFile.AllianceInfo();
        a.id = "NP"; a.name = "Northern Pact"; a.members = List.of(member("Gaia"), member("Anthropia")); a.notes = "Mutual defence.";
        f.war.alliances = new ArrayList<>(f.war.alliances); f.war.alliances.add(a);
        l = diff(base, f, now);
        check("alliance formed (its members do not each 'join')", one(l, News.Type.ALLIANCE, "The Northern Pact is formed"));
        g = fresh(); g.war.alliances = new ArrayList<>(f.war.alliances);
        TerritoryFile.AllianceInfo a2 = new TerritoryFile.AllianceInfo();
        a2.id = a.id; a2.name = a.name; a2.members = List.of(member("Gaia"), member("Indiana"));
        g.war.alliances.set(g.war.alliances.size() - 1, a2);
        l = diff(f, g, now);
        check("one joins and one leaves", l.size() == 2 && l.stream().anyMatch(n -> n.type() == News.Type.ALLIANCE_JOIN && n.title().equals("Indiana joins the Northern Pact"))
                && l.stream().anyMatch(n -> n.type() == News.Type.ALLIANCE_LEAVE && n.title().equals("Anthropia leaves the Northern Pact")));
        l = diff(f, base, now);
        check("alliance dissolved (its members do not each 'leave')", one(l, News.Type.ALLIANCE_END, "The Northern Pact is dissolved"));

        // truces, pacts, ultimatums
        f = fresh();
        TerritoryFile.Truce t = new TerritoryFile.Truce(); t.a = "Cumania"; t.b = "Santana"; t.until = "2026-11-15T23:59:00Z";
        TerritoryFile.Truce old = new TerritoryFile.Truce(); old.a = "X"; old.b = "Y"; old.until = "2026-11-01T00:00:00Z";
        f.war.truces = List.of(t, old);
        l = diff(base, f, now);
        check("truce in force is news, one already over is not", one(l, News.Type.TRUCE, "Truce between Cumania and Santana") && l.get(0).details().get(0).equals("Until 15 Nov 2026 23:59 UTC"));
        check("a truce running out is not news", diff(f, base, now).isEmpty());
        f = fresh();
        TerritoryFile.Ultimatum u = new TerritoryFile.Ultimatum(); u.from = "Anthropia"; u.to = "Indiana"; u.sent = "2026-11-12T10:00:00Z";
        TerritoryFile.Nap n = new TerritoryFile.Nap(); n.a = "Gaia"; n.b = "Solis";
        f.war.ultimatums = List.of(u); f.war.naps = List.of(n);
        l = diff(base, f, now);
        check("ultimatum and pact", l.size() == 2 && l.stream().anyMatch(x -> x.type() == News.Type.ULTIMATUM && x.title().equals("Anthropia sends an ultimatum to Indiana"))
                && l.stream().anyMatch(x -> x.type() == News.Type.NAP && x.details().get(0).equals("No end date")));

        // the Staff's posts: the map's news.json (/news post of the site's Discord bot)
        String one = "{\"news\": [{\"id\": \"2026-11-12-FordClosed\", \"title\": \"Greneshire closes the Ford\", \"text\": \"Caravans must go round.\", \"posted\": \"2026-11-12T11:00:00Z\"}]}";
        String two = one.replace("}]}", "}, {\"id\": \"2026-11-12-Reopened\", \"title\": \"The Ford reopens\", \"text\": \"Trade is back.\"}, {\"id\": \"x\"}]}");
        List<NewsDiff.Post> posts1 = NewsDiff.parsePosts(one), posts2 = NewsDiff.parsePosts(two);
        check("news.json read; a post without a title is left out", posts1.size() == 1 && posts2.size() == 2);
        check("no list / empty file: no posts", NewsDiff.parsePosts("{}").isEmpty() && NewsDiff.parsePosts("").isEmpty());
        l = NewsDiff.posts(NewsDiff.postFacts(posts1), posts2);
        check("a new post is news, with its text", one(l, News.Type.POST, "The Ford reopens") && "Trade is back.".equals(l.get(0).notes()));
        check("a post deleted is not news", NewsDiff.posts(NewsDiff.postFacts(posts2), posts1).isEmpty());
        Path pdir = Files.createTempDirectory("newsposts");
        NewsStore ps = new NewsStore(pdir.resolve("news.json"));
        check("first reading of a map's posts only remembers them", ps.observePosts("gaia", posts1, now, 50).isEmpty());
        check("the site's first download is remembered apart from the posts", ps.observe("gaia", base, now, 50).isEmpty());
        l = ps.observePosts("gaia", posts2, now, 50);
        check("then a new post is news, on its map", l.size() == 1 && l.get(0).map().equals("gaia") && l.get(0).id() == 1);
        check("the site's data does not undo the posts' memory", ps.observe("gaia", base, now, 50).isEmpty() && ps.observePosts("gaia", posts2, now, 50).isEmpty());

        // subjects
        TerritoryFile sub = fresh();
        f = sub;
        String key = sub.countries.keySet().stream().filter(k -> sub.countries.get(k).diplomacy == null || sub.countries.get(k).diplomacy.overlord == null).findFirst().orElseThrow();
        TerritoryFile.Country c = f.countries.get(key);
        if (c.diplomacy == null) c.diplomacy = new TerritoryFile.Diplomacy();
        c.diplomacy.overlord = List.of("Gaia");
        l = diff(base, f, now);
        String shown = c.info != null && c.info.get("displayName") != null ? c.info.get("displayName") : c.name != null ? c.name : key;
        check("becomes a subject", one(l, News.Type.VASSAL, shown + " becomes a subject of Gaia"));
        check("independence", one(diff(f, base, now), News.Type.INDEPENDENCE, shown + " is no longer a subject of Gaia"));

        // the store: first download only remembers, numbers go up, kept across a restart, unread per player
        Path dir = Files.createTempDirectory("newscheck");
        NewsStore store = new NewsStore(dir.resolve("news.json"));
        check("first download of a map: no news", store.observe("gaia", base, now, 50).isEmpty());
        List<News> got = store.observe("gaia", f, now, 50);
        check("then the change is news, numbered", got.size() == 1 && got.get(0).id() == 1 && got.get(0).map().equals("gaia"));
        check("another map's first download says nothing", store.observe("other", f, now, 50).isEmpty());
        check("a new player has everything unread", store.unread("p1") == 1);
        store.markRead("p1");
        check("read", store.unread("p1") == 0);
        store.save();
        NewsStore again = new NewsStore(dir.resolve("news.json"));
        again.load();
        check("kept across a restart", again.latest(10).size() == 1 && again.latest(10).get(0).title().equals(got.get(0).title()) && again.unread("p1") == 0);
        check("no news again after the restart", again.observe("gaia", f, now, 50).isEmpty());
        List<News> back = again.observe("gaia", base, now, 2);
        check("numbers go on after a restart", back.size() == 1 && back.get(0).id() == 2 && again.unread("p1") == 1);
        again.observe("gaia", f, now, 2);
        again.observe("gaia", base, now, 2);
        check("only 'keep' news are kept, newest first", again.latest(10).size() == 2 && again.latest(10).get(0).id() == 4);
        Files.writeString(dir.resolve("news.json"), "{ not json");
        boolean threw = false;
        try { new NewsStore(dir.resolve("news.json")).load(); } catch (Exception e) { threw = true; }
        check("an unreadable news.json is kept aside", threw && Files.exists(dir.resolve("news.json.bad")) && !Files.exists(dir.resolve("news.json")));

        // long texts fill several pages
        List<String> parts = News.chunks("word ".repeat(200), 228);
        check("long text cut at spaces into pages", parts.size() == 5 && parts.stream().allMatch(p -> p.length() <= 228 && !p.startsWith(" ")));
        check("one long word is cut too", News.chunks("x".repeat(500), 228).size() == 3);

        System.out.println(ok + "/" + (ok + failed) + " ok");
        if (failed > 0) System.exit(1);
    }

    private static TerritoryFile.WarInfo copyWar(TerritoryFile.WarInfo w, String ended) {
        TerritoryFile.WarInfo c = new TerritoryFile.WarInfo();
        c.id = w.id; c.name = w.name; c.type = w.type; c.notes = w.notes; c.sides = w.sides; c.ended = ended;
        return c;
    }
}
