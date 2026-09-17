package com.riskwarning.knowledge.service;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonData;
import com.riskwarning.common.dto.retrieval.RetrievalFilter;
import java.util.*;
import java.util.stream.Collectors;

/** filter-v1 前置过滤；Java 事后过滤会缩短 Top-K，因此本实现不采用该替代方案。 */
public class RetrievalFilterQueryBuilder {
    public static final String GENERAL_SCRIPT = "doc.containsKey('complianceDomain') && "
            + "doc['complianceDomain'].size()==1 && doc['complianceDomain'][0]==params.general";

    public List<Query> build(RetrievalFilter filter) {
        List<Query> queries = new ArrayList<>();
        if (filter == null || !filter.isEnabled()) { return queries; }
        if (present(filter.getEnterpriseIndustryTags())) {
            Query general = Query.of(q -> q.script(s -> s.script(script -> script.inline(i -> i
                    .lang("painless").source(GENERAL_SCRIPT).params("general", JsonData.of("综合类"))))));
            queries.add(Query.of(q -> q.bool(b -> b.minimumShouldMatch("1")
                    .should(terms("complianceDomain", filter.getEnterpriseIndustryTags()),
                            missing("complianceDomain"), general))));
        }
        if (present(filter.getProjectRegionCodes())) {
            Set<String> regions = new TreeSet<>(filter.getProjectRegionCodes());
            regions.addAll(Arrays.asList("GLOBAL", "MULTI", "UNKNOWN"));
            queries.add(Query.of(q -> q.bool(b -> b.minimumShouldMatch("1")
                    .should(terms("regionCodes", regions), missing("regionCodes")))));
        }
        return queries;
    }

    public Map<String, String> describe(RetrievalFilter filter) {
        Map<String, String> audit = new LinkedHashMap<>();
        audit.put("filterVersion", RetrievalFilter.FILTER_VERSION);
        audit.put("enabled", String.valueOf(filter != null && filter.isEnabled()));
        List<Query> intended = build(filter == null ? RetrievalFilter.none()
                : RetrievalFilter.of(filter.getEnterpriseIndustryTags(), filter.getProjectRegionCodes()));
        audit.put("expression", intended.toString());
        audit.put("applied", String.valueOf(filter != null && filter.isEnabled() && !intended.isEmpty()));
        return Collections.unmodifiableMap(audit);
    }

    private boolean present(Set<String> values) { return values != null && !values.isEmpty(); }
    private Query missing(String field) {
        return Query.of(q -> q.bool(b -> b.mustNot(n -> n.exists(e -> e.field(field)))));
    }
    private Query terms(String field, Set<String> values) {
        return Query.of(q -> q.terms(t -> t.field(field).terms(v -> v.value(values.stream().sorted()
                .map(co.elastic.clients.elasticsearch._types.FieldValue::of).collect(Collectors.toList())))));
    }
}
