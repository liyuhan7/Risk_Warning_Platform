package com.riskwarning.common.dto.retrieval;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalFilterTest {

    @Test
    void noneReturnsFullyDisabledFilter() {
        RetrievalFilter filter = RetrievalFilter.none();
        assertFalse(filter.isEnabled());
        assertTrue(filter.getEnterpriseIndustryTags().isEmpty());
        assertTrue(filter.getProjectRegionCodes().isEmpty());
        assertFalse(filter.isEnabled());
    }

    @Test
    void ofEnablesAndCopiesSets() {
        RetrievalFilter filter = RetrievalFilter.of(
                new LinkedHashSet<>(Arrays.asList("ELECTRONICS", "综合类")),
                new LinkedHashSet<>(Arrays.asList("CN", "GLOBAL")));
        assertTrue(filter.isEnabled());
        assertEquals(2, filter.getEnterpriseIndustryTags().size());
        assertEquals(2, filter.getProjectRegionCodes().size());
    }

    @Test
    void ofWithNullArgsTreatsAsMissingAndDoesNotFilter() {
        RetrievalFilter filter = RetrievalFilter.of(null, null);
        assertTrue(filter.getEnterpriseIndustryTags().isEmpty());
        assertTrue(filter.getProjectRegionCodes().isEmpty());
    }

    @Test
    void ofDefensivelyCopiesSoSourceSetMutationIsIsolated() {
        LinkedHashSet<String> industry = new LinkedHashSet<>(Arrays.asList("ELECTRONICS"));
        LinkedHashSet<String> regions = new LinkedHashSet<>(Arrays.asList("CN"));
        RetrievalFilter filter = RetrievalFilter.of(industry, regions);
        industry.add("MACHINERY");
        regions.add("EU");
        assertEquals(1, filter.getEnterpriseIndustryTags().size());
        assertEquals(1, filter.getProjectRegionCodes().size());
    }

    @Test
    void ofReturnsUnmodifiableSets() {
        RetrievalFilter filter = RetrievalFilter.of(
                new LinkedHashSet<>(Arrays.asList("SOFTWARE")),
                new LinkedHashSet<>(Arrays.asList("US")));
        assertThrows(UnsupportedOperationException.class,
                () -> filter.getEnterpriseIndustryTags().add("X"));
        assertThrows(UnsupportedOperationException.class,
                () -> filter.getProjectRegionCodes().add("X"));
    }

    @Test
    void ofIsDeterministicForSameInputs() {
        RetrievalFilter first = RetrievalFilter.of(
                new LinkedHashSet<>(Arrays.asList("ELECTRONICS")),
                new LinkedHashSet<>(Arrays.asList("CN")));
        RetrievalFilter second = RetrievalFilter.of(
                new LinkedHashSet<>(Arrays.asList("ELECTRONICS")),
                new LinkedHashSet<>(Arrays.asList("CN")));
        assertEquals(first, second);
        assertEquals("filter-v1", RetrievalFilter.FILTER_VERSION);
    }
}