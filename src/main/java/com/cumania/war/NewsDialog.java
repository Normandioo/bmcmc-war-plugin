package com.cumania.war;

import com.cumania.war.news.Flags;
import com.cumania.war.news.News;
import com.cumania.war.news.NewsDiff;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One news item as a Paper Dialog: the window the game itself draws, with real text (it wraps by itself and has accents) and two buttons.
 * It is how a news is read: a click on a news in the menu opens it. The resource pack dresses it: a banner on top, a divider between the parts,
 * the type's icon beside the opening text, and a flag before every country or alliance named (the pack's characters, see tools/build_pack.py).
 */
final class NewsDialog {
    private static final int TEXT_WIDTH = 300, ICON = 48;
    private static final Key DIALOG = Key.key("bmcnews", "dialog"), FLAGS = Key.key("bmcnews", "flags");
    private static final String BANNER = "", DIVIDER = "";   // the characters of the pack's dialog font

    private final BmcWarPlugin plugin;
    private final Flags flags = Flags.load();

    NewsDialog(BmcWarPlugin plugin) { this.plugin = plugin; }

    /** A text with a flag before each country or alliance it names. The flags are white (a glyph takes the text's colour) and never bold. */
    private Component rich(String text, TextColor color, boolean bold) {
        var out = Component.text();
        for (Flags.Part part : flags.split(text)) {
            if (part.flag()) out.append(Component.text(part.text(), NamedTextColor.WHITE).font(FLAGS).decoration(TextDecoration.BOLD, false));
            else out.append(Component.text(part.text(), color).decoration(TextDecoration.BOLD, bold));
        }
        return out.build();
    }

    private static DialogBody picture(String glyph, String after) {
        // the extra line is outside the pack's font, which has no glyph for a space (it showed as a small box)
        return DialogBody.plainMessage(Component.text(glyph, NamedTextColor.WHITE).font(DIALOG).append(Component.text(after).font(Key.key("minecraft", "default"))), TEXT_WIDTH);
    }

    /** The news as a window: the banner, its date, the opening text beside the type's icon, the facts, the Staff's text. Buttons: back to the list, and close. */
    void show(Player p, News n) {
        List<DialogBody> body = new ArrayList<>();
        body.add(picture(BANNER, "\n "));   // the second line leaves room under a banner taller than a line of text
        body.add(DialogBody.plainMessage(Component.text(NewsDiff.clock().format(Instant.ofEpochMilli(n.time())), NamedTextColor.GRAY), TEXT_WIDTH));
        List<String> facts = n.details();
        if (!facts.isEmpty()) body.add(DialogBody.item(NewsMenu.item(NewsMenu.icon(n.type()), "", List.of()),
                DialogBody.plainMessage(rich(facts.get(0), NamedTextColor.WHITE, false), TEXT_WIDTH - ICON - 8), false, false, ICON, ICON));
        if (facts.size() > 1) body.add(picture(DIVIDER, ""));
        for (int i = 1; i < facts.size(); i++) body.add(DialogBody.plainMessage(rich(facts.get(i), NamedTextColor.GRAY, false), TEXT_WIDTH));
        if (n.notes() != null) {
            body.add(picture(DIVIDER, ""));
            body.add(DialogBody.plainMessage(Component.text("From the Staff", NamedTextColor.GOLD, TextDecoration.BOLD), TEXT_WIDTH));
            for (String paragraph : n.notes().split("\\R+")) body.add(DialogBody.plainMessage(rich(paragraph, NamedTextColor.WHITE, false), TEXT_WIDTH));
        }
        ActionButton back = button("Back to the list", () -> plugin.menu().open(p));
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(rich(n.title(), NewsBoard.color(n.type()), true)).canCloseWithEscape(true).body(body).build())
                .type(DialogType.confirmation(back, button("Close", () -> { }))));
        p.closeInventory();   // the menu (a chest) is open when a news is clicked
        p.showDialog(dialog);
    }

    /** A button that runs something on the game's main thread when it is clicked (the dialog closes by itself). */
    private ActionButton button(String label, Runnable run) {
        return ActionButton.builder(Component.text(label)).width(150)
                .action(DialogAction.customClick((view, audience) -> Bukkit.getScheduler().runTask(plugin, run), ClickCallback.Options.builder().build())).build();
    }
}
