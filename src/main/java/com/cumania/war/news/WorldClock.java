package com.cumania.war.news;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * The server's calendar, worked out the way the site's world clock does (js/clock.js): one real day is one year, counted from yearZero (UTC);
 * each real day's 24 hours make the year: 2 hours = a month (12 of them), 4 minutes = a day (30 per month). "March 3rd, 868 AES".
 * No Bukkit classes.
 */
public record WorldClock(LocalDate yearZero, String era, String beforeEra) {

    /** Gaia's clock in the site's timeline-config.json. shortcut: copied here, not read from the site; read it from there when territories.json carries the clock. */
    public static final WorldClock DEFAULT = new WorldClock(LocalDate.of(2024, 5, 25), "AES", "BES");

    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};

    /** The clock a config says; the default for a missing or unreadable year-zero (so a typo never stops the news). */
    public static WorldClock of(String yearZero, String era, String beforeEra) {
        LocalDate zero;
        try { zero = LocalDate.parse(yearZero == null ? "" : yearZero.strip()); } catch (RuntimeException e) { return DEFAULT; }
        return new WorldClock(zero, era == null || era.isBlank() ? DEFAULT.era : era.strip(), beforeEra == null || beforeEra.isBlank() ? DEFAULT.beforeEra : beforeEra.strip());
    }

    /** "March 3rd, 868 AES" for a real moment. */
    public String format(Instant t) {
        var utc = t.atOffset(ZoneOffset.UTC);
        long days = ChronoUnit.DAYS.between(yearZero, utc.toLocalDate());
        int month = utc.getHour() / 2, day = (utc.getHour() % 2) * 15 + utc.getMinute() / 4 + 1;
        return MONTHS[month] + " " + ordinal(day) + ", " + Math.abs(days) + " " + (days >= 0 ? era : beforeEra);
    }

    /** How long a stretch of real time is in the world: "11 years", "5 months" or "3 days" (the biggest unit that fits); null under one world day. */
    public static String span(Duration real) {
        long years = real.toDays(), months = real.toHours() / 2, days = real.toMinutes() / 4;
        if (years >= 1) return years + (years == 1 ? " year" : " years");
        if (months >= 1) return months + (months == 1 ? " month" : " months");
        if (days >= 1) return days + (days == 1 ? " day" : " days");
        return null;
    }

    private static String ordinal(int day) {
        if (day > 3 && day < 21) return day + "th";
        return day + switch (day % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; };
    }
}
