/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.search.aggregations.support.values;

import org.apache.lucene.search.Scorable;
import org.elasticsearch.common.lucene.ScorerAware;
import org.elasticsearch.index.fielddata.SortingNumericDoubleValues;
import org.elasticsearch.script.AggregationScript;
import org.elasticsearch.search.aggregations.AggregationExecutionException;
import org.joda.time.ReadableInstant;

import java.io.IOException;
import java.lang.reflect.Array;
import java.time.ZonedDateTime;
import java.util.Collection;

/**
 * {@link SortingNumericDoubleValues} implementation which is based on a script
 */
public class ScriptDoubleValues extends SortingNumericDoubleValues implements ScorerAware {

    final AggregationScript script;

    public ScriptDoubleValues(AggregationScript script) {
        super();
        this.script = script;
    }

    @Override
    public boolean advanceExact(int target) throws IOException {
        script.setDocument(target);
        final Object value = script.execute();

        if (value == null) {
            return false;
        } else if (value instanceof Number) {
            resize(1);
            values[0] = ((Number) value).doubleValue();
        } else if (value instanceof ReadableInstant) {
            resize(1);
            values[0] = ((ReadableInstant) value).getMillis();
        } else if (value instanceof ZonedDateTime) {
            resize(1);
            values[0] = ((ZonedDateTime) value).toInstant().toEpochMilli();
        } else if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            if (length == 0) {
                return false;
            }
            resize(length);
            for (int i = 0; i < length; ++i) {
                values[i] = toDoubleValue(Array.get(value, i));
            }
        } else if (value instanceof Collection) {
            Collection<?> coll = (Collection<?>) value;
            if (coll.isEmpty()) {
                return false;
            }
            resize(coll.size());
            int i = 0;
            for (Object v : coll) {
                values[i++] = toDoubleValue(v);
            }
            assert i == docValueCount();
        } else {
            resize(1);
            values[0] = toDoubleValue(value);
        }

        sort();
        return true;
    }

    private static double toDoubleValue(Object o) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        } else if (o instanceof ReadableInstant) {
            // Dates are exposed in scripts as ReadableDateTimes but aggregations want them to be numeric
            return ((ReadableInstant) o).getMillis();
        } else if (o instanceof ZonedDateTime) {
            return ((ZonedDateTime) o).toInstant().toEpochMilli();
        } else if (o instanceof Boolean) {
            // We do expose boolean fields as boolean in scripts, however aggregations still expect
            // that scripts return the same internal representation as regular fields, so boolean
            // values in scripts need to be converted to a number, and the value formatter will
            // make sure of using true/false in the key_as_string field
            return ((Boolean) o).booleanValue() ? 1.0 : 0.0;
        } else {
            throw new AggregationExecutionException("Unsupported script value [" + o + "], expected a number, date, or boolean");
        }
    }

    @Override
    public void setScorer(Scorable scorer) {
        script.setScorer(scorer);
    }
}
