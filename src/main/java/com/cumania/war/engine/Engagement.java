package com.cumania.war.engine;

import java.util.Map;
import java.util.Set;

/**
 * An Engagement (7.10): the only time fighting, and so Capturing, may happen in a war (7.8.8).
 * targets: the cities it is fought over, as city keys; empty = the whole Theater (a Strategic Campaign, 7.12).
 * endsAt: the war's engine clock when it is over (Double.POSITIVE_INFINITY = until stopped).
 * restore: for a Raid, the targets' icons when it began, put back when it ends (7.17.13).
 * waiting: opened but not begun, until everyone is ready (the ready check): nothing can be captured and its clock does not run yet.
 * minutes: how long it lasts once it begins (0 = until stopped).
 */
public record Engagement(Kind kind, Set<String> targets, double startedAt, double endsAt, Map<String, Integer> restore, String by,
                         boolean waiting, double minutes) {

    public enum Kind {
        STRATEGIC("Strategic Campaign", 60), TACTICAL("Tactical Campaign", 40), ASSAULT("Assault", 20), RAID("Raid", 30), FREE("Free fighting", 0);
        public final String label;
        /** The Laws' longest duration in minutes (7.12.3, 7.13.9, 7.16.7, 7.17.12); 0 = none. */
        public final int lawMinutes;
        Kind(String label, int lawMinutes) { this.label = label; this.lawMinutes = lawMinutes; }
    }

    /** The same Engagement, begun now (its clock starts). */
    public Engagement begun(double now) {
        return new Engagement(kind, targets, now, minutes > 0 ? now + minutes * 60 : Double.POSITIVE_INFINITY, restore, by, false, minutes);
    }

    public boolean covers(String cityKey) { return targets.isEmpty() || targets.contains(cityKey); }
}
