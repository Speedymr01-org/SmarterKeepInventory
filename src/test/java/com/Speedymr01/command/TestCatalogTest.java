package com.Speedymr01.command;

import com.Speedymr01.command.TestCatalog.Case;
import com.Speedymr01.command.TestCatalog.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCatalogTest {

    @Test
    @DisplayName("every test belongs to a declared category")
    void testsReferenceKnownCategories() {
        Set<String> ids = new HashSet<>();
        for (Category category : TestCatalog.CATEGORIES) {
            ids.add(category.id());
        }
        for (Case test : TestCatalog.TESTS) {
            assertTrue(ids.contains(test.category()),
                    "test '" + test.title() + "' has unknown category " + test.category());
        }
    }

    @Test
    @DisplayName("every category has at least one test")
    void categoriesAreNotEmpty() {
        for (Category category : TestCatalog.CATEGORIES) {
            assertTrue(TestCatalog.indicesFor(category.id()).size() > 0,
                    "category " + category.id() + " has no tests");
        }
    }

    @Test
    @DisplayName("category indices are complete, ordered and non-overlapping")
    void indicesCoverEveryTestOnce() {
        List<Integer> all = TestCatalog.allIndices();
        assertEquals(TestCatalog.TESTS.size(), all.size());

        Set<Integer> seen = new HashSet<>();
        for (Category category : TestCatalog.CATEGORIES) {
            List<Integer> indices = TestCatalog.indicesFor(category.id());
            for (int index : indices) {
                assertEquals(category.id(), TestCatalog.TESTS.get(index).category());
                assertTrue(seen.add(index), "test " + index + " assigned to more than one category");
            }
        }
        assertEquals(TestCatalog.TESTS.size(), seen.size(), "some tests are not assigned to a category");
    }

    @Test
    @DisplayName("allIndices is ascending and starts at 0")
    void allIndicesAscending() {
        List<Integer> all = TestCatalog.allIndices();
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i, all.get(i));
        }
    }

    @Test
    @DisplayName("category lookup accepts exact ids, ignoring case")
    void resolvesExactIds() {
        for (Category category : TestCatalog.CATEGORIES) {
            assertEquals(category, TestCatalog.resolveCategory(category.id()));
            assertEquals(category, TestCatalog.resolveCategory(category.id().toUpperCase()));
        }
    }

    @Test
    @DisplayName("category lookup accepts a unique prefix")
    void resolvesUniquePrefix() {
        assertEquals("falling", TestCatalog.resolveCategory("fall").id());
        assertEquals("ignition", TestCatalog.resolveCategory("i").id());
        assertEquals("persistence", TestCatalog.resolveCategory("per").id());
    }

    @Test
    @DisplayName("category lookup rejects an ambiguous prefix")
    void rejectsAmbiguousPrefix() {
        assertNull(TestCatalog.resolveCategory(""));
        assertNull(TestCatalog.resolveCategory("nonexistent"));
    }

    @Test
    @DisplayName("every test has non-empty instructions")
    void testsAreFullyPopulated() {
        for (Case test : TestCatalog.TESTS) {
            assertNotNull(test.title());
            assertTrue(!test.title().isBlank(), "blank title");
            assertTrue(!test.task().isBlank(), "blank task for " + test.title());
            assertTrue(!test.expect().isBlank(), "blank expectation for " + test.title());
        }
    }

    @Test
    @DisplayName("category labels resolve and fall back to the id")
    void labelsResolve() {
        assertEquals("Falling blocks", TestCatalog.categoryLabel("falling"));
        assertEquals("unknown-id", TestCatalog.categoryLabel("unknown-id"));
    }
}
