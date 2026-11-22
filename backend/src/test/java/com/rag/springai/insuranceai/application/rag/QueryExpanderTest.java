package com.rag.springai.insuranceai.application.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryExpanderTest {

    private final QueryExpander queryExpander = new QueryExpander();

    @Test
    void returnsNothingWhenDisabled() {
        List<String> expanded = queryExpander.expand("water damage from a burst pipe", false, 3);

        assertTrue(expanded.isEmpty());
    }

    @Test
    void returnsNothingWhenMaxExpandedTermsIsZero() {
        List<String> expanded = queryExpander.expand("water damage from a burst pipe", true, 0);

        assertTrue(expanded.isEmpty());
    }

    @Test
    void returnsKnownSynonymsWhenEnabled() {
        List<String> expanded = queryExpander.expand("burst pipe damage", true, 10);

        assertTrue(expanded.contains("broken") || expanded.contains("ruptured"),
                "burst has known synonyms in the PoC map");
        assertTrue(expanded.contains("harm") || expanded.contains("loss"), "damage has known synonyms in the PoC map");
    }

    @Test
    void neverExceedsMaxExpandedTerms() {
        List<String> expanded = queryExpander.expand("damage coverage burst pipe theft fire flood claim", true, 2);

        assertEquals(2, expanded.size());
    }

    @Test
    void isDeterministicForTheSameInput() {
        List<String> first = queryExpander.expand("burst pipe damage", true, 5);
        List<String> second = queryExpander.expand("burst pipe damage", true, 5);

        assertEquals(first, second);
    }

    @Test
    void returnsEmptyWhenNoWordHasAKnownSynonym() {
        List<String> expanded = queryExpander.expand("what is the maximum altitude", true, 5);

        assertTrue(expanded.isEmpty());
    }
}
