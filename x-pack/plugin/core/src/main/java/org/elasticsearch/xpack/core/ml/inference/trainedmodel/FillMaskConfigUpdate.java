/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.core.ml.inference.trainedmodel;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.xpack.core.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.core.ml.utils.NamedXContentObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.xpack.core.ml.inference.trainedmodel.NlpConfig.NUM_TOP_CLASSES;
import static org.elasticsearch.xpack.core.ml.inference.trainedmodel.NlpConfig.RESULTS_FIELD;

public class FillMaskConfigUpdate extends NlpConfigUpdate implements NamedXContentObject {

    public static final String NAME = FillMaskConfig.NAME;

    public static FillMaskConfigUpdate fromMap(Map<String, Object> map) {
        Map<String, Object> options = new HashMap<>(map);
        Integer numTopClasses = (Integer)options.remove(NUM_TOP_CLASSES.getPreferredName());
        String resultsField = (String)options.remove(RESULTS_FIELD.getPreferredName());

        if (options.isEmpty() == false) {
            throw ExceptionsHelper.badRequestException("Unrecognized fields {}.", options.keySet());
        }
        return new FillMaskConfigUpdate(numTopClasses, resultsField);
    }

    private static final ObjectParser<FillMaskConfigUpdate.Builder, Void> STRICT_PARSER = createParser(false);

    private static ObjectParser<FillMaskConfigUpdate.Builder, Void> createParser(boolean lenient) {
        ObjectParser<FillMaskConfigUpdate.Builder, Void> parser = new ObjectParser<>(
            NAME,
            lenient,
            FillMaskConfigUpdate.Builder::new);
        parser.declareString(FillMaskConfigUpdate.Builder::setResultsField, RESULTS_FIELD);
        parser.declareInt(FillMaskConfigUpdate.Builder::setNumTopClasses, NUM_TOP_CLASSES);
        return parser;
    }

    public static FillMaskConfigUpdate fromXContentStrict(XContentParser parser) {
        return STRICT_PARSER.apply(parser, null).build();
    }

    private final Integer numTopClasses;
    private final String resultsField;

    public FillMaskConfigUpdate(Integer numTopClasses, String resultsField) {
        this.numTopClasses = numTopClasses;
        this.resultsField = resultsField;
    }

    public FillMaskConfigUpdate(StreamInput in) throws IOException {
        this.numTopClasses = in.readOptionalInt();
        this.resultsField = in.readOptionalString();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeOptionalInt(numTopClasses);
        out.writeOptionalString(resultsField);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        if (numTopClasses != null) {
            builder.field(NUM_TOP_CLASSES.getPreferredName(), numTopClasses);
        }
        if (resultsField != null) {
            builder.field(RESULTS_FIELD.getPreferredName(), resultsField);
        }
        builder.endObject();
        return builder;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public InferenceConfig apply(InferenceConfig originalConfig) {
        if (originalConfig instanceof FillMaskConfig == false) {
            throw ExceptionsHelper.badRequestException(
                "Inference config of type [{}] can not be updated with a request of type [{}]",
                originalConfig.getName(),
                getName());
        }

        FillMaskConfig fillMaskConfig = (FillMaskConfig)originalConfig;
        if (isNoop(fillMaskConfig)) {
            return originalConfig;
        }

        FillMaskConfig.Builder builder = new FillMaskConfig.Builder(fillMaskConfig);
        if (numTopClasses != null) {
            builder.setNumTopClasses(numTopClasses);
        }
        if (resultsField != null) {
            builder.setResultsField(resultsField);
        }
        return builder.build();
    }

    boolean isNoop(FillMaskConfig originalConfig) {
        return (this.numTopClasses == null || this.numTopClasses == originalConfig.getNumTopClasses()) &&
            (this.resultsField == null || this.resultsField.equals(originalConfig.getResultsField()));
    }

    @Override
    public boolean isSupported(InferenceConfig config) {
        return config instanceof FillMaskConfig;
    }

    @Override
    public String getResultsField() {
        return resultsField;
    }

    @Override
    public InferenceConfigUpdate.Builder<? extends InferenceConfigUpdate.Builder<?, ?>, ? extends InferenceConfigUpdate> newBuilder() {
        return new Builder()
            .setNumTopClasses(numTopClasses)
            .setResultsField(resultsField);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FillMaskConfigUpdate that = (FillMaskConfigUpdate) o;
        return Objects.equals(numTopClasses, that.numTopClasses) &&
            Objects.equals(resultsField, that.resultsField);
    }

    @Override
    public int hashCode() {
        return Objects.hash(numTopClasses, resultsField);
    }

    public static class Builder
        implements InferenceConfigUpdate.Builder<FillMaskConfigUpdate.Builder, FillMaskConfigUpdate> {
        private Integer numTopClasses;
        private String resultsField;

        public FillMaskConfigUpdate.Builder setNumTopClasses(Integer numTopClasses) {
            this.numTopClasses = numTopClasses;
            return this;
        }

        @Override
        public FillMaskConfigUpdate.Builder setResultsField(String resultsField) {
            this.resultsField = resultsField;
            return this;
        }

        public FillMaskConfigUpdate build() {
            return new FillMaskConfigUpdate(this.numTopClasses, this.resultsField);
        }
    }
}
