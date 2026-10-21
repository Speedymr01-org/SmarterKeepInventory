package com.Speedymr01;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure list helpers shared by the config migration. */
public final class ConfigMerge {

    private ConfigMerge() {
    }

    /**
     * Returns {@code existing} plus every {@code defaults} entry that is not already present
     * (compared case-insensitively). Order is preserved and entries are never removed, so user
     * edits survive the migration.
     */
    public static List<String> mergeDistinct(List<String> existing, List<String> defaults) {
        List<String> merged = new ArrayList<>(existing);
        Set<String> known = new HashSet<>();
        for (String entry : merged) {
            known.add(entry.toUpperCase(Locale.ROOT));
        }
        for (String entry : defaults) {
            if (known.add(entry.toUpperCase(Locale.ROOT))) {
                merged.add(entry);
            }
        }
        return merged;
    }
}
