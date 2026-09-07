package com.riskwarning.common.po.evidence;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceChunkTest {

    @Test
    void createsStableIdFromNormalizedTextAndLocation() {
        EvidenceChunk first = EvidenceChunk.create(101L, 10L, 20L, "source.pdf", 2, 3,
                null, null, "  第一行\r\n第二行  ", LocalDateTime.of(2026, 9, 5, 10, 0));
        EvidenceChunk second = EvidenceChunk.create(101L, 10L, 20L, "source.pdf", 2, 3,
                null, null, "第一行 第二行", LocalDateTime.of(2026, 9, 5, 10, 1));

        assertEquals(first.getTextHash(), second.getTextHash());
        assertEquals(first.getId(), second.getId());
        assertEquals(32, first.getId().length());
        first.verifyIntegrity();
    }

    @Test
    void rejectsInvalidEvidenceCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> EvidenceChunk.create(
                101L, 10L, 20L, "source.pdf", 0, 0, null, null, "text", LocalDateTime.now()));
        assertThrows(IllegalArgumentException.class, () -> EvidenceChunk.create(
                101L, 10L, 20L, "source.pdf", 1, 0, 5, null, "text", LocalDateTime.now()));
    }
}
