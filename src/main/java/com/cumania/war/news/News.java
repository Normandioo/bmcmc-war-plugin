package com.cumania.war.news;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One piece of diplomatic news: something that changed in the site's diplomacy between two downloads.
 * id = its number (1, 2, 3... in the order they came out), map = the war map whose site it came from, time = when it was seen (ms).
 * details = short facts (sides, members, dates); notes = the Staff's own text from the site, when there is one; image = the address of a
 * picture the Staff posted with it (shown by the BMC Atlas mod), or null. No Bukkit classes.
 */
public record News(long id, String map, Type type, String title, List<String> details, String notes, long time, String image) {

    public enum Type {
        WAR, PEACE, POST, BREAKING, ALLIANCE, ALLIANCE_JOIN, ALLIANCE_LEAVE, ALLIANCE_END, TRUCE, NAP, ULTIMATUM, VASSAL, INDEPENDENCE;

        /** The name used in the config (news.announce): "alliance-join". */
        public String key() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }
    }

    News withId(long id, String map, long time) { return new News(id, map, type, title, details, notes, time, image); }

    /**
     * Cuts a text into parts of at most max characters, at spaces when it can, so a long text fills several book pages
     * instead of being cut off at the bottom of one.
     */
    public static List<String> chunks(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        max = Math.max(1, max);   // a zero would cut nothing off and never end
        String rest = text.strip();
        while (rest.length() > max) {
            int cut = rest.lastIndexOf(' ', max);
            if (cut < max / 2) cut = max;   // one very long word: cut it
            out.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }
}
