package com.Speedymr01.command;

import java.util.ArrayList;
import java.util.List;

/**
 * Static catalog for {@code /kitest}: the tests and their categories. Kept free of Bukkit state
 * (pure data + lookups) so it can be unit-tested without a server.
 */
final class TestCatalog {

    record Category(String id, String label) {
    }

    record Case(String category, String title, String task, String expect) {
    }

    static final List<Category> CATEGORIES = List.of(
            new Category("falling", "Falling blocks"),
            new Category("dispensers", "Dispensers"),
            new Category("explosions", "Explosions"),
            new Category("ignition", "Player ignition"),
            new Category("baseline", "Baseline / unchanged"),
            new Category("persistence", "Persistence"));

    static final List<Case> TESTS = List.of(
            new Case("falling", "Dripstone fall kill",
                    "Place pointed dripstone above an enabled player and make it fall on them (break the support block).",
                    "No keep-inventory; dripstone messages to both players."),
            new Case("falling", "Anvil squish",
                    "Drop a player-placed anvil on an enabled player.",
                    "No keep-inventory; anvil messages to both players."),
            new Case("falling", "Sand burial (suffocation)",
                    "Drop player-placed sand on an enabled player and leave them buried until they suffocate.",
                    "No keep-inventory; suffocation messages to both players."),
            new Case("falling", "Gravel burial (suffocation)",
                    "Drop player-placed gravel on an enabled player and leave them buried until they suffocate.",
                    "No keep-inventory; suffocation messages to both players."),
            new Case("dispensers", "Dispenser harming potion kill",
                    "Place a dispenser loaded with a splash Potion of Harming, fire it at an enabled player and let it kill them.",
                    "Credited to the dispenser placer; indirect message ('harming potion') to both players."),
            new Case("dispensers", "Own dispenser self-kill (no credit)",
                    "Use your own dispenser (harming splash or TNT) to kill yourself.",
                    "No credit: /ki rules apply as normal (usually items kept) and no trap message is sent."),
            new Case("dispensers", "Dispenser arrow direct kill",
                    "Place a dispenser with arrows, fire at an enabled player and kill them with a direct hit.",
                    "Credited to the dispenser placer; arrow messages to both players."),
            new Case("dispensers", "Dispenser fire charge - direct hit",
                    "Dispenser fire charge that directly hits and kills the victim.",
                    "Credited to the dispenser placer (fire message)."),
            new Case("dispensers", "Dispenser fire charge - burn to death",
                    "Dispenser fire charge; let the victim burn to death from the fire.",
                    "Fire message credited to the dispenser placer."),
            new Case("dispensers", "Dispenser fire charge - sword kill mid-burn",
                    "Light a player on fire with a dispenser fire charge, then kill them with a sword before they burn out.",
                    "Credited to the sword player, NOT the dispenser owner."),
            new Case("dispensers", "While-fighting fire death",
                    "Let the victim fight another player while a dispenser fire charge burns them to death.",
                    "Credit goes to the dispenser owner, not the player they were fighting."),
            new Case("dispensers", "While-fighting wither death",
                    "Let the victim fight another player while a dispenser launches a wither potion that kills them.",
                    "Credit goes to the dispenser owner, not the player they were fighting."),
            new Case("dispensers", "Dispenser TNT",
                    "Dispenser with TNT; blow up an enabled player.",
                    "Explosion message credited to the dispenser placer."),
            new Case("explosions", "Placed TNT (redstone primed)",
                    "Place a TNT block and prime it with a lever/pressure plate so an enabled player is caught in the blast.",
                    "Credited to the TNT placer; explosion messages to both players."),
            new Case("explosions", "TNT chain reaction",
                    "Prime a line of TNT at one end so the blast chains and kills an enabled player.",
                    "Credited to the placer for the whole chain (each primed TNT inherits the owner)."),
            new Case("explosions", "Bed explosion (Nether/End)",
                    "In the Nether or End, have an enabled player trigger a bed placed by another player.",
                    "Explosion credited to the bed placer; explosion messages to both players."),
            new Case("explosions", "Respawn anchor explosion",
                    "In the Overworld or End, have an enabled player use a charged respawn anchor placed by another player.",
                    "Explosion credited to the anchor placer; explosion messages to both players."),
            new Case("ignition", "Player fire charge ignition",
                    "Burn an enabled player to death with a thrown fire charge.",
                    "Fire message credited to the thrower."),
            new Case("ignition", "Flame bow ignition",
                    "Burn an enabled player to death with a Flame-enchanted bow.",
                    "Fire message credited to the archer."),
            new Case("ignition", "Fire Aspect ignition",
                    "Burn an enabled player to death with a Fire Aspect sword.",
                    "Fire message credited to the attacker."),
            new Case("baseline", "PvP sword kill (unchanged)",
                    "Kill an enabled player with a sword.",
                    "Items drop as normal; red \"You died and lost your inventory.\" notice; no trap messages."),
            new Case("baseline", "Mob kill (unchanged)",
                    "Let a zombie or other mob kill an enabled player.",
                    "Items kept + the green keep-inventory message; no trap messages."),
            new Case("baseline", "Keep inventory turned off",
                    "Run /keepinv off, then die to fall damage (no attacker).",
                    "Items drop; red \"You died and lost your inventory.\" notice; no trap messages."),
            new Case("baseline", "Victim quits on the death screen",
                    "Trap-kill an enabled player, have them disconnect while dead, then rejoin.",
                    "The trap kill message appears when they respawn after rejoining."),
            new Case("persistence", "Trap ownership survives restart",
                    "Place an anvil or dripstone trap, restart the server, then trigger it on an enabled player.",
                    "Still credited after the restart (traps.yml loaded)."),
            new Case("persistence", "Dispenser ownership survives restart",
                    "Place a dispenser, restart the server, then fire it at an enabled player.",
                    "Still credited after the restart (traps.yml loaded)."),
            new Case("persistence", "Autosave writes traps.yml",
                    "Place a tracked block and wait for traps.autosave-interval-seconds.",
                    "The new entry appears in plugins/SmarterKeepInventory/traps.yml without a manual save."),
            new Case("persistence", "Save on server stop",
                    "Place a tracked block, then stop the server before the autosave interval elapses.",
                    "The entry is present in traps.yml after the shutdown save.")
    );

    private TestCatalog() {
    }

    static List<Integer> allIndices() {
        List<Integer> indices = new ArrayList<>(TESTS.size());
        for (int i = 0; i < TESTS.size(); i++) {
            indices.add(i);
        }
        return indices;
    }

    static List<Integer> indicesFor(String categoryId) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < TESTS.size(); i++) {
            if (TESTS.get(i).category().equals(categoryId)) {
                indices.add(i);
            }
        }
        return indices;
    }

    /** Resolves an exact or unambiguous prefix match (case-insensitive), or null. */
    static Category resolveCategory(String raw) {
        String needle = raw.toLowerCase();
        Category prefixMatch = null;
        for (Category category : CATEGORIES) {
            if (category.id().equals(needle)) {
                return category;
            }
            if (category.id().startsWith(needle)) {
                if (prefixMatch != null) {
                    return null; // ambiguous prefix
                }
                prefixMatch = category;
            }
        }
        return prefixMatch;
    }

    static String categoryLabel(String id) {
        for (Category category : CATEGORIES) {
            if (category.id().equals(id)) {
                return category.label();
            }
        }
        return id;
    }
}
