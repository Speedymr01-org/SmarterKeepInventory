package com.Speedymr01;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMergeTest {

    @Test
    @DisplayName("missing defaults are appended in their own order")
    void appendsMissingEntries() {
        List<String> merged = ConfigMerge.mergeDistinct(List.of("STONE"), List.of("SAND", "GRAVEL"));
        assertEquals(List.of("STONE", "SAND", "GRAVEL"), merged);
    }

    @Test
    @DisplayName("existing entries are never removed or reordered")
    void keepsUserEntries() {
        List<String> existing = List.of("my_custom_block", "ANVIL");
        List<String> merged = ConfigMerge.mergeDistinct(existing, List.of("SAND", "ANVIL", "GRAVEL"));
        assertEquals(List.of("my_custom_block", "ANVIL", "SAND", "GRAVEL"), merged);
    }

    @Test
    @DisplayName("dedupe is case-insensitive")
    void dedupeIgnoresCase() {
        List<String> merged = ConfigMerge.mergeDistinct(List.of("sand", "Gravel"), List.of("SAND", "gravel", "ANVIL"));
        assertEquals(List.of("sand", "Gravel", "ANVIL"), merged);
    }

    @Test
    @DisplayName("duplicates within the defaults are only added once")
    void dedupeWithinDefaults() {
        List<String> merged = ConfigMerge.mergeDistinct(new ArrayList<>(), List.of("ANVIL", "anvil", "SAND"));
        assertEquals(List.of("ANVIL", "SAND"), merged);
    }

    @Test
    @DisplayName("empty inputs produce an empty result")
    void handlesEmptyInputs() {
        assertTrue(ConfigMerge.mergeDistinct(List.of(), List.of()).isEmpty());
        assertEquals(List.of("SAND"), ConfigMerge.mergeDistinct(List.of(), List.of("SAND")));
        assertEquals(List.of("SAND"), ConfigMerge.mergeDistinct(List.of("SAND"), List.of()));
    }

    @Test
    @DisplayName("the additions can be found by slicing at the original size")
    void additionsAreTheTailOfTheResult() {
        List<String> existing = Arrays.asList("STONE", "anvil");
        List<String> merged = ConfigMerge.mergeDistinct(existing, List.of("ANVIL", "SAND"));
        assertEquals(List.of("SAND"), merged.subList(existing.size(), merged.size()));
    }

    @Test
    @DisplayName("the input list is not mutated")
    void doesNotMutateInput() {
        List<String> existing = new ArrayList<>(List.of("STONE"));
        ConfigMerge.mergeDistinct(existing, List.of("SAND"));
        assertEquals(List.of("STONE"), existing);
    }
}
