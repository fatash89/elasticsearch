/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.vectortile.rest;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ParseField;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.core.Booleans;
import org.elasticsearch.core.CheckedFunction;
import org.elasticsearch.geometry.Rectangle;
import org.elasticsearch.index.query.AbstractQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.script.Script;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregatorFactories;
import org.elasticsearch.search.aggregations.PipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.geogrid.GeoTileUtils;
import org.elasticsearch.search.aggregations.metrics.AvgAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.CardinalityAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.MaxAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.MinAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.SumAggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.FieldAndFormat;
import org.elasticsearch.search.sort.ScriptSortBuilder;
import org.elasticsearch.search.sort.SortBuilder;
import org.elasticsearch.search.sort.SortOrder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.elasticsearch.search.internal.SearchContext.DEFAULT_TRACK_TOTAL_HITS_UP_TO;
import static org.elasticsearch.search.internal.SearchContext.TRACK_TOTAL_HITS_ACCURATE;
import static org.elasticsearch.search.internal.SearchContext.TRACK_TOTAL_HITS_DISABLED;

/**
 * Transforms a rest request in a vector tile request
 */
class VectorTileRequest {

    protected static final String INDEX_PARAM = "index";
    protected static final String FIELD_PARAM = "field";
    protected static final String Z_PARAM = "z";
    protected static final String X_PARAM = "x";
    protected static final String Y_PARAM = "y";

    protected static final ParseField GRID_PRECISION_FIELD = new ParseField("grid_precision");
    protected static final ParseField GRID_TYPE_FIELD = new ParseField("grid_type");
    protected static final ParseField EXTENT_FIELD = new ParseField("extent");
    protected static final ParseField EXACT_BOUNDS_FIELD = new ParseField("exact_bounds");

    protected enum GRID_TYPE {
        GRID,
        POINT,
        CENTROID;

        private static GRID_TYPE fromString(String type) {
            switch (type.toLowerCase(Locale.ROOT)) {
                case "grid":
                    return GRID;
                case "point":
                    return POINT;
                case "centroid":
                    return CENTROID;
                default:
                    throw new IllegalArgumentException("Invalid grid type [" + type + "]");
            }
        }
    }

    protected static class Defaults {
        public static final int SIZE = 10000;
        public static final List<FieldAndFormat> FETCH = emptyList();
        public static final Map<String, Object> RUNTIME_MAPPINGS = emptyMap();
        public static final QueryBuilder QUERY = null;
        public static final AggregatorFactories.Builder AGGS = null;
        public static final int GRID_PRECISION = 8;
        public static final GRID_TYPE GRID_TYPE = VectorTileRequest.GRID_TYPE.GRID;
        public static final int EXTENT = 4096;
        public static final boolean EXACT_BOUNDS = false;
        public static final int TRACK_TOTAL_HITS_UP_TO = DEFAULT_TRACK_TOTAL_HITS_UP_TO;
    }

    private static final ObjectParser<VectorTileRequest, RestRequest> PARSER;

    static {
        PARSER = new ObjectParser<>("vector-tile");
        PARSER.declareInt(VectorTileRequest::setSize, SearchSourceBuilder.SIZE_FIELD);
        PARSER.declareField(VectorTileRequest::setFieldAndFormats, (p) -> {
            List<FieldAndFormat> fetchFields = new ArrayList<>();
            while ((p.nextToken()) != XContentParser.Token.END_ARRAY) {
                fetchFields.add(FieldAndFormat.fromXContent(p));
            }
            return fetchFields;
        }, SearchSourceBuilder.FETCH_FIELDS_FIELD, ObjectParser.ValueType.OBJECT_ARRAY);
        PARSER.declareField(
            VectorTileRequest::setQueryBuilder,
            (CheckedFunction<XContentParser, QueryBuilder, IOException>) AbstractQueryBuilder::parseInnerQueryBuilder,
            SearchSourceBuilder.QUERY_FIELD,
            ObjectParser.ValueType.OBJECT
        );
        PARSER.declareField(
            VectorTileRequest::setRuntimeMappings,
            XContentParser::map,
            SearchSourceBuilder.RUNTIME_MAPPINGS_FIELD,
            ObjectParser.ValueType.OBJECT
        );
        PARSER.declareField(
            VectorTileRequest::setAggBuilder,
            AggregatorFactories::parseAggregators,
            SearchSourceBuilder.AGGS_FIELD,
            ObjectParser.ValueType.OBJECT
        );
        PARSER.declareField(
            VectorTileRequest::setSortBuilders,
            SortBuilder::fromXContent,
            SearchSourceBuilder.SORT_FIELD,
            ObjectParser.ValueType.OBJECT_ARRAY
        );
        // Specific for vector tiles
        PARSER.declareInt(VectorTileRequest::setGridPrecision, GRID_PRECISION_FIELD);
        PARSER.declareString(VectorTileRequest::setGridType, GRID_TYPE_FIELD);
        PARSER.declareInt(VectorTileRequest::setExtent, EXTENT_FIELD);
        PARSER.declareBoolean(VectorTileRequest::setExactBounds, EXACT_BOUNDS_FIELD);
        PARSER.declareField(VectorTileRequest::setTrackTotalHitsUpTo, (p) -> {
            XContentParser.Token token = p.currentToken();
            if (token == XContentParser.Token.VALUE_BOOLEAN
                || (token == XContentParser.Token.VALUE_STRING && Booleans.isBoolean(p.text()))) {
                return p.booleanValue() ? TRACK_TOTAL_HITS_ACCURATE : TRACK_TOTAL_HITS_DISABLED;
            } else {
                return p.intValue();
            }
        }, SearchSourceBuilder.TRACK_TOTAL_HITS_FIELD, ObjectParser.ValueType.VALUE);
    }

    static VectorTileRequest parseRestRequest(RestRequest restRequest) throws IOException {
        final VectorTileRequest request = new VectorTileRequest(
            Strings.splitStringByCommaToArray(restRequest.param(INDEX_PARAM)),
            restRequest.param(FIELD_PARAM),
            Integer.parseInt(restRequest.param(Z_PARAM)),
            Integer.parseInt(restRequest.param(X_PARAM)),
            Integer.parseInt(restRequest.param(Y_PARAM))
        );
        if (restRequest.hasContentOrSourceParam()) {
            try (XContentParser contentParser = restRequest.contentOrSourceParamParser()) {
                PARSER.parse(contentParser, request, restRequest);
            }
        }
        // Following the same strategy of the _search API, some parameters can be defined in the body or as URL parameters.
        // URL parameters takes precedence so we check them here.
        if (restRequest.hasParam(SearchSourceBuilder.SIZE_FIELD.getPreferredName())) {
            request.setSize(restRequest.paramAsInt(SearchSourceBuilder.SIZE_FIELD.getPreferredName(), Defaults.SIZE));
        }
        if (restRequest.hasParam(GRID_PRECISION_FIELD.getPreferredName())) {
            request.setGridPrecision(restRequest.paramAsInt(GRID_PRECISION_FIELD.getPreferredName(), Defaults.GRID_PRECISION));
        }
        if (restRequest.hasParam(EXTENT_FIELD.getPreferredName())) {
            request.setExtent(restRequest.paramAsInt(EXTENT_FIELD.getPreferredName(), Defaults.EXTENT));
        }
        if (restRequest.hasParam(GRID_TYPE_FIELD.getPreferredName())) {
            request.setGridType(restRequest.param(GRID_TYPE_FIELD.getPreferredName(), Defaults.GRID_TYPE.name()));
        }
        if (restRequest.hasParam(EXACT_BOUNDS_FIELD.getPreferredName())) {
            request.setExactBounds(restRequest.paramAsBoolean(EXACT_BOUNDS_FIELD.getPreferredName(), Defaults.EXACT_BOUNDS));
        }
        if (restRequest.hasParam(SearchSourceBuilder.TRACK_TOTAL_HITS_FIELD.getPreferredName())) {
            if (Booleans.isBoolean(restRequest.param(SearchSourceBuilder.TRACK_TOTAL_HITS_FIELD.getPreferredName()))) {
                if (restRequest.paramAsBoolean(SearchSourceBuilder.TRACK_TOTAL_HITS_FIELD.getPreferredName(), true)) {
                    request.setTrackTotalHitsUpTo(TRACK_TOTAL_HITS_ACCURATE);
                } else {
                    request.setTrackTotalHitsUpTo(TRACK_TOTAL_HITS_DISABLED);
                }
            } else {
                request.setTrackTotalHitsUpTo(
                    restRequest.paramAsInt(SearchSourceBuilder.TRACK_TOTAL_HITS_FIELD.getPreferredName(), DEFAULT_TRACK_TOTAL_HITS_UP_TO)
                );
            }
        }
        return request;
    }

    private static final String SCRIPT = ""
        + "ScriptDocValues.Geometry geometry = doc[params."
        + FIELD_PARAM
        + "];"
        + "double w = geometry.getMercatorWidth();"
        + "double h = geometry.getMercatorHeight();"
        + "return h * h + w * w;";

    private final String[] indexes;
    private final String field;
    private final int x;
    private final int y;
    private final int z;
    private final Rectangle bbox;
    private QueryBuilder queryBuilder = Defaults.QUERY;
    private Map<String, Object> runtimeMappings = Defaults.RUNTIME_MAPPINGS;
    private int gridPrecision = Defaults.GRID_PRECISION;
    private GRID_TYPE gridType = Defaults.GRID_TYPE;
    private int size = Defaults.SIZE;
    private int extent = Defaults.EXTENT;
    private AggregatorFactories.Builder aggBuilder = Defaults.AGGS;
    private List<FieldAndFormat> fields = Defaults.FETCH;
    private List<SortBuilder<?>> sortBuilders;
    private boolean exact_bounds = Defaults.EXACT_BOUNDS;
    private int trackTotalHitsUpTo = Defaults.TRACK_TOTAL_HITS_UP_TO;

    private VectorTileRequest(String[] indexes, String field, int z, int x, int y) {
        this.indexes = indexes;
        this.field = field;
        this.z = z;
        this.x = x;
        this.y = y;
        // This should validate that z/x/y is a valid combination
        this.bbox = GeoTileUtils.toBoundingBox(x, y, z);
    }

    public String[] getIndexes() {
        return indexes;
    }

    public String getField() {
        return field;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public Rectangle getBoundingBox() {
        return bbox;
    }

    public int getExtent() {
        return extent;
    }

    private void setExtent(int extent) {
        if (extent < 0) {
            throw new IllegalArgumentException("[extent] parameter cannot be negative, found [" + extent + "]");
        }
        this.extent = extent;
    }

    public boolean getExactBounds() {
        return exact_bounds;
    }

    private void setExactBounds(boolean exact_bounds) {
        this.exact_bounds = exact_bounds;
    }

    public List<FieldAndFormat> getFieldAndFormats() {
        return fields;
    }

    private void setFieldAndFormats(List<FieldAndFormat> fields) {
        this.fields = fields;
    }

    public QueryBuilder getQueryBuilder() {
        return queryBuilder;
    }

    private void setQueryBuilder(QueryBuilder queryBuilder) {
        // TODO: validation
        this.queryBuilder = queryBuilder;
    }

    public Map<String, Object> getRuntimeMappings() {
        return runtimeMappings;
    }

    private void setRuntimeMappings(Map<String, Object> runtimeMappings) {
        this.runtimeMappings = runtimeMappings;
    }

    public int getGridPrecision() {
        return gridPrecision;
    }

    private void setGridPrecision(int gridPrecision) {
        if (gridPrecision < 0) {
            throw new IllegalArgumentException("[gridPrecision] parameter cannot be negative, found [" + gridPrecision + "]");
        }
        if (gridPrecision > 8) {
            throw new IllegalArgumentException("[gridPrecision] parameter cannot be bigger than 8, found [" + gridPrecision + "]");
        }
        this.gridPrecision = gridPrecision;
    }

    public GRID_TYPE getGridType() {
        return gridType;
    }

    private void setGridType(String gridType) {
        this.gridType = GRID_TYPE.fromString(gridType);
    }

    public int getSize() {
        return size;
    }

    private void setSize(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("[size] parameter cannot be negative, found [" + size + "]");
        }
        this.size = size;
    }

    public AggregatorFactories.Builder getAggBuilder() {
        return aggBuilder;
    }

    private void setAggBuilder(AggregatorFactories.Builder aggBuilder) {
        for (AggregationBuilder aggregation : aggBuilder.getAggregatorFactories()) {
            final String type = aggregation.getType();
            switch (type) {
                case MinAggregationBuilder.NAME:
                case MaxAggregationBuilder.NAME:
                case AvgAggregationBuilder.NAME:
                case SumAggregationBuilder.NAME:
                case CardinalityAggregationBuilder.NAME:
                    break;
                default:
                    // top term and percentile should be supported
                    throw new IllegalArgumentException("Unsupported aggregation of type [" + type + "]");
            }
        }
        for (PipelineAggregationBuilder aggregation : aggBuilder.getPipelineAggregatorFactories()) {
            // should not have pipeline aggregations
            final String type = aggregation.getType();
            throw new IllegalArgumentException("Unsupported pipeline aggregation of type [" + type + "]");
        }
        this.aggBuilder = aggBuilder;
    }

    public List<SortBuilder<?>> getSortBuilders() {
        if (sortBuilders == null) {
            if (size == 0) {
                // no need to add sorting
                return List.of();
            }
            return List.of(
                new ScriptSortBuilder(
                    new Script(Script.DEFAULT_SCRIPT_TYPE, Script.DEFAULT_SCRIPT_LANG, SCRIPT, Map.of(FIELD_PARAM, getField())),
                    ScriptSortBuilder.ScriptSortType.NUMBER
                ).order(SortOrder.DESC)
            );
        } else {
            return sortBuilders;
        }
    }

    private void setSortBuilders(List<SortBuilder<?>> sortBuilders) {
        this.sortBuilders = sortBuilders;
    }

    public int getTrackTotalHitsUpTo() {
        return trackTotalHitsUpTo;
    }

    private void setTrackTotalHitsUpTo(int trackTotalHitsUpTo) {
        this.trackTotalHitsUpTo = trackTotalHitsUpTo;
    }
}
