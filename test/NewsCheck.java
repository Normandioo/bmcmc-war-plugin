import com.cumania.war.news.FontWidths;
import com.cumania.war.news.Flags;
import com.cumania.war.news.News;
import com.cumania.war.news.NewsDiff;
import com.cumania.war.news.NewsFilter;
import com.cumania.war.news.NewsStore;
import com.cumania.war.news.WorldClock;
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

    /** The news item's opening text names every one of these. */
    private static boolean tells(News n, String... parts) {
        String text = n.details().isEmpty() ? "" : n.details().get(0);
        for (String p : parts) if (!text.contains(p)) { System.out.println("     text: " + text); return false; }
        return true;
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
        check("war news opens with a text about it", tells(l.get(0), "Greneshire and Cumania", "Santana", "War of the Ford", "It is a Limited War."));
        check("the same news item always reads the same", l.get(0).details().get(0).equals(diff(base, f, now).get(0).details().get(0)));

        // the war ends; an end date still to come is not the end yet
        TerritoryFile g = fresh(); g.war.wars = new ArrayList<>(f.war.wars);
        w.ended = "2026-12-01T00:00:00Z";
        check("an end date to come: no news", diff(f, g, now).isEmpty());
        w.ended = "2026-11-12T11:00:00Z";
        TerritoryFile going = fresh(); going.war.wars = new ArrayList<>(f.war.wars);
        going.war.wars.set(going.war.wars.size() - 1, copyWar(w, null));
        l = diff(going, g, now);
        check("war over", one(l, News.Type.PEACE, "The War of the Ford is over"));
        check("war over, told", tells(l.get(0), "War of the Ford", "Greneshire and Cumania against Santana") && !l.get(0).details().get(0).contains("after"));
        w.started = "2026-11-01T11:00:00Z";
        check("war over, with how long it lasted", tells(diff(going, g, now).get(0), "War of the Ford", "after 11 years of war"));   // 11 real days are 11 years in the world
        check("a war added already over is history, not news", diff(base, g, now).stream().noneMatch(n -> n.type() == News.Type.PEACE || n.type() == News.Type.WAR));

        // alliances
        f = fresh();
        TerritoryFile.AllianceInfo a = new TerritoryFile.AllianceInfo();
        a.id = "NP"; a.name = "Northern Pact"; a.members = List.of(member("Gaia"), member("Anthropia")); a.notes = "Mutual defence.";
        f.war.alliances = new ArrayList<>(f.war.alliances); f.war.alliances.add(a);
        l = diff(base, f, now);
        check("alliance formed (its members do not each 'join')", one(l, News.Type.ALLIANCE, "The Northern Pact is formed"));
        check("alliance formed, told", tells(l.get(0), "Gaia and Anthropia", "Northern Pact"));
        g = fresh(); g.war.alliances = new ArrayList<>(f.war.alliances);
        TerritoryFile.AllianceInfo a2 = new TerritoryFile.AllianceInfo();
        a2.id = a.id; a2.name = a.name; a2.members = List.of(member("Gaia"), member("Indiana"));
        g.war.alliances.set(g.war.alliances.size() - 1, a2);
        l = diff(f, g, now);
        check("one joins and one leaves", l.size() == 2 && l.stream().anyMatch(n -> n.type() == News.Type.ALLIANCE_JOIN && n.title().equals("Indiana joins the Northern Pact"))
                && l.stream().anyMatch(n -> n.type() == News.Type.ALLIANCE_LEAVE && n.title().equals("Anthropia leaves the Northern Pact")));
        check("joining and leaving, told with who is left", l.stream().anyMatch(n -> n.type() == News.Type.ALLIANCE_JOIN && tells(n, "Indiana", "Northern Pact", "It now has 2 members: Gaia and Indiana."))
                && l.stream().anyMatch(n -> n.type() == News.Type.ALLIANCE_LEAVE && tells(n, "Anthropia", "Northern Pact", "It now has 2 members: Gaia and Indiana.")));
        l = diff(f, base, now);
        check("alliance dissolved (its members do not each 'leave')", one(l, News.Type.ALLIANCE_END, "The Northern Pact is dissolved"));
        check("every kind of news has a text about it", tells(l.get(0), "Northern Pact"));

        // truces, pacts, ultimatums
        f = fresh();
        TerritoryFile.Truce t = new TerritoryFile.Truce(); t.a = "Cumania"; t.b = "Santana"; t.until = "2026-11-15T23:59:00Z";
        TerritoryFile.Truce old = new TerritoryFile.Truce(); old.a = "X"; old.b = "Y"; old.until = "2026-11-01T00:00:00Z";
        f.war.truces = List.of(t, old);
        l = diff(base, f, now);
        check("truce in force is news, one already over is not", one(l, News.Type.TRUCE, "Truce between Cumania and Santana") && l.get(0).details().get(1).equals("Until December 30th, 904 AES")
                && tells(l.get(0), "Cumania and Santana", "until December 30th, 904 AES") && !l.get(0).details().get(0).contains("UTC"));
        check("a truce running out is not news", diff(f, base, now).isEmpty());
        f = fresh();
        TerritoryFile.Ultimatum u = new TerritoryFile.Ultimatum(); u.from = "Anthropia"; u.to = "Indiana"; u.sent = "2026-11-12T10:00:00Z";
        TerritoryFile.Nap n = new TerritoryFile.Nap(); n.a = "Gaia"; n.b = "Solis";
        f.war.ultimatums = List.of(u); f.war.naps = List.of(n);
        l = diff(base, f, now);
        check("ultimatum and pact", l.size() == 2 && l.stream().anyMatch(x -> x.type() == News.Type.ULTIMATUM && x.title().equals("Anthropia sends an ultimatum to Indiana"))
                && l.stream().anyMatch(x -> x.type() == News.Type.NAP && x.details().get(1).equals("No end date")
                        && tells(x, "Gaia and Solis") && !x.details().get(0).contains("until"))
                && l.stream().anyMatch(x -> x.type() == News.Type.ULTIMATUM && tells(x, "Anthropia", "Indiana", "on June 1st, 901 AES")));

        // the server's calendar: the same dates the site's own world clock (js/clock.js) gives for these moments
        WorldClock gaia = WorldClock.DEFAULT;
        String[][] site = {{"2024-05-25T00:00:00Z", "January 1st, 0 AES"}, {"2024-05-26T02:00:00Z", "February 1st, 1 AES"}, {"2024-05-24T12:30:00Z", "July 8th, 1 BES"},
                {"2026-11-15T23:59:00Z", "December 30th, 904 AES"}, {"2026-11-12T22:30:00Z", "December 8th, 901 AES"}, {"2026-11-12T01:59:59Z", "January 30th, 901 AES"},
                {"2026-12-01T05:21:00Z", "March 21st, 920 AES"}, {"2025-02-03T13:44:00Z", "July 27th, 254 AES"}, {"2026-11-12T03:11:00Z", "February 18th, 901 AES"},
                {"2026-11-12T11:23:00Z", "June 21st, 901 AES"}};
        boolean same = true;
        for (String[] s : site) if (!gaia.format(Instant.parse(s[0])).equals(s[1])) { System.out.println("     " + s[0] + " -> " + gaia.format(Instant.parse(s[0])) + ", the site says " + s[1]); same = false; }
        check("dates match the site's world clock", same);
        check("another calendar from the config", WorldClock.of("2020-01-01", "AB", "BB").format(Instant.parse("2020-01-02T02:00:00Z")).equals("February 1st, 1 AB"));
        check("a bad year-zero falls back to the default calendar", WorldClock.of("not a date", null, null).equals(WorldClock.DEFAULT) && WorldClock.of(null, null, null).equals(WorldClock.DEFAULT));
        check("spans in the world's units", "11 years".equals(WorldClock.span(java.time.Duration.ofDays(11))) && "1 year".equals(WorldClock.span(java.time.Duration.ofDays(1)))
                && "5 months".equals(WorldClock.span(java.time.Duration.ofHours(11))) && "1 month".equals(WorldClock.span(java.time.Duration.ofHours(2)))
                && "3 days".equals(WorldClock.span(java.time.Duration.ofMinutes(13))) && WorldClock.span(java.time.Duration.ofMinutes(3)) == null);

        // the widths of the default font, to place the menu's text: "Hello" = H 5, e 5, l 2, l 2, o 5, plus one after each
        check("text width", FontWidths.width("Hello") == 6 + 6 + 3 + 3 + 6 && FontWidths.width("") == 0 && FontWidths.width(" ") == 4 && FontWidths.width("...") == 6);
        check("accents dropped, other characters are a question mark", FontWidths.ascii("Ação Santana × Cumania 中").equals("Acao Santana x Cumania ?"));
        List<String> wrapped = FontWidths.wrap("Greneshire and Cumania declare war on Lombriga and Santana", 120, 2);
        check("a headline wraps in two lines that fit", wrapped.size() == 2 && wrapped.stream().allMatch(s -> FontWidths.width(s) <= 120) && wrapped.get(0).startsWith("Greneshire and"));
        List<String> cut = FontWidths.wrap("The Northern Pact is formed by Gaia, Anthropia, Indiana and Cumania and many others", 120, 2);
        check("too long: the last line ends in three dots and fits", cut.size() == 2 && cut.get(1).endsWith("...") && FontWidths.width(cut.get(1)) <= 120);
        check("one word longer than a line is cut", FontWidths.wrap("W".repeat(40), 120, 3).stream().allMatch(s -> FontWidths.width(s) <= 120) && FontWidths.wrap("", 120, 2).isEmpty());

        // flags before the names of countries and alliances (the characters come from flags.json, built with the pack)
        Flags flags = new Flags(java.util.Map.of("Dorne", 0xE300, "Mesea", 0xE301, "Platine League", 0xE302, "Platine", 0xE303));
        List<Flags.Part> flagged = flags.split("Dorne declares war on Mesea");
        check("a flag before each name, the name stays in the text", flagged.size() == 4 && flagged.get(0).flag() && flagged.get(0).text().equals("") && flagged.get(1).text().equals("Dorne declares war on ")
                && flagged.get(2).flag() && flagged.get(2).text().equals("") && flagged.get(3).text().equals("Mesea"));
        check("the longest name wins", flags.split("The Platine League").get(1).text().equals(""));
        check("only whole words: Dornes is not Dorne", flags.split("Dornes and Mesean").size() == 1);
        check("a text with no name is one part, and so is an empty list of flags", flags.split("Peace at last.").size() == 1 && new Flags(java.util.Map.of()).split("Dorne").size() == 1);
        check("a name at the very start and the very end", flags.split("Mesea").size() == 2 && flags.split("Mesea").get(1).text().equals("Mesea") && !flags.split("").iterator().hasNext());
        check("the plugin's own flags.json is readable", Flags.load().split("Greneshire").size() == 2);

        // the menu's filters: every kind of news belongs to exactly one filter, the Staff's posts to none
        boolean once = true;
        for (News.Type type : News.Type.values()) {
            int in = 0;
            for (NewsFilter fl : NewsFilter.values()) if (fl.matches(type)) in++;
            once &= in == (type == News.Type.POST || type == News.Type.BREAKING ? 0 : 1);
        }
        check("each kind of news is in one filter, posts in none", once);

        // the Staff's posts: the map's news.json (/news post of the site's Discord bot)
        final String SITE = "https://bmcmc.online/gaia/territories.json";
        String one = "{\"news\": [{\"id\": \"2026-11-12-FordClosed\", \"title\": \"Greneshire closes the Ford\", \"text\": \"Caravans must go round.\", \"posted\": \"2026-11-12T11:00:00Z\"}]}";
        String two = one.replace("}]}", "}, {\"id\": \"2026-11-12-Reopened\", \"title\": \"The Ford reopens\", \"text\": \"Trade is back.\"}, {\"id\": \"x\"}]}");
        List<NewsDiff.Post> posts1 = NewsDiff.parsePosts(one), posts2 = NewsDiff.parsePosts(two);
        check("news.json read; a post without a title is left out", posts1.size() == 1 && posts2.size() == 2);
        check("no list / empty file: no posts", NewsDiff.parsePosts("{}").isEmpty() && NewsDiff.parsePosts("").isEmpty());
        l = NewsDiff.posts(NewsDiff.postFacts(posts1), posts2, SITE);
        check("a new post is news, with its text", one(l, News.Type.BREAKING, "The Ford reopens") && "Trade is back.".equals(l.get(0).notes()));
        check("a post without a picture has none", l.get(0).image() == null);
        String staff = one.replace("}]}", "}, {\"id\": \"s\", \"title\": \"Restart at 8\", \"text\": \"t\", \"kind\": \"staff\"}]}");
        check("a post marked staff is a Staff notice", NewsDiff.posts(NewsDiff.postFacts(posts1), NewsDiff.parsePosts(staff), SITE).get(0).type() == News.Type.POST);
        String pic = one.replace("}]}", "}, {\"id\": \"p\", \"title\": \"Siege\", \"text\": \"t\", \"image\": \"gaia/news/p.png\"}]}");
        l = NewsDiff.posts(NewsDiff.postFacts(posts1), NewsDiff.parsePosts(pic), SITE);
        check("a post's picture is a full address on the site", "https://bmcmc.online/gaia/news/p.png".equals(l.get(0).image()));
        check("a full picture address is kept", "https://x.org/a.png".equals(NewsDiff.imageUrl(SITE, "https://x.org/a.png")));
        check("no picture, or a broken one, gives null", NewsDiff.imageUrl(SITE, " ") == null && NewsDiff.imageUrl(SITE, "a b|c") == null);
        check("a post deleted is not news", NewsDiff.posts(NewsDiff.postFacts(posts2), posts1, SITE).isEmpty());
        Path pdir = Files.createTempDirectory("newsposts");
        NewsStore ps = new NewsStore(pdir.resolve("news.json"));
        check("first reading of a map's posts only remembers them", ps.observePosts("gaia", SITE, posts1, now, 50).isEmpty());
        check("the site's first download is remembered apart from the posts", ps.observe("gaia", base, now, 50).isEmpty());
        l = ps.observePosts("gaia", SITE, posts2, now, 50);
        check("then a new post is news, on its map", l.size() == 1 && l.get(0).map().equals("gaia") && l.get(0).id() == 1);
        check("the site's data does not undo the posts' memory", ps.observe("gaia", base, now, 50).isEmpty() && ps.observePosts("gaia", SITE, posts2, now, 50).isEmpty());

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
        check("subjects, told", tells(l.get(0), shown, "Gaia") && tells(diff(f, base, now).get(0), shown, "Gaia"));

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
        again.maxAgeDays(1);
        again.observe("gaia", f, now.plusSeconds(2 * 86_400), 10);
        check("news older than the age limit is dropped", again.latest(10).size() == 1 && again.latest(10).get(0).id() == 5);
        Files.writeString(dir.resolve("news.json"), "{ not json");
        boolean threw = false;
        try { new NewsStore(dir.resolve("news.json")).load(); } catch (Exception e) { threw = true; }
        check("an unreadable news.json is kept aside", threw && Files.exists(dir.resolve("news.json.bad")) && !Files.exists(dir.resolve("news.json")));

        // saving: only when something changed, and old readers are forgotten
        Path quiet = Files.createTempDirectory("newsquiet").resolve("news.json");
        NewsStore q = new NewsStore(quiet);
        q.observe("gaia", base, now, 50);
        q.save();
        long written = Files.getLastModifiedTime(quiet).toMillis();
        Files.delete(quiet);
        q.save();
        check("nothing changed: nothing written", !Files.exists(quiet));
        q.observe("gaia", base, now, 50);
        q.save();
        check("the same download changes nothing: nothing written", !Files.exists(quiet));
        q.observe("gaia", f, now, 50);
        q.save();
        check("a news is written", Files.exists(quiet) && written > 0);
        q.markRead("old");
        for (int i = 0; i < 4; i++) { q.observe("gaia", i % 2 == 0 ? base : f, now, 2); }
        q.markRead("new");
        check("a reader from before the oldest kept news is forgotten, a recent one kept", q.unread("old") == 2 && q.unread("new") == 0);
        q.save();
        check("the forgotten reader is not in the file", !Files.readString(quiet).contains("\"old\""));

        // long texts fill several pages
        List<String> parts = News.chunks("word ".repeat(200), 228);
        check("long text cut at spaces into pages", parts.size() == 5 && parts.stream().allMatch(p -> p.length() <= 228 && !p.startsWith(" ")));
        check("a zero width does not hang", News.chunks("abc def", 0).size() == 6);
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
