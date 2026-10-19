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

    private boolean enabled() { return plugin.getConfig().getBoolean("engagement.ready-check.enabled", true); }

    /** Asks everyone with a side in this war whether they are ready. */
    void start(SiteData.War war, Engagement e) {
        stop(war);
        if (!enabled()) return;
        SiteData site = plugin.site();
        if (site == null) return;
        Map<UUID, Asked> who = new LinkedHashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline()) continue;   // only who is on the server right now is asked
            int side = plugin.sideOf(p, site, war);
            if (side < 0) continue;
            Asked a = new Asked(war.id(), side, p.getName());
            who.put(p.getUniqueId(), a);
            pending.put(p.getUniqueId(), a);
            show(p, war, e.kind(), false);
        }
        asked.put(war.id(), who);
        answers.put(war.id(), new LinkedHashMap<>());
        kinds.put(war.id(), e.kind());
        wars.put(war.id(), war);
        long every = Math.max(5, plugin.getConfig().getLong("engagement.ready-check.remind-every-seconds", 20));
        long within = Math.max(0, plugin.getConfig().getLong("engagement.ready-check.answer-within-seconds", 120));
        long started = System.currentTimeMillis();
        closesAt.put(war.id(), started + within * 1000);
        reminders.put(war.id(), Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            boolean over = System.currentTimeMillis() - started >= within * 1000;
            for (Map.Entry<UUID, Asked> p : who.entrySet()) {
                if (!pending.containsKey(p.getKey())) continue;
                if (over) { pending.remove(p.getKey()); continue; }
                Player player = Bukkit.getPlayer(p.getKey());
                if (player != null && player.isOnline()) show(player, war, e.kind(), true);
                else pending.remove(p.getKey());   // left without answering: no longer asked
            }
            if (over) {
                BukkitTask t = reminders.remove(war.id());
                if (t != null) t.cancel();
                tellStaff(Component.text("[" + war.name() + "] Ready check closed. ", NamedTextColor.GOLD).append(Component.text(summary(war), NamedTextColor.WHITE)));
            }
        }, every * 20L, every * 20L));
        tellStaff(Component.text("[" + war.name() + "] Ready check sent to " + who.size() + " player(s).", NamedTextColor.GOLD));
    }

    /** The Engagement is over: nothing more to ask. */
    void stop(SiteData.War war) {
        BukkitTask t = reminders.remove(war.id());
        if (t != null) t.cancel();
        pending.values().removeIf(a -> a.warId().equals(war.id()));
        asked.remove(war.id());
        answers.remove(war.id());
        kinds.remove(war.id());
        wars.remove(war.id());
        closesAt.remove(war.id());
    }

    void stopAll() {
        for (BukkitTask t : reminders.values()) t.cancel();
        reminders.clear(); pending.clear(); asked.clear(); answers.clear(); kinds.clear(); wars.clear(); closesAt.clear();
    }

    /** Someone who leaves is no longer asked, and drops out of the count (the check is about who is online). */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pending.remove(id);
        for (Map.Entry<String, Map<UUID, Asked>> e : asked.entrySet()) {
            if (e.getValue().remove(id) == null) continue;
            Map<UUID, Object[]> got = answers.get(e.getKey());
            if (got != null) got.remove(id);
            SiteData.War war = wars.get(e.getKey());
            tellStaff(Component.text("[" + (war == null ? e.getKey() : war.name()) + "] " + event.getPlayer().getName() + " left the server and is out of the ready check.", NamedTextColor.GRAY));
        }
    }

    /** Someone with a side who comes online while a ready check is still asking is asked too. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {   // a moment after joining, so the title is not lost in the loading screen
            if (!p.isOnline()) return;
            SiteData site = plugin.site();
            if (site == null) return;
            long now = System.currentTimeMillis();
            for (Map.Entry<String, SiteData.War> e : wars.entrySet()) {
                if (closesAt.getOrDefault(e.getKey(), 0L) <= now) continue;
                Map<UUID, Asked> who = asked.get(e.getKey());
                if (who == null || who.containsKey(p.getUniqueId())) continue;
                int side = plugin.sideOf(p, site, e.getValue());
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
        if (!p.isOnline() || pending.remove(p.getUniqueId()) == null) return;
        Map<UUID, Object[]> list = answers.get(a.warId());
        if (list == null) return;
        list.put(p.getUniqueId(), new Object[]{a.side(), a.name(), ready});
        p.showTitle(Title.title(Component.text(ready ? "Ready" : "Not ready", ready ? NamedTextColor.GREEN : NamedTextColor.RED),
                Component.text("The staff has been told.", NamedTextColor.GRAY), Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
        SiteData.War war = plugin.site() == null ? null : plugin.site().war(a.warId());
        String side = war != null && a.side() < war.sides().size() ? war.sides().get(a.side()).name() : "side " + a.side();
        plugin.getLogger().info("[" + a.warId() + "] " + a.name() + " (" + side + ") is " + (ready ? "ready" : "NOT ready"));
        tellStaff(Component.text("[" + (war == null ? a.warId() : war.name()) + "] ", NamedTextColor.RED)
                .append(Component.text(a.name() + " (" + side + ") is ", NamedTextColor.WHITE))
                .append(Component.text(ready ? "ready" : "not ready", ready ? NamedTextColor.GREEN : NamedTextColor.RED))
                .append(Component.text(war == null ? "" : ". " + summary(war), NamedTextColor.GRAY)));
    }

    /** "Attackers 3/5 ready, Defenders 1/2 ready; not ready: X; no answer: Y, Z". */
    String summary(SiteData.War war) {
        Map<UUID, Asked> who = asked.get(war.id());
        Map<UUID, Object[]> got = answers.get(war.id());
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
