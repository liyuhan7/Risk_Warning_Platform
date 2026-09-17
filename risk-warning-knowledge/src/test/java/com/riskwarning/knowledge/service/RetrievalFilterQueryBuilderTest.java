package com.riskwarning.knowledge.service;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.riskwarning.common.dto.retrieval.RetrievalFilter;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalFilterQueryBuilderTest {
    private final RetrievalFilterQueryBuilder builder = new RetrievalFilterQueryBuilder();

    @Test
    void disabledFilterBuildsNoEsQueryButKeepsAuditExpression() {
        assertTrue(builder.build(RetrievalFilter.none()).isEmpty());
        Map<String, String> audit = builder.describe(RetrievalFilter.none());
        assertEquals("false", audit.get("enabled"));
        assertEquals("false", audit.get("applied"));
        assertNotNull(audit.get("expression"));
    }

    @Test
    void industryExpressionRequiresPureGeneralOrIntersectionOrMissing() {
        RetrievalFilter filter = RetrievalFilter.of(new LinkedHashSet<>(Arrays.asList("制造业", "数据服务")),
                Collections.emptySet());
        List<Query> queries = builder.build(filter);
        assertEquals(1, queries.size());
        String json = queries.get(0).toString();
        assertTrue(json.contains("complianceDomain"));
        assertTrue(json.contains("size()==1"));
        assertTrue(json.contains("综合类"));
        assertTrue(json.contains("must_not"));
        assertFalse(json.contains("regionCodes"));
    }

    @Test
    void regionAllowsProjectCodesAndGlobalMultiUnknownOrMissing() {
        RetrievalFilter filter = RetrievalFilter.of(Collections.emptySet(),
                new LinkedHashSet<>(Arrays.asList("CN-SH", "CN")));
        String json = builder.build(filter).get(0).toString();
        for (String value : Arrays.asList("CN", "CN-SH", "GLOBAL", "MULTI", "UNKNOWN")) {
            assertTrue(json.contains(value));
        }
        assertTrue(json.contains("regionCodes"));
        assertTrue(json.contains("must_not"));
    }

    @Test
    void missingMetadataDoesNotCreateACondition() {
        RetrievalFilter filter = RetrievalFilter.of(null, null);
        assertTrue(builder.build(filter).isEmpty());
        assertEquals("false", builder.describe(filter).get("applied"));
    }
}
