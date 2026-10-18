package com.Speedymr01.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Interactive in-game test checklist: /kitest prints the full list with a Start button,
 * then walks through each test one by one with Pass/Fail buttons (all in chat).
 * Results are kept in memory (per player) for the session.
 */
public class TestCommand implements CommandExecutor, TabCompleter {

    private record Test(String title, String task, String expect) {
    }

    private static final List<Test> TESTS = List.of(
            new Test("Dripstone fall kill",
                    "Place pointed dripstone above an enabled player and make it fall on them (break the support block).",
                    "No keep-inventory; dripstone messages to both players."),
            new Test("Anvil squish",
                    "Drop a player-placed anvil on an enabled player.",
                    "No keep-inventory; anvil messages to both players."),
            new Test("Falling-block crush + suffocation",
                    "Direct crush: drop sand on a player. Suffocation: trap a player inside player-placed sand/gravel until they suffocate.",
                    "Both credited; suffocation messages to both players."),
            new Test("PvP + mob deaths unchanged",
                    "Kill an enabled player with a sword. Then let a zombie kill an enabled player.",
                    "PvP: items drop, no new messages. Mob: items kept + the green keep-inventory message."),
            new Test("Victim quits on the death screen",
                    "Trap-kill an enabled player and have them disconnect while dead, then rejoin.",
                    "The trap kill message appears when they respawn after rejoining."),
            new Test("Dispenser harming potion",
                    "A player places a dispenser holding a splash Potion of Harming and fires it at an enabled player.",
                    "Credited to the dispenser placer; indirect message with 'harming potion'. Also test triggering your own dispenser: no credit."),
            new Test("Dispenser arrow direct kill",
                    "Dispenser with arrows; fire at an enabled player and kill them with a direct hit.",
                    "Credited to the dispenser placer; arrow messages to both players."),
            new Test("Dispenser fire charge - burn to death",
                    "Dispenser with a fire charge; let the victim burn to death from the fire.",
                    "Fire message credited to the dispenser placer."),
            new Test("Dispenser fire charge - direct hit",
                    "Dispenser fire charge that directly hits and kills the victim.",
                    "Credited to the dispenser placer (fire message)."),
            new Test("Sword kill mid-burn",
                    "Light a player on fire with a dispenser fire charge, then kill them with a sword before they burn out.",
                    "Credited to the sword player, NOT the dispenser owner."),
            new Test("'While fighting X' deaths",
                    "Let the victim fight another player while a dispenser fire charge burns them to death; repeat with a dispenser wither potion.",
                    "Credit goes to the dispenser owner, not the player they were fighting."),
            new Test("Dispenser TNT",
                    "Dispenser with TNT; blow up an enabled player.",
                    "Explosion message credited to the dispenser placer."),
            new Test("Player-caused ignition",
                    "Burn an enabled player to death with a thrown fire charge, a Flame bow, or a Fire Aspect sword.",
                    "Fire message credited to the igniter."),
            new Test("Restart persistence",
                    "Place traps and a dispenser, then restart the server and check plugins/SmarterKeepInventory/traps.yml.",
                    "traps.yml exists and reloads after restart; autosave fires at the configured interval; saved on server stop.")
    );

    private static final class Session {
        final int runId;
        final Boolean[] answers;
        int index;
        boolean active;

        Session(int runId) {
            this.runId = runId;
            this.answers = new Boolean[TESTS.size()];
            this.active = true;
        }
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger runIds = new AtomicInteger();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command is player-only (the buttons need a player).", NamedTextColor.RED));
            return true;
        }
        String sub = args.length == 0 ? "list" : args[0].toLowerCase();
        switch (sub) {
            case "start" -> startRun(player);
            case "stop" -> stopRun(player);
            case "pass", "fail" -> handleAnswer(player, sub.equals("pass"), args);
            default -> printList(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("start", "stop", "list");
        }
        return new ArrayList<>();
    }

    // ------------------------------------------------------------------
    // List view
    // ------------------------------------------------------------------

    private void printList(Player player) {
        Session session = sessions.get(player.getUniqueId());
        player.sendMessage(Component.text("=== SmarterKeepInventory Test Checklist ===", NamedTextColor.GOLD));
        player.sendMessage(Component.text(TESTS.size() + " tests. Click Start and answer each one in chat.", NamedTextColor.GRAY));

        for (int i = 0; i < TESTS.size(); i++) {
            Boolean answer = session != null ? session.answers[i] : null;
            String icon;
            NamedTextColor color;
            if (answer == null) {
                icon = "• ";
                color = NamedTextColor.GRAY;
            } else if (answer) {
                icon = "✔ ";
                color = NamedTextColor.GREEN;
            } else {
                icon = "✘ ";
                color = NamedTextColor.RED;
            }
            player.sendMessage(Component.text(icon + (i + 1) + ". " + TESTS.get(i).title(), color));
        }

        if (session != null && session.active) {
            player.sendMessage(Component.text("Run in progress — next test: " + (session.index + 1) + ".", NamedTextColor.YELLOW));
        }

        Component start = Component.text("[▶ Start]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(Component.text("Begin the sequential test run")))
                .clickEvent(ClickEvent.runCommand("/kitest start"));
        Component line = start;
        if (session != null && session.active) {
            Component stop = Component.text("[■ Stop]", NamedTextColor.GRAY)
                    .hoverEvent(HoverEvent.showText(Component.text("Stop the run and show the summary")))
                    .clickEvent(ClickEvent.runCommand("/kitest stop"));
            line = line.append(Component.text("  ")).append(stop);
        }
        player.sendMessage(line);
    }

    // ------------------------------------------------------------------
    // Run flow
    // ------------------------------------------------------------------

    private void startRun(Player player) {
        Session session = new Session(runIds.incrementAndGet());
        sessions.put(player.getUniqueId(), session);
        player.sendMessage(Component.text("Starting test run #" + session.runId + " — "
                + TESTS.size() + " tests. Good luck!", NamedTextColor.GOLD));
        printTest(player, session);
    }

    private void stopRun(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.active) {
            player.sendMessage(Component.text("No active test run. Use /kitest start.", NamedTextColor.RED));
            return;
        }
        session.active = false;
        printSummary(player, session, true);
    }

    private void handleAnswer(Player player, boolean passed, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("Use the Pass/Fail buttons in chat (or /kitest start).", NamedTextColor.RED));
            return;
        }
        int runId;
        int index;
        try {
            runId = Integer.parseInt(args[1]);
            index = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Invalid button data.", NamedTextColor.RED));
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.active) {
            player.sendMessage(Component.text("No active test run. Use /kitest start.", NamedTextColor.RED));
            return;
        }
        if (session.runId != runId) {
            player.sendMessage(Component.text("That button belongs to a previous test run.", NamedTextColor.RED));
            return;
        }
        if (index != session.index) {
            player.sendMessage(Component.text("That button is outdated — here is the current test:", NamedTextColor.RED));
            printTest(player, session);
            return;
        }

        Test answered = TESTS.get(index);
        session.answers[index] = passed;
        session.index++;
        player.sendMessage(passed
                ? Component.text("✔ Passed: " + answered.title(), NamedTextColor.GREEN)
                : Component.text("✘ Failed: " + answered.title(), NamedTextColor.RED));

        if (session.index >= TESTS.size()) {
            session.active = false;
            printSummary(player, session, false);
        } else {
            printTest(player, session);
        }
    }

    private void printTest(Player player, Session session) {
        int index = session.index;
        Test test = TESTS.get(index);
        player.sendMessage(Component.text("Test " + (index + 1) + "/" + TESTS.size() + " — ", NamedTextColor.GOLD)
                .append(Component.text(test.title(), NamedTextColor.WHITE, TextDecoration.BOLD)));
        player.sendMessage(Component.text("Do: ", NamedTextColor.GRAY)
                .append(Component.text(test.task(), NamedTextColor.YELLOW)));
        player.sendMessage(Component.text("Expect: ", NamedTextColor.GRAY)
                .append(Component.text(test.expect(), NamedTextColor.AQUA)));
        player.sendMessage(buttons(session));
    }

    private Component buttons(Session session) {
        Component pass = Component.text("[✔ Pass]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(Component.text("Mark as passed and continue")))
                .clickEvent(ClickEvent.runCommand("/kitest pass " + session.runId + " " + session.index));
        Component fail = Component.text("[✘ Fail]", NamedTextColor.RED, TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(Component.text("Mark as failed and continue")))
                .clickEvent(ClickEvent.runCommand("/kitest fail " + session.runId + " " + session.index));
        Component stop = Component.text("[■ Stop]", NamedTextColor.GRAY)
                .hoverEvent(HoverEvent.showText(Component.text("Stop the run and show the summary")))
                .clickEvent(ClickEvent.runCommand("/kitest stop"));
        return pass.append(Component.text("  ")).append(fail).append(Component.text("  ")).append(stop);
    }

    private void printSummary(Player player, Session session, boolean stoppedEarly) {
        int passed = 0;
        int failed = 0;
        int untested = 0;
        for (Boolean answer : session.answers) {
            if (answer == null) {
                untested++;
            } else if (answer) {
                passed++;
            } else {
                failed++;
            }
        }

        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("=== Test run #" + session.runId + " (" + player.getName() + ") finished ===", NamedTextColor.GOLD));
        if (stoppedEarly) {
            lines.add(Component.text("(stopped early)", NamedTextColor.YELLOW));
        }
        lines.add(Component.text("Passed: ", NamedTextColor.GRAY).append(Component.text(passed, NamedTextColor.GREEN))
                .append(Component.text("  Failed: ", NamedTextColor.GRAY)).append(Component.text(failed, NamedTextColor.RED))
                .append(Component.text("  Untested: ", NamedTextColor.GRAY)).append(Component.text(untested, NamedTextColor.YELLOW)));

        for (int i = 0; i < TESTS.size(); i++) {
            Boolean answer = session.answers[i];
            if (answer != null && !answer) {
                lines.add(Component.text("✘ " + (i + 1) + ". " + TESTS.get(i).title(), NamedTextColor.RED));
            }
        }
        for (int i = 0; i < TESTS.size(); i++) {
            if (session.answers[i] == null) {
                lines.add(Component.text("• " + (i + 1) + ". " + TESTS.get(i).title() + " (untested)", NamedTextColor.YELLOW));
            }
        }
        lines.add(Component.text("Run /kitest start to test again, or /kitest to see the list.", NamedTextColor.GRAY));

        for (Component line : lines) {
            player.sendMessage(line);
            Bukkit.getConsoleSender().sendMessage(line);
        }
    }
}
