package net.newminecraftservers.companion;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

/** Chat styling shared by every command, so players and the console read the same voice. */
final class Messages {
    static final TextColor BRAND = TextColor.color(0x6CCB4A);
    private static final Component PREFIX = Component.text()
        .append(Component.text("NMS", BRAND, TextDecoration.BOLD))
        .append(Component.text(" » ", NamedTextColor.DARK_GRAY))
        .build();

    private Messages() {}

    static String clean(String value) {
        if (value == null) return "";
        return value.replace('§', '?').replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
    }

    static void info(CommandSender sender, String text) {
        send(sender, Component.text(clean(text), NamedTextColor.GRAY));
    }

    static void success(CommandSender sender, String text) {
        send(sender, Component.text(clean(text), NamedTextColor.GREEN));
    }

    static void warn(CommandSender sender, String text) {
        send(sender, Component.text(clean(text), NamedTextColor.GOLD));
    }

    static void error(CommandSender sender, String text) {
        send(sender, Component.text(clean(text), NamedTextColor.RED));
    }

    static void send(CommandSender sender, Component body) {
        sender.sendMessage(PREFIX.append(body));
    }

    static void header(CommandSender sender, String title) {
        sender.sendMessage(Component.text()
            .append(Component.text("━━ ", NamedTextColor.DARK_GRAY))
            .append(Component.text(clean(title), BRAND, TextDecoration.BOLD))
            .append(Component.text(" ━━", NamedTextColor.DARK_GRAY))
            .build());
    }

    /** "Label: value" lines become a gray label and a white value; notices turn gold. */
    static void lines(CommandSender sender, List<String> lines) {
        if (lines.isEmpty()) return;
        header(sender, lines.get(0));
        for (String line : lines.subList(1, lines.size())) {
            String text = clean(line);
            if (text.startsWith("Notice: ")) {
                sender.sendMessage(Component.text("  ⚠ " + text.substring(8), NamedTextColor.GOLD));
                continue;
            }
            int colon = text.indexOf(": ");
            if (colon > 0 && colon < 40) {
                sender.sendMessage(Component.text()
                    .append(Component.text("  " + text.substring(0, colon) + ": ", NamedTextColor.GRAY))
                    .append(Component.text(text.substring(colon + 2), NamedTextColor.WHITE))
                    .build());
            } else {
                sender.sendMessage(Component.text("  " + text, NamedTextColor.GRAY));
            }
        }
    }

    static Component link(String label, String url) {
        return Component.text(label, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
            .clickEvent(ClickEvent.openUrl(url))
            .hoverEvent(HoverEvent.showText(Component.text("Open " + url, NamedTextColor.GRAY)));
    }

    static void linkLine(CommandSender sender, String label, String url) {
        send(sender, Component.text()
            .append(Component.text(label + " ", NamedTextColor.GRAY))
            .append(link(url.replaceFirst("^https://", ""), url))
            .build());
    }

    /** A command players can click to paste into chat. */
    static TextComponent command(String usage, String description) {
        String suggestion = usage.replaceAll("[<\\[].*$", "").trim() + " ";
        return Component.text()
            .append(Component.text("  " + usage, NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.suggestCommand(suggestion))
                .hoverEvent(HoverEvent.showText(Component.text("Click to type " + suggestion.trim(), NamedTextColor.GRAY))))
            .append(Component.text(" — " + description, NamedTextColor.GRAY))
            .build();
    }

    /** Big, copyable code for the MOTD step. */
    static Component code(String code) {
        return Component.text(code, NamedTextColor.YELLOW, TextDecoration.BOLD)
            .clickEvent(ClickEvent.copyToClipboard(code))
            .hoverEvent(HoverEvent.showText(Component.text("Click to copy", NamedTextColor.GRAY)));
    }
}
