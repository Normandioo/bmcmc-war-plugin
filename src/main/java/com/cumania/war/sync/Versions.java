package com.cumania.war.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Version numbers ("1.2.5"), the latest GitHub release, and what the BMC Atlas mod says about itself. No Bukkit classes here. */
public final class Versions {
    private Versions() {}

    /** The latest release of the plugin and of the mod (GitHub's public releases API). */
    public static final String PLUGIN_LATEST = "https://api.github.com/repos/Normandioo/bmcmc-war-plugin/releases/latest";
    public static final String MOD_LATEST = "https://api.github.com/repos/Normandioo/BMCAtlas/releases/latest";
    public static final String MOD_PAGE = "https://github.com/Normandioo/BMCAtlas/releases/latest";

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    /** A release: its version and the page it can be downloaded from. */
    public record Release(String version, String page) {}

    /** What the mod tells the server when it opens the channel: its version, and whether it tells the player about updates itself. */
    public record ModHello(String version, boolean selfCheck) {}

    /** The first "a.b.c" in a text ("v1.2.5-build.160" -> "1.2.5", "0.18.0" -> "0.18.0"), or null. */
    public static String versionIn(String text) {
        if (text == null) return null;
        Matcher m = VERSION.matcher(text);
        return m.find() ? m.group(1) + "." + m.group(2) + "." + m.group(3) : null;
    }

    /** True when version a ("1.2.10") is newer than b ("1.2.9"). */
    public static boolean newer(String a, String b) {
        if (a == null || b == null) return false;
        Matcher x = VERSION.matcher(a), y = VERSION.matcher(b);
        if (!x.find() || !y.find()) return false;
        for (int i = 1; i <= 3; i++) {
            int p = Integer.parseInt(x.group(i)), q = Integer.parseInt(y.group(i));
            if (p != q) return p > q;
        }
        return false;
    }

    /** The release in a GitHub "releases/latest" answer, or null when it cannot be read. */
    public static Release release(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String v = versionIn(o.has("tag_name") ? o.get("tag_name").getAsString() : null);
            if (v == null) return null;
            return new Release(v, o.has("html_url") ? o.get("html_url").getAsString() : null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The mod's {"t":"hello","mod":"1.2.6","selfCheck":true}, or null for anything else. */
    public static ModHello modHello(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (!o.has("t") || !"hello".equals(o.get("t").getAsString())) return null;
            String v = versionIn(o.has("mod") ? o.get("mod").getAsString() : null);
            if (v == null) return null;
            return new ModHello(v, o.has("selfCheck") && o.get("selfCheck").getAsBoolean());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
