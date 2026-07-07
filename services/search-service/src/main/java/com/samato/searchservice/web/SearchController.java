package com.samato.searchservice.web;

import com.samato.searchservice.projection.RestaurantProjector;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.common.geo.GeoPoint;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.SearchHit;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The user-facing search endpoint.
 *
 * Capabilities:
 *   - Full-text search across name + description:    `?q=spicy%20noodles`
 *   - Filter by cuisine:                            `?cuisine=Italian`
 *   - Filter by city:                               `?city=Bangalore`
 *   - Geo-distance:                                 `?lat=12.97&lon=77.59&radiusKm=5`
 *   - Combine them:                                 `?q=thai&cuisine=Thai&city=Bangalore`
 *
 * The query is built with the **bool query** — a `must` for the text,
 * `filter` for the exact-match terms. `filter` is cacheable in OpenSearch
 * (no scoring, just yes/no) and faster than `must` for facets.
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final RestHighLevelClient osClient;

    public SearchController(RestHighLevelClient osClient) {
        this.osClient = osClient;
    }

    @GetMapping("/restaurants")
    @PreAuthorize("isAuthenticated()")
    public SearchResponse search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cuisine,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lon,
            @RequestParam(required = false) Double radiusKm,
            @RequestParam(defaultValue = "20") int size
    ) throws Exception {
        BoolQueryBuilder bool = QueryBuilders.boolQuery();
        if (q != null && !q.isBlank()) {
            bool.must(QueryBuilders.multiMatchQuery(q, "name", "description"));
        }
        if (cuisine != null && !cuisine.isBlank()) {
            bool.filter(QueryBuilders.termQuery("cuisine", cuisine));
        }
        if (city != null && !city.isBlank()) {
            bool.filter(QueryBuilders.termQuery("city", city));
        }
        if (lat != null && lon != null && radiusKm != null) {
            bool.filter(QueryBuilders.geoDistanceQuery("location")
                    .point(lat, lon)
                    .distance(radiusKm + "km"));
        }

        SearchSourceBuilder source = new SearchSourceBuilder()
                .query(bool)
                .size(size);

        SearchRequest req = new SearchRequest(RestaurantProjector.INDEX).source(source);
        org.opensearch.action.search.SearchResponse osResp = osClient.search(req, RequestOptions.DEFAULT);

        List<Map<String, Object>> hits = new ArrayList<>();
        for (SearchHit h : osResp.getHits().getHits()) {
            Map<String, Object> doc = new HashMap<>(h.getSourceAsMap());
            doc.put("_score", h.getScore());
            hits.add(doc);
        }
        return new SearchResponse(osResp.getHits().getTotalHits().value, hits);
    }

    public record SearchResponse(long total, List<Map<String, Object>> hits) {}
}
