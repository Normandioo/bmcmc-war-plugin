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

    private final BmcWarPlugin plugin;
    /** Players still to answer -> what they were asked about. Read from the chat thread, so concurrent. */
    private final Map<UUID, Asked> pending = new ConcurrentHashMap<>();
    /** war id -> player -> (side, name, ready?) in the order they answered. */
    private final Map<String, Map<UUID, Object[]>> answers = new HashMap<>();
    /** war id -> everyone asked: player -> (side, name). */
    private final Map<String, Map<UUID, Asked>> asked = new HashMap<>();
    private final Map<String, BukkitTask> reminders = new HashMap<>();
    private final Map<String, Engagement.Kind> kinds = new HashMap<>();
    private final Map<String, SiteData.War> wars = new HashMap<>();
    /** war id -> when the ready check stops asking (System.currentTimeMillis()). */
    private final Map<String, Long> closesAt = new HashMap<>();

    ReadyCheck(BmcWarPlugin plugin) { this.plugin = plugin; }

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

    private boolean waiting(String warId) {
        SiteData.War war = wars.get(warId);
        Engagement e = war == null ? null : plugin.engine(war).engagement(war);
        return e != null && e.waiting();
    }

    /** Asks everyone with a side in this war whether they are ready. */
    void start(SiteData.War war, Engagement e) {
        stop(war);
        if (!enabled()) return;
        SiteData site = plugin.site(war);
        if (site == null) return;
        Map<UUID, Asked> who = new LinkedHashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline()) continue;   // only who is on the server right now is asked
            int side = asks(p, site, war);
            if (side < 0) continue;
            Asked a = new Asked(key(war), side, p.getName());
            who.put(p.getUniqueId(), a);
            pending.put(p.getUniqueId(), a);
            show(p, war, e.kind(), false);
        }
        asked.put(key(war), who);
        answers.put(key(war), new LinkedHashMap<>());
        kinds.put(key(war), e.kind());
        wars.put(key(war), war);
        long every = Math.max(5, plugin.getConfig().getLong("engagement.ready-check.remind-every-seconds", 20));
        long within = Math.max(0, plugin.getConfig().getLong("engagement.ready-check.answer-within-seconds", 120));
        long started = System.currentTimeMillis();
        closesAt.put(key(war), started + within * 1000);
        reminders.put(key(war), Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            boolean over = !waiting(key(war)) && System.currentTimeMillis() - started >= within * 1000;
            for (Map.Entry<UUID, Asked> p : who.entrySet()) {
                if (!pending.containsKey(p.getKey())) continue;
                if (over) { pending.remove(p.getKey()); continue; }
                Player player = Bukkit.getPlayer(p.getKey());
                if (player != null && player.isOnline()) show(player, war, e.kind(), true);
                else pending.remove(p.getKey());   // left without answering: no longer asked
            }
            if (over) {
                BukkitTask t = reminders.remove(key(war));
                if (t != null) t.cancel();
                tellStaff(Component.text("[" + war.name() + "] Ready check closed. ", NamedTextColor.GOLD).append(Component.text(summary(war), NamedTextColor.WHITE)));
            }
        }, every * 20L, every * 20L));
        tellStaff(Component.text("[" + war.name() + "] Ready check sent to " + who.size() + " player(s)."
                + (e.waiting() ? " The engagement begins when all of them are ready (/bmcwar engagement go to begin it anyway)." : ""), NamedTextColor.GOLD));
        if (e.waiting()) checkAll(war);   // nobody of the war online: nothing to wait for
    }

    /** When everyone still asked has said ready, the waiting Engagement begins. */
    private void checkAll(SiteData.War war) {
        if (!waiting(key(war))) return;
        Map<UUID, Asked> who = asked.get(key(war));
        Map<UUID, Object[]> got = answers.get(key(war));
        if (who == null || got == null) return;
        for (UUID id : who.keySet()) {
            Object[] a = got.get(id);
            if (a == null || !(Boolean) a[2]) return;
        }
        begin(war, null);
    }

    /** Begins the waiting Engagement now (forcedBy = the staff member who did it, or null when everyone is ready). False when none is waiting. */
    boolean begin(SiteData.War war, String forcedBy) {
        Engagement e = plugin.engine(war).begin(war);
        if (e == null) return false;
        BukkitTask t = reminders.remove(key(war));
        if (t != null) t.cancel();
        pending.values().removeIf(a -> a.warId().equals(key(war)));
        closesAt.put(key(war), 0L);
        String why = forcedBy == null ? "Everyone is ready" : "Begun by " + forcedBy;
        Map<UUID, Asked> who = asked.getOrDefault(key(war), Map.of());
        for (UUID id : who.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) continue;
            p.showTitle(Title.title(Component.text(e.kind().label + " begins!", NamedTextColor.RED),
                    Component.text(why, NamedTextColor.GRAY), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
        }
        plugin.broadcast(war, Component.text(why + ": the " + e.kind().label.toLowerCase(Locale.ROOT) + " begins now"
                + (e.minutes() > 0 ? " (" + (int) e.minutes() + " min)" : "") + ".", NamedTextColor.GOLD));
        plugin.getLogger().info("[" + key(war) + "] " + e.kind() + " begun (" + why + ")");
        return true;
    }

    /** The Engagement is over: nothing more to ask. */
    void stop(SiteData.War war) {
        BukkitTask t = reminders.remove(key(war));
        if (t != null) t.cancel();
        pending.values().removeIf(a -> a.warId().equals(key(war)));
        asked.remove(key(war));
        answers.remove(key(war));
        kinds.remove(key(war));
        wars.remove(key(war));
        closesAt.remove(key(war));
    }

    void stopAll() {
        for (BukkitTask t : reminders.values()) t.cancel();
        reminders.clear(); pending.clear(); asked.clear(); answers.clear(); kinds.clear(); wars.clear(); closesAt.clear();
    }

    /** Someone who leaves is no longer asked, and drops out of the count (the check is about who is online): they no longer hold it up. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pending.remove(id);
        for (Map.Entry<String, Map<UUID, Asked>> e : new ArrayList<>(asked.entrySet())) {
            if (e.getValue().remove(id) == null) continue;
            Map<UUID, Object[]> got = answers.get(e.getKey());
            if (got != null) got.remove(id);
            SiteData.War war = wars.get(e.getKey());
            tellStaff(Component.text("[" + (war == null ? e.getKey() : war.name()) + "] " + event.getPlayer().getName() + " left the server and is out of the ready check.", NamedTextColor.GRAY));
            if (war != null) Bukkit.getScheduler().runTask(plugin, () -> checkAll(war));
        }
    }

    /** Someone with a side who comes online while a ready check is still asking is asked too. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {   // a moment after joining, so the title is not lost in the loading screen
            if (!p.isOnline()) return;
            long now = System.currentTimeMillis();
            for (Map.Entry<String, SiteData.War> e : wars.entrySet()) {
                SiteData site = plugin.site(e.getValue());
                if (site == null) continue;
                if (!waiting(e.getKey()) && closesAt.getOrDefault(e.getKey(), 0L) <= now) continue;   // while waiting, everyone who comes is asked
                Map<UUID, Asked> who = asked.get(e.getKey());
                if (who == null || who.containsKey(p.getUniqueId())) continue;
                int side = asks(p, site, e.getValue());
                if (side < 0) continue;
                Asked a = new Asked(e.getKey(), side, p.getName());
                who.put(p.getUniqueId(), a);
                pending.put(p.getUniqueId(), a);
                show(p, e.getValue(), kinds.get(e.getKey()), false);
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
        Asked a = pending.get(p.getUniqueId());
        if (a == null) return;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").replaceAll("\\s+", " ");
        Boolean ready = switch (text) {
            case "ready", "im ready", "i am ready" -> Boolean.TRUE;
            case "not ready", "notready", "im not ready", "i am not ready" -> Boolean.FALSE;
            default -> null;
        };
        if (ready == null) return;   // anything else is ordinary chat
        event.setCancelled(true);     // the answer stays out of the public chat
        Bukkit.getScheduler().runTask(plugin, () -> answer(p, a, ready));
    }

    private void answer(Player p, Asked a, boolean ready) {
        if (!p.isOnline() || !pending.containsKey(p.getUniqueId())) return;
        if (ready || !waiting(a.warId())) pending.remove(p.getUniqueId());   // while waiting, someone not ready keeps being asked and can say ready later
        Map<UUID, Object[]> list = answers.get(a.warId());
        if (list == null) return;
        list.put(p.getUniqueId(), new Object[]{a.side(), a.name(), ready});
        p.showTitle(Title.title(Component.text(ready ? "Ready" : "Not ready", ready ? NamedTextColor.GREEN : NamedTextColor.RED),
                Component.text(waiting(a.warId()) ? (ready ? "Waiting for the others..." : "Type ready when you are.") : "The staff has been told.", NamedTextColor.GRAY), Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
        SiteData.War war = wars.get(a.warId());
        String side = war != null && a.side() < war.sides().size() ? war.sides().get(a.side()).name() : "side " + a.side();
        plugin.getLogger().info("[" + a.warId() + "] " + a.name() + " (" + side + ") is " + (ready ? "ready" : "NOT ready"));
        tellStaff(Component.text("[" + (war == null ? a.warId() : war.name()) + "] ", NamedTextColor.RED)
                .append(Component.text(a.name() + " (" + side + ") is ", NamedTextColor.WHITE))
                .append(Component.text(ready ? "ready" : "not ready", ready ? NamedTextColor.GREEN : NamedTextColor.RED))
                .append(Component.text(war == null ? "" : ". " + summary(war), NamedTextColor.GRAY)));
        if (war != null) checkAll(war);
    }

    /** "Attackers 3/5 ready, Defenders 1/2 ready; not ready: X; no answer: Y, Z". */
    String summary(SiteData.War war) {
        Map<UUID, Asked> who = asked.get(key(war));
        Map<UUID, Object[]> got = answers.get(key(war));
        if (who == null || got == null) return "No ready check going on.";
        List<String> parts = new ArrayList<>(), notReady = new ArrayList<>(), silent = new ArrayList<>();
        for (int s = 0; s < war.sides().size(); s++) {
            int total = 0, ready = 0;
            for (Map.Entry<UUID, Asked> e : who.entrySet()) {
                if (e.getValue().side() != s) continue;
                total++;
                Object[] ans = got.get(e.getKey());
                if (ans != null && (Boolean) ans[2]) ready++;
            }
            if (total > 0) parts.add(war.sides().get(s).name() + " " + ready + "/" + total + " ready");
        }
        for (Map.Entry<UUID, Asked> e : who.entrySet()) {
            Object[] ans = got.get(e.getKey());
            if (ans == null) silent.add(e.getValue().name());
            else if (!(Boolean) ans[2]) notReady.add(e.getValue().name());
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
