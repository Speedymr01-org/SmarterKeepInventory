package com.Speedymr01.command;

import com.Speedymr01.command.TestCatalog.Case;
import com.Speedymr01.command.TestCatalog.Category;
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
 * Interactive in-game test checklist: /kitest prints the tests grouped by category, each category
 * (or all of them) can be started on its own, and every test is answered with Pass/Fail/Skip
 * buttons (all in chat). Results are kept in memory (per player) for the session.
 */
public class TestCommand implements CommandExecutor, TabCompleter {

    private enum Answer {
        PASS, FAIL, SKIP
    }

    private static final class Session {
        final int runId;
        /** Category id being run, or null when running every category. */
        final String category;
        /** Global TESTS indices in run order. */
        final List<Integer> tests;
        final Answer[] answers;
        int index;
        boolean active;

        Session(int runId, String category, List<Integer> tests) {
            this.runId = runId;
            this.category = category;
            this.tests = tests;
            this.answers = new Answer[tests.size()];
            this.active = true;
        }

        Answer answerFor(int globalIndex) {
            int local = tests.indexOf(globalIndex);
            return local < 0 ? null : answers[local];
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
            case "start" -> startRun(player, args);
            case "stop" -> stopRun(player);
            case "pass" -> handleAnswer(player, Answer.PASS, args);
            case "fail" -> handleAnswer(player, Answer.FAIL, args);
            case "skip" -> handleAnswer(player, Answer.SKIP, args);
            default -> printList(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return matching(Arrays.asList("start", "stop", "list"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            List<String> options = new ArrayList<>();
            options.add("all");
            for (Category category : TestCatalog.CATEGORIES) {
                options.add(category.id());
            }
            return matching(options, args[1]);
        }
        return new ArrayList<>();
    }

    private List<String> matching(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }

    // ------------------------------------------------------------------
    // List view
    // ------------------------------------------------------------------

    private void printList(Player player) {
        Session session = sessions.get(player.getUniqueId());
        player.sendMessage(Component.text("=== SmarterKeepInventory Test Checklist ===", NamedTextColor.GOLD));
        player.sendMessage(Component.text(TestCatalog.TESTS.size() + " tests in "
                + TestCatalog.CATEGORIES.size()
                + " categories. Run one category at a time or all of them.", NamedTextColor.GRAY));

        for (Category category : TestCatalog.CATEGORIES) {
            List<Integer> indices = TestCatalog.indicesFor(category.id());
            int answered = 0;
            for (int index : indices) {
                if (session != null && session.answerFor(index) != null) {
                    answered++;
                }
            }
            player.sendMessage(Component.text(category.label() + " (" + answered + "/" + indices.size() + ")",
                            NamedTextColor.GOLD, TextDecoration.BOLD)
                    .append(Component.text("  "))
                    .append(categoryButton(category)));
            for (int index : indices) {
                Answer answer = session != null ? session.answerFor(index) : null;
                String icon;
                NamedTextColor color;
                if (answer == null) {
                    icon = "• ";
                    color = NamedTextColor.GRAY;
                } else if (answer == Answer.PASS) {
                    icon = "✔ ";
                    color = NamedTextColor.GREEN;
                } else if (answer == Answer.FAIL) {
                    icon = "✘ ";
                    color = NamedTextColor.RED;
                } else {
                    icon = "» ";
                    color = NamedTextColor.YELLOW;
                }
                player.sendMessage(Component.text(icon + (index + 1) + ". " + TestCatalog.TESTS.get(index).title(), color));
            }
        }

        if (session != null && session.active) {
            player.sendMessage(Component.text("Run in progress — " + scopeLabel(session) + ", test "
                    + (session.index + 1) + "/" + session.tests.size() + ".", NamedTextColor.YELLOW));
        }

        Component startAll = Component.text("[▶ Start all]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(Component.text("Run every test in order")))
                .clickEvent(ClickEvent.runCommand("/kitest start all"));
        Component line = startAll;
        if (session != null && session.active) {
            Component stop = Component.text("[■ Stop]", NamedTextColor.GRAY)
                    .hoverEvent(HoverEvent.showText(Component.text("Stop the run and show the summary")))
                    .clickEvent(ClickEvent.runCommand("/kitest stop"));
            line = line.append(Component.text("  ")).append(stop);
        }
        player.sendMessage(line);
    }

    private Component categoryButton(Category category) {
        return Component.text("[▶ Run]", NamedTextColor.GREEN)
                .hoverEvent(HoverEvent.showText(Component.text("Run only: " + category.label())))
                .clickEvent(ClickEvent.runCommand("/kitest start " + category.id()));
    }

    // ------------------------------------------------------------------
    // Run flow
    // ------------------------------------------------------------------

    private void startRun(Player player, String[] args) {
        Session current = sessions.get(player.getUniqueId());
        if (current != null && current.active) {
            player.sendMessage(Component.text("A run is already active — stop it first (/kitest stop).",
                    NamedTextColor.RED));
            return;
        }

        String requested = args.length > 1 ? args[1] : "all";
        String categoryId = null;
        if (!requested.equalsIgnoreCase("all")) {
            Category category = TestCatalog.resolveCategory(requested);
            if (category == null) {
                player.sendMessage(Component.text("Unknown category '" + requested + "'. Options: all, "
                        + String.join(", ", TestCatalog.CATEGORIES.stream().map(Category::id).toList()),
                        NamedTextColor.RED));
                return;
            }
            categoryId = category.id();
        }

        List<Integer> indices = categoryId == null
                ? TestCatalog.allIndices()
                : TestCatalog.indicesFor(categoryId);
        if (indices.isEmpty()) {
            player.sendMessage(Component.text("That category has no tests.", NamedTextColor.RED));
            return;
        }

        Session session = new Session(runIds.incrementAndGet(), categoryId, indices);
        sessions.put(player.getUniqueId(), session);
        player.sendMessage(Component.text("Starting test run #" + session.runId + " — "
                + scopeLabel(session) + " (" + indices.size() + " tests). Good luck!", NamedTextColor.GOLD));
        printTest(player, session);
    }

    private void stopRun(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.active) {
            player.sendMessage(Component.text("No active test run. Use /kitest start [category|all].",
                    NamedTextColor.RED));
            return;
        }
        session.active = false;
        printSummary(player, session, true);
    }

    private void handleAnswer(Player player, Answer answer, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("Use the Pass/Fail/Skip buttons in chat (or /kitest start).",
                    NamedTextColor.RED));
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
            player.sendMessage(Component.text("No active test run. Use /kitest start [category|all].",
                    NamedTextColor.RED));
            return;
        }
        if (session.runId != runId) {
            player.sendMessage(Component.text("That button belongs to a previous test run.", NamedTextColor.RED));
            return;
        }
        if (index != session.index) {
            player.sendMessage(Component.text("That button is outdated — here is the current test:",
                    NamedTextColor.RED));
            printTest(player, session);
            return;
        }

        Case answered = TestCatalog.TESTS.get(session.tests.get(index));
        session.answers[index] = answer;
        session.index++;
        player.sendMessage(switch (answer) {
            case PASS -> Component.text("✔ Passed: " + answered.title(), NamedTextColor.GREEN);
            case FAIL -> Component.text("✘ Failed: " + answered.title(), NamedTextColor.RED);
            case SKIP -> Component.text("» Skipped: " + answered.title(), NamedTextColor.YELLOW);
        });

        if (session.index >= session.tests.size()) {
            session.active = false;
            printSummary(player, session, false);
        } else {
            printTest(player, session);
        }
    }

    private void printTest(Player player, Session session) {
        int position = session.index;
        Case test = TestCatalog.TESTS.get(session.tests.get(position));
        player.sendMessage(Component.text("[" + TestCatalog.categoryLabel(test.category()) + "] Test "
                + (position + 1) + "/" + session.tests.size() + " — ", NamedTextColor.GOLD)
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
        Component skip = Component.text("[» Skip]", NamedTextColor.YELLOW, TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(Component.text("Mark as skipped and continue")))
                .clickEvent(ClickEvent.runCommand("/kitest skip " + session.runId + " " + session.index));
        Component stop = Component.text("[■ Stop]", NamedTextColor.GRAY)
                .hoverEvent(HoverEvent.showText(Component.text("Stop the run and show the summary")))
                .clickEvent(ClickEvent.runCommand("/kitest stop"));
        return pass.append(Component.text("  ")).append(fail)
                .append(Component.text("  ")).append(skip)
                .append(Component.text("  ")).append(stop);
    }

    private void printSummary(Player player, Session session, boolean stoppedEarly) {
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        int untested = 0;
        for (Answer answer : session.answers) {
            if (answer == null) {
                untested++;
            } else {
                switch (answer) {
                    case PASS -> passed++;
                    case FAIL -> failed++;
                    case SKIP -> skipped++;
                }
            }
        }

        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("=== Test run #" + session.runId + " (" + player.getName() + ") — "
                + scopeLabel(session) + " — finished ===", NamedTextColor.GOLD));
        if (stoppedEarly) {
            lines.add(Component.text("(stopped early)", NamedTextColor.YELLOW));
        }
        lines.add(Component.text("Passed: ", NamedTextColor.GRAY).append(Component.text(passed, NamedTextColor.GREEN))
                .append(Component.text("  Failed: ", NamedTextColor.GRAY)).append(Component.text(failed, NamedTextColor.RED))
                .append(Component.text("  Skipped: ", NamedTextColor.GRAY)).append(Component.text(skipped, NamedTextColor.YELLOW))
                .append(Component.text("  Untested: ", NamedTextColor.GRAY)).append(Component.text(untested, NamedTextColor.YELLOW)));

        for (int position = 0; position < session.tests.size(); position++) {
            int globalIndex = session.tests.get(position);
            if (session.answers[position] == Answer.FAIL) {
                lines.add(Component.text("✘ " + (globalIndex + 1) + ". " + TestCatalog.TESTS.get(globalIndex).title(),
                        NamedTextColor.RED));
            }
        }
        for (int position = 0; position < session.tests.size(); position++) {
            int globalIndex = session.tests.get(position);
            if (session.answers[position] == Answer.SKIP) {
                lines.add(Component.text("» " + (globalIndex + 1) + ". " + TestCatalog.TESTS.get(globalIndex).title()
                        + " (skipped)", NamedTextColor.YELLOW));
            }
        }
        for (int position = 0; position < session.tests.size(); position++) {
            int globalIndex = session.tests.get(position);
            if (session.answers[position] == null) {
                lines.add(Component.text("• " + (globalIndex + 1) + ". " + TestCatalog.TESTS.get(globalIndex).title()
                        + " (untested)", NamedTextColor.YELLOW));
            }
        }
        lines.add(Component.text("Run /kitest start <category> to test another group, or /kitest for the list.",
                NamedTextColor.GRAY));

        for (Component line : lines) {
            player.sendMessage(line);
            Bukkit.getConsoleSender().sendMessage(line);
        }
    }

    private String scopeLabel(Session session) {
        return session.category == null ? "all categories" : TestCatalog.categoryLabel(session.category);
    }
}
