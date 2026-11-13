package com.cumania.war.news;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * The widths of Minecraft's default font, to place a line of text exactly inside a picture (the news menu): a character is drawn as wide as
 * its glyph and the next one starts 1 pixel after. shortcut: a table of the game's widths, not read from the game; check it in the game when
 * Minecraft is updated. No Bukkit classes.
 */
public final class FontWidths {
    private FontWidths() {}

    /** The text as the default font draws it: accents dropped, any other character outside plain ASCII a "x" for the multiplication sign, else "?". */
    public static String ascii(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).toCharArray()) {
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            out.append(c >= 32 && c < 127 ? c : c == '×' ? 'x' : '?');
        }
        return out.toString();
    }

    /** Pixels a line takes: every character's width plus one. */
    public static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += glyph(s.charAt(i)) + 1;
        return w;
    }

    private static int glyph(char c) {
        if ("!,.:;i|".indexOf(c) >= 0) return 1;
        if ("'`l".indexOf(c) >= 0) return 2;
        if ("I[]t \"".indexOf(c) >= 0) return 3;
        if ("*<>(){}fk".indexOf(c) >= 0) return 4;
        if ("@~".indexOf(c) >= 0) return 6;
        return 5;
    }

    /** Cuts a text into at most maxLines lines of at most maxPx pixels, at spaces; what does not fit is left out and the last line ends in "...". */
    public static List<String> wrap(String text, int maxPx, int maxLines) {
        List<String> out = new ArrayList<>();
        String line = "";
        for (String word : text == null ? new String[0] : text.strip().split("\\s+")) {
            if (word.isEmpty()) continue;
            String joined = line.isEmpty() ? word : line + " " + word;
            if (width(joined) <= maxPx) { line = joined; continue; }
            if (!line.isEmpty()) out.add(line);
            while (width(word) > maxPx) {   // one very long word: cut it
                int k = 1;
                while (k < word.length() && width(word.substring(0, k + 1)) <= maxPx) k++;
                out.add(word.substring(0, k));
                word = word.substring(k);
            }
            line = word;
        }
        if (!line.isEmpty()) out.add(line);
        if (out.size() <= maxLines) return out;
        List<String> cut = new ArrayList<>(out.subList(0, maxLines));
        String last = cut.get(maxLines - 1);
        while (!last.isEmpty() && width(last + "...") > maxPx) last = last.substring(0, last.length() - 1);
        cut.set(maxLines - 1, last.stripTrailing() + "...");
        return cut;
    }
}
