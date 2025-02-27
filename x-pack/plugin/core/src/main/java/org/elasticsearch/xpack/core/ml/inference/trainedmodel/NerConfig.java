/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.core.ml.inference.trainedmodel;

import org.elasticsearch.Version;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.xpack.core.ml.inference.persistence.InferenceIndexConstants;
import org.elasticsearch.xpack.core.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.core.ml.utils.NamedXContentObjectHelper;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class NerConfig implements NlpConfig {

    public static final String NAME = "ner";

    public static NerConfig fromXContentStrict(XContentParser parser) {
        return STRICT_PARSER.apply(parser, null);
    }

    public static NerConfig fromXContentLenient(XContentParser parser) {
        return LENIENT_PARSER.apply(parser, null);
    }

    private static final ConstructingObjectParser<NerConfig, Void> STRICT_PARSER = createParser(false);
    private static final ConstructingObjectParser<NerConfig, Void> LENIENT_PARSER = createParser(true);

    @SuppressWarnings({ "unchecked"})
    private static ConstructingObjectParser<NerConfig, Void> createParser(boolean ignoreUnknownFields) {
        ConstructingObjectParser<NerConfig, Void> parser = new ConstructingObjectParser<>(NAME, ignoreUnknownFields,
            a -> new NerConfig((VocabularyConfig) a[0], (Tokenization) a[1], (List<String>) a[2], (String) a[3]));
        parser.declareObject(
            ConstructingObjectParser.optionalConstructorArg(),
            (p, c) -> {
                if (ignoreUnknownFields == false) {
                    throw ExceptionsHelper.badRequestException(
                        "illegal setting [{}] on inference model creation",
                        VOCABULARY.getPreferredName()
                    );
                }
                return VocabularyConfig.fromXContentLenient(p);
            },
            VOCABULARY
        );
        parser.declareNamedObject(
            ConstructingObjectParser.optionalConstructorArg(), (p, c, n) -> p.namedObject(Tokenization.class, n, ignoreUnknownFields),
                TOKENIZATION
        );
        parser.declareStringArray(ConstructingObjectParser.optionalConstructorArg(), CLASSIFICATION_LABELS);
        parser.declareString(ConstructingObjectParser.optionalConstructorArg(), RESULTS_FIELD);

        return parser;
    }

    private final VocabularyConfig vocabularyConfig;
    private final Tokenization tokenization;
    private final List<String> classificationLabels;
    private final String resultsField;

    public NerConfig(@Nullable VocabularyConfig vocabularyConfig,
                     @Nullable Tokenization tokenization,
                     @Nullable List<String> classificationLabels,
                     @Nullable String resultsField) {
        this.vocabularyConfig = Optional.ofNullable(vocabularyConfig)
            .orElse(new VocabularyConfig(InferenceIndexConstants.nativeDefinitionStore()));
        this.tokenization = tokenization == null ? Tokenization.createDefault() : tokenization;
        this.classificationLabels = classificationLabels == null ? Collections.emptyList() : classificationLabels;
        this.resultsField = resultsField;
    }

    public NerConfig(StreamInput in) throws IOException {
        vocabularyConfig = new VocabularyConfig(in);
        tokenization = in.readNamedWriteable(Tokenization.class);
        classificationLabels = in.readStringList();
        resultsField = in.readOptionalString();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        vocabularyConfig.writeTo(out);
        out.writeNamedWriteable(tokenization);
        out.writeStringCollection(classificationLabels);
        out.writeOptionalString(resultsField);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(VOCABULARY.getPreferredName(), vocabularyConfig, params);
        NamedXContentObjectHelper.writeNamedObject(builder, params, TOKENIZATION.getPreferredName(), tokenization);
        if (classificationLabels.isEmpty() == false) {
            builder.field(CLASSIFICATION_LABELS.getPreferredName(), classificationLabels);
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
    public boolean isTargetTypeSupported(TargetType targetType) {
        return false;
    }

    @Override
    public Version getMinimalSupportedVersion() {
        return Version.V_8_0_0;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (o == null || getClass() != o.getClass()) return false;

        NerConfig that = (NerConfig) o;
        return Objects.equals(vocabularyConfig, that.vocabularyConfig)
            && Objects.equals(tokenization, that.tokenization)
            && Objects.equals(classificationLabels, that.classificationLabels)
            && Objects.equals(resultsField, that.resultsField);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vocabularyConfig, tokenization, classificationLabels, resultsField);
    }

    @Override
    public VocabularyConfig getVocabularyConfig() {
        return vocabularyConfig;
    }

    @Override
    public Tokenization getTokenization() {
        return tokenization;
    }

    public List<String> getClassificationLabels() {
        return classificationLabels;
    }

    @Override
    public String getResultsField() {
        return resultsField;
    }

    @Override
    public boolean isAllocateOnly() {
        return true;
    }
}
