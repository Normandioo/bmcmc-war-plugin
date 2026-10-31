package com.cumania.war.site;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.List;
import java.util.Map;

/** The territories.json the site publishes (see tools/export_territories.py). Coordinates are blocks. */
public final class TerritoryFile {
    public int format;
    public String world;
    public String dimension;
    public String generated;
    public Map<String, Country> countries;
    public List<Province> provinces;
    /** Optional (mod 1.2+): the site's localities with their position. */
    public List<Locality> localities;
    /** Optional (mod 1.3+): the site's records of the people behind the countries' populations, by site name. */
    public Map<String, Player> players;
    /** Optional (mod 1.4+): wars, alliances and diplomatic timers, as the site works them out. */
    public WarData war;
    /** Optional: cities' capture limits drawn in the site's capture SVG (captureFile), in place of the province around them. */
    public List<Zone> zones;
    /** Optional: the Connection network (for In Play, 7.8.1). Without it every locality of the war counts as In Play. */
    public Network network;

    /** A city's capture limit: name + country as in localities; rings like a province's (even-odd). */
    public static final class Zone {
        public String name, country;
        public List<int[]> rings;
    }

    public static final class WarData {
        public List<WarInfo> wars;
        public List<AllianceInfo> alliances;
        public List<Truce> truces;
        public List<Ultimatum> ultimatums;
        public List<Nap> naps;
        public List<Lock> locks;
    }

    /** Times are ISO UTC ("2026-10-12T23:19:00Z"); null when not set. */
    public static final class WarInfo {
        public String id, name, type, rule, started, ended, theater, notes;
        public boolean noAllies;
        /** The site reads who holds its cities from dynmap while this is set. */
        public boolean live;
        public List<Side> sides;
        public List<Captured> captured;
        /** Staff's picks of Connections across neutral land (7.7.3-4): pairs of "Name(Country)". */
        public List<List<String>> shortCircuits;
        /** False turns the automatic Connections across neutral land off for this war. */
        public Boolean shortCircuit;
        /** Supply Capitals given by the Server (7.8.11): nation -> "Name(Country)"s. */
        public Map<String, List<String>> supplyCapitals;
        /** Bridges or Straits opened or closed in this war (7.7.10-14): "Name(Country)" -> "open" / "closed". */
        public Map<String, String> bridges;
    }

    /** The Connections between localities, Warzones, Bridges and Straits, as the site reads them from its map (js/network.js). */
    public static final class Network {
        /** "Name(Country)" of a Bridge or Strait -> what it is and whether it is drawn open. */
        public Map<String, Link> links;
        /** [a, b] = a and b are Connected; [a, b, 1] = and in the same Warzone. a or b may be a Bridge or Strait. */
        public List<List<Object>> edges;
    }

    public static final class Link {
        public String kind;   // bridge / strait
        public boolean open;
    }

    public static final class Side {
        public String name, color;   // color: "#rrggbb" when the site gives one
        public List<Member> members;
    }

    /** A nation, "@nation and subjects" or confederation as the site lists it; countries = the countries (keys of the file) it covers. */
    public static final class Member {
        public String name, note;
        public List<String> countries;
    }

    public static final class Captured {
        public String name, country, state, holder, holderKey;   // state: held / contested / uncontrolled
        public int x, z;
        /** Set by live control (not in the file): which side's colour holds it. */
        public transient Integer liveSide;
    }

    public static final class AllianceInfo {
        public String id, name, formed, officialName, color, notes, flag;
        public List<Member> members;
    }

    public static final class Truce { public String a, b, from, until; }
    public static final class Ultimatum { public String from, to, sent; }
    public static final class Nap { public String a, b, until; }
    public static final class Lock { public String nation, kind, until; }

    public static final class Player {
        /** In-game name, real-life country, last join (as the site writes it), free notes, skin picture (relative to the file). */
        public String ign, country, lastJoined, notes, skin;
        /** Immune to purging: "true" or the date it lasts until. */
        public String immune;
        /** What they are part of now ("Greneshire - Leader"), and their past affiliations. */
        public List<String> current, affiliations;
        public Purge purge;
    }

    /** Their realm's purge timer: the realm's name, its population and the days a player may stay away. */
    public static final class Purge {
        public String realm;
        public int population, days;
    }

    public static final class Country {
        public String name;
        public String color;   // "#rrggbb"
        /** Optional panel data, as the site stores it: displayName, type, officialName, formed, leader, government, demonym, ethnicity, capital. */
        public Map<String, String> info;
        public List<String> population;
        public Diplomacy diplomacy;
    }

    public static final class Diplomacy {
        public List<String> overlord, subject, allies, nap, alliances;
        public List<War> wars;
    }

    public static final class War {
        public String name;
        public String side;   // attacker / defender
    }

    public static final class Locality {
        public String name, country, kind, development, lord, ethnicity;
        public int x, z;
        public List<String> designations;
    }

    public static final class Province {
        public String id;
        public String country;
        /** Each ring is x0,z0,x1,z1,... ; a province is the even-odd union of its rings. */
        public List<int[]> rings;
    }

    public static TerritoryFile parse(String json) throws JsonSyntaxException {
        TerritoryFile f = new Gson().fromJson(json, TerritoryFile.class);
        if (f == null || f.countries == null || f.provinces == null) throw new JsonSyntaxException("not a territories file");
        if (f.format != 1) throw new JsonSyntaxException("unsupported territories format " + f.format);
        return f;
    }
}
