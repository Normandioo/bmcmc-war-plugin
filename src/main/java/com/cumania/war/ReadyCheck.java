package com.cumania.war;

import com.cumania.war.engine.Engagement;
import com.cumania.war.site.SiteData;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When an Engagement starts, everyone fighting in that war is asked on screen (a title, not the chat) whether they are ready, and
 * answers by typing "ready" or "not ready" in the chat. The answer is kept out of the public chat, confirmed on their screen and
 * reported to the staff (bmcwar.admin). Those who have not answered are asked again every few seconds for a while.
 * With engagement.ready-check.wait-for-all (the default), the Engagement is held until everyone asked has said "ready": nothing is
 * captured and its clock does not run until then. Those not ready keep being asked; someone who leaves no longer holds it up; the
 * staff can begin it anyway with /bmcwar engagement go.
 */
final class ReadyCheck implements Listener {
    private record Asked(String warId, int side, String name) {}

    /** One war's ready check: everyone asked, what they answered, and the task that reminds them. */
    private static final class Check {
        final SiteData.War war;
        final Engagement.Kind kind;
        final Map<UUID, Asked> asked = new LinkedHashMap<>();
        final Map<UUID, Boolean> answers = new HashMap<>();   // player -> ready?
        BukkitTask reminder;
        long closesAt;   // when it stops asking (System.currentTimeMillis())
        Check(SiteData.War war, Engagement.Kind kind) { this.war = war; this.kind = kind; }
        void stopReminding() { if (reminder != null) { reminder.cancel(); reminder = null; } }
    }

    private final BmcWarPlugin plugin;
    private final Map<String, Check> checks = new HashMap<>();   // war key -> its check
    /** Players still to answer -> war key -> what they were asked about. Read from the chat thread, so concurrent. */
    private final Map<UUID, Map<String, Asked>> pending = new ConcurrentHashMap<>();

    ReadyCheck(BmcWarPlugin plugin) { this.plugin = plugin; }

    private void ask(UUID id, Asked a) { pending.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(a.warId(), a); }

    private void unask(UUID id, String key) {
        pending.computeIfPresent(id, (k, m) -> { m.remove(key); return m.isEmpty() ? null : m; });
    }

    private boolean asking(UUID id, String key) {
        Map<String, Asked> m = pending.get(id);
        return m != null && m.containsKey(key);
    }

    /** A war's key here: its map and id (two maps may have wars of the same id). */
    private String key(SiteData.War war) { WarMap m = plugin.mapOf(war); return (m == null ? "" : m.name) + ":" + war.id(); }

    /**
     * The side this player is asked for in this war, or -1. Someone standing in another war map's world (an event map, say) is not
     * asked about this war: they are busy with the war over there.
     */
    private int asks(Player p, SiteData site, SiteData.War war) {
        WarMap here = plugin.mapAt(p);
        if (here != null && here != plugin.mapOf(war)) return -1;
        return plugin.sideOf(p, site, war);
    }

    private boolean enabled() { return plugin.getConfig().getBoolean("engagement.ready-check.enabled", true); }

    /** Whether a new Engagement waits for everyone to be ready before it begins. */
    boolean holds() { return enabled() && plugin.getConfig().getBoolean("engagement.ready-check.wait-for-all", true); }

    private boolean waiting(String key) {
        Check c = checks.get(key);
        Engagement e = c == null ? null : plugin.engine(c.war).engagement(c.war);
        return e != null && e.waiting();
    }

    /** Asks everyone with a side in this war whether they are ready. */
    void start(SiteData.War war, Engagement e) {
        stop(war);
        if (!enabled()) return;
        SiteData site = plugin.site(war);
        if (site == null) return;
        String key = key(war);
        Check c = new Check(war, e.kind());
        for (Player p : Bukkit.getOnlinePlayers()) {
            int side = asks(p, site, war);
            if (side < 0) continue;
            Asked a = new Asked(key, side, p.getName());
            c.asked.put(p.getUniqueId(), a);
            ask(p.getUniqueId(), a);
            show(p, war, c.kind, false);
        }
        checks.put(key, c);
        long every = Math.max(5, plugin.getConfig().getLong("engagement.ready-check.remind-every-seconds", 20));
        long within = Math.max(0, plugin.getConfig().getLong("engagement.ready-check.answer-within-seconds", 120));
        long started = System.currentTimeMillis();
        c.closesAt = started + within * 1000;
        c.reminder = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            boolean over = !waiting(key) && System.currentTimeMillis() - started >= within * 1000;
            for (UUID id : c.asked.keySet()) {
                if (!asking(id, key)) continue;
                if (over) { unask(id, key); continue; }
                Player player = Bukkit.getPlayer(id);
                if (player != null && player.isOnline()) show(player, war, c.kind, true);
                else unask(id, key);   // left without answering: no longer asked
            }
            if (over) {
                c.stopReminding();
                tellStaff(Component.text("[" + war.name() + "] Ready check closed. ", NamedTextColor.GOLD).append(Component.text(summary(war), NamedTextColor.WHITE)));
            }
        }, every * 20L, every * 20L);
        tellStaff(Component.text("[" + war.name() + "] Ready check sent to " + c.asked.size() + " player(s)."
                + (e.waiting() ? " The engagement begins when all of them are ready (/bmcwar engagement go to begin it anyway)." : ""), NamedTextColor.GOLD));
        if (e.waiting()) checkAll(war);   // nobody of the war online: nothing to wait for
    }

    /** When everyone still asked has said ready, the waiting Engagement begins. */
    private void checkAll(SiteData.War war) {
        String key = key(war);
        Check c = checks.get(key);
        if (c == null || !waiting(key)) return;
        for (UUID id : c.asked.keySet()) if (!Boolean.TRUE.equals(c.answers.get(id))) return;
        begin(war, null);
    }

    /** Begins the waiting Engagement now (forcedBy = the staff member who did it, or null when everyone is ready). False when none is waiting. */
    boolean begin(SiteData.War war, String forcedBy) {
        Engagement e = plugin.engine(war).begin(war);
        if (e == null) return false;
        String key = key(war);
        Check c = checks.get(key);
        Map<UUID, Asked> who = Map.of();
        if (c != null) {
            c.stopReminding();
            c.closesAt = 0;
            who = c.asked;
            for (UUID id : who.keySet()) unask(id, key);
        }
        String why = forcedBy == null ? "Everyone is ready" : "Begun by " + forcedBy;
        for (UUID id : who.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) continue;
            p.showTitle(Title.title(Component.text(e.kind().label + " begins!", NamedTextColor.RED),
                    Component.text(why, NamedTextColor.GRAY), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
        }
        plugin.broadcast(war, Component.text(why + ": the " + e.kind().label.toLowerCase(Locale.ROOT) + " begins now"
                + (e.minutes() > 0 ? " (" + (int) e.minutes() + " min)" : "") + ".", NamedTextColor.GOLD));
        plugin.getLogger().info("[" + key + "] " + e.kind() + " begun (" + why + ")");
        plugin.log(war, "ENGAGEMENT BEGINS (" + why + ")");
        return true;
    }

    /** The Engagement is over: nothing more to ask. */
    void stop(SiteData.War war) {
        String key = key(war);
        Check c = checks.remove(key);
        if (c == null) return;
        c.stopReminding();
        for (UUID id : c.asked.keySet()) unask(id, key);
    }

    void stopAll() {
        for (Check c : checks.values()) c.stopReminding();
        checks.clear();
        pending.clear();
    }

    /** Someone who leaves is no longer asked, and drops out of the count (the check is about who is online): they no longer hold it up. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pending.remove(id);
        for (Check c : new ArrayList<>(checks.values())) {
            if (c.asked.remove(id) == null) continue;
            c.answers.remove(id);
            tellStaff(Component.text("[" + c.war.name() + "] " + event.getPlayer().getName() + " left the server and is out of the ready check.", NamedTextColor.GRAY));
            Bukkit.getScheduler().runTask(plugin, () -> checkAll(c.war));
        }
    }

    /** Someone with a side who comes online while a ready check is still asking is asked too. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {   // a moment after joining, so the title is not lost in the loading screen
            if (!p.isOnline()) return;
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Check> e : checks.entrySet()) {
                Check c = e.getValue();
                SiteData site = plugin.site(c.war);
                if (site == null) continue;
                if (!waiting(e.getKey()) && c.closesAt <= now) continue;   // while waiting, everyone who comes is asked
                if (c.asked.containsKey(p.getUniqueId())) continue;
                int side = asks(p, site, c.war);
                if (side < 0) continue;
                Asked a = new Asked(e.getKey(), side, p.getName());
                c.asked.put(p.getUniqueId(), a);
                ask(p.getUniqueId(), a);
                show(p, c.war, c.kind, false);
            }
        }, 60L);
    }

    private void show(Player p, SiteData.War war, Engagement.Kind kind, boolean again) {
        Component title = Component.text(kind.label + (again ? "" : " started"), NamedTextColor.GOLD);
        Component sub = Component.text("Are you ready? Type ", NamedTextColor.YELLOW)
                .append(Component.text("ready", NamedTextColor.GREEN)).append(Component.text(" or ", NamedTextColor.YELLOW))
                .append(Component.text("not ready", NamedTextColor.RED)).append(Component.text(" in the chat", NamedTextColor.YELLOW));
        p.showTitle(Title.title(title, sub, Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(again ? 5 : 8), Duration.ofMillis(800))));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        Map<String, Asked> mine = pending.get(p.getUniqueId());
        if (mine == null || mine.isEmpty()) return;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").replaceAll("\\s+", " ");
        Boolean ready = switch (text) {
            case "ready", "im ready", "i am ready" -> Boolean.TRUE;
            case "not ready", "notready", "im not ready", "i am not ready" -> Boolean.FALSE;
            default -> null;
        };
        if (ready == null) return;   // anything else is ordinary chat
        event.setCancelled(true);     // the answer stays out of the public chat
        List<Asked> asks = List.copyOf(mine.values());   // one answer goes to every war the player was asked about
        Bukkit.getScheduler().runTask(plugin, () -> { for (Asked a : asks) answer(p, a, ready); });
    }

    private void answer(Player p, Asked a, boolean ready) {
        UUID id = p.getUniqueId();
        if (!p.isOnline() || !asking(id, a.warId())) return;
        if (ready || !waiting(a.warId())) unask(id, a.warId());   // while waiting, someone not ready keeps being asked and can say ready later
        Check c = checks.get(a.warId());
        if (c == null) return;
        c.answers.put(id, ready);
        p.showTitle(Title.title(Component.text(ready ? "Ready" : "Not ready", ready ? NamedTextColor.GREEN : NamedTextColor.RED),
                Component.text(waiting(a.warId()) ? (ready ? "Waiting for the others..." : "Type ready when you are.") : "The staff has been told.", NamedTextColor.GRAY), Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
        String side = a.side() < c.war.sides().size() ? c.war.sides().get(a.side()).name() : "side " + a.side();
        plugin.getLogger().info("[" + a.warId() + "] " + a.name() + " (" + side + ") is " + (ready ? "ready" : "NOT ready"));
        tellStaff(Component.text("[" + c.war.name() + "] ", NamedTextColor.RED)
                .append(Component.text(a.name() + " (" + side + ") is ", NamedTextColor.WHITE))
                .append(Component.text(ready ? "ready" : "not ready", ready ? NamedTextColor.GREEN : NamedTextColor.RED))
                .append(Component.text(". " + summary(c.war), NamedTextColor.GRAY)));
        checkAll(c.war);
    }

    /** "Attackers 3/5 ready, Defenders 1/2 ready; not ready: X; no answer: Y, Z". */
    String summary(SiteData.War war) {
        Check c = checks.get(key(war));
        if (c == null) return "No ready check going on.";
        List<String> parts = new ArrayList<>(), notReady = new ArrayList<>(), silent = new ArrayList<>();
        for (int s = 0; s < war.sides().size(); s++) {
            int total = 0, ready = 0;
            for (Map.Entry<UUID, Asked> e : c.asked.entrySet()) {
                if (e.getValue().side() != s) continue;
                total++;
                if (Boolean.TRUE.equals(c.answers.get(e.getKey()))) ready++;
            }
            if (total > 0) parts.add(war.sides().get(s).name() + " " + ready + "/" + total + " ready");
        }
        for (Map.Entry<UUID, Asked> e : c.asked.entrySet()) {
            Boolean ans = c.answers.get(e.getKey());
            if (ans == null) silent.add(e.getValue().name());
            else if (!ans) notReady.add(e.getValue().name());
        }
        String out = parts.isEmpty() ? "Nobody of the war was online." : String.join(", ", parts);
        if (!notReady.isEmpty()) out += "; not ready: " + String.join(", ", notReady);
        if (!silent.isEmpty()) out += "; no answer: " + String.join(", ", silent);
        return out;
    }

    private void tellStaff(Component text) {
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("bmcwar.admin")) p.sendMessage(text);
        Bukkit.getConsoleSender().sendMessage(text);
    }
}
