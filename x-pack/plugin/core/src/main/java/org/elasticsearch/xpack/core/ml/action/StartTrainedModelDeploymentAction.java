/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.core.ml.action;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.support.master.MasterNodeRequest;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ParseField;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.xpack.core.ml.inference.TrainedModelConfig;
import org.elasticsearch.xpack.core.ml.inference.allocation.AllocationStatus;
import org.elasticsearch.xpack.core.ml.job.messages.Messages;
import org.elasticsearch.xpack.core.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.core.ml.utils.MlTaskParams;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.xpack.core.ml.MlTasks.trainedModelDeploymentTaskId;

public class StartTrainedModelDeploymentAction extends ActionType<CreateTrainedModelAllocationAction.Response> {

    public static final StartTrainedModelDeploymentAction INSTANCE = new StartTrainedModelDeploymentAction();
    public static final String NAME = "cluster:admin/xpack/ml/trained_models/deployment/start";

    public static final TimeValue DEFAULT_TIMEOUT = new TimeValue(20, TimeUnit.SECONDS);

    public StartTrainedModelDeploymentAction() {
        super(NAME, CreateTrainedModelAllocationAction.Response::new);
    }

    public static class Request extends MasterNodeRequest<Request> implements ToXContentObject {

        private static final AllocationStatus.State[] VALID_WAIT_STATES = new AllocationStatus.State[] {
            AllocationStatus.State.STARTED,
            AllocationStatus.State.STARTING,
            AllocationStatus.State.FULLY_ALLOCATED };
        public static final ParseField MODEL_ID = new ParseField("model_id");
        public static final ParseField TIMEOUT = new ParseField("timeout");
        public static final ParseField WAIT_FOR = new ParseField("wait_for");
        public static final ParseField INFERENCE_THREADS = TaskParams.INFERENCE_THREADS;
        public static final ParseField MODEL_THREADS = TaskParams.MODEL_THREADS;

        public static final ObjectParser<Request, Void> PARSER = new ObjectParser<>(NAME, Request::new);

        static {
            PARSER.declareString(Request::setModelId, MODEL_ID);
            PARSER.declareString((request, val) -> request.setTimeout(TimeValue.parseTimeValue(val, TIMEOUT.getPreferredName())), TIMEOUT);
            PARSER.declareString((request, waitFor) -> request.setWaitForState(AllocationStatus.State.fromString(waitFor)), WAIT_FOR);
            PARSER.declareInt(Request::setInferenceThreads, INFERENCE_THREADS);
            PARSER.declareInt(Request::setModelThreads, MODEL_THREADS);
        }

        public static Request parseRequest(String modelId, XContentParser parser) {
            Request request = PARSER.apply(parser, null);
            if (request.getModelId() == null) {
                request.setModelId(modelId);
            } else if (Strings.isNullOrEmpty(modelId) == false && modelId.equals(request.getModelId()) == false) {
                throw ExceptionsHelper.badRequestException(
                    Messages.getMessage(Messages.INCONSISTENT_ID, MODEL_ID, request.getModelId(), modelId));
            }
            return request;
        }

        private String modelId;
        private TimeValue timeout = DEFAULT_TIMEOUT;
        private AllocationStatus.State waitForState = AllocationStatus.State.STARTED;
        private int modelThreads = 1;
        private int inferenceThreads = 1;

        private Request() {}

        public Request(String modelId) {
            setModelId(modelId);
        }

        public Request(StreamInput in) throws IOException {
            super(in);
            modelId = in.readString();
            timeout = in.readTimeValue();
            waitForState = in.readEnum(AllocationStatus.State.class);
            modelThreads = in.readVInt();
            inferenceThreads = in.readVInt();
        }

        public final void setModelId(String modelId) {
            this.modelId = ExceptionsHelper.requireNonNull(modelId, MODEL_ID);
        }

        public String getModelId() {
            return modelId;
        }

        public void setTimeout(TimeValue timeout) {
            this.timeout = ExceptionsHelper.requireNonNull(timeout, TIMEOUT);
        }

        public TimeValue getTimeout() {
            return timeout;
        }

        public AllocationStatus.State getWaitForState() {
            return waitForState;
        }

        public Request setWaitForState(AllocationStatus.State waitForState) {
            this.waitForState = ExceptionsHelper.requireNonNull(waitForState, WAIT_FOR);
            return this;
        }

        public int getModelThreads() {
            return modelThreads;
        }

        public void setModelThreads(int modelThreads) {
            this.modelThreads = modelThreads;
        }

        public int getInferenceThreads() {
            return inferenceThreads;
        }

        public void setInferenceThreads(int inferenceThreads) {
            this.inferenceThreads = inferenceThreads;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(modelId);
            out.writeTimeValue(timeout);
            out.writeEnum(waitForState);
            out.writeVInt(modelThreads);
            out.writeVInt(inferenceThreads);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(MODEL_ID.getPreferredName(), modelId);
            builder.field(TIMEOUT.getPreferredName(), timeout.getStringRep());
            builder.field(WAIT_FOR.getPreferredName(), waitForState);
            builder.field(MODEL_THREADS.getPreferredName(), modelThreads);
            builder.field(INFERENCE_THREADS.getPreferredName(), inferenceThreads);
            builder.endObject();
            return builder;
        }

        @Override
        public ActionRequestValidationException validate() {
            ActionRequestValidationException validationException = new ActionRequestValidationException();
            if (waitForState.isAnyOf(VALID_WAIT_STATES) == false) {
                validationException.addValidationError(
                    "invalid [wait_for] state ["
                        + waitForState
                        + "]; must be one of ["
                        + Strings.arrayToCommaDelimitedString(VALID_WAIT_STATES)
                );
            }
            if (modelThreads < 1) {
                validationException.addValidationError("[" + MODEL_THREADS + "] must be a positive integer");
            }
            if (inferenceThreads < 1) {
                validationException.addValidationError("[" + INFERENCE_THREADS + "] must be a positive integer");
            }
            return validationException.validationErrors().isEmpty() ? null : validationException;
        }

        @Override
        public int hashCode() {
            return Objects.hash(modelId, timeout, waitForState, modelThreads, inferenceThreads);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || obj.getClass() != getClass()) {
                return false;
            }
            Request other = (Request) obj;
            return Objects.equals(modelId, other.modelId)
                && Objects.equals(timeout, other.timeout)
                && Objects.equals(waitForState, other.waitForState)
                && modelThreads == other.modelThreads
                && inferenceThreads == other.inferenceThreads;
        }

        @Override
        public String toString() {
            return Strings.toString(this);
        }
    }

    public static class TaskParams implements MlTaskParams, Writeable, ToXContentObject {

        // TODO add support for other roles? If so, it may have to be an instance method...
        // NOTE, whatever determines allocation should not be dynamically set on the node
        // Otherwise allocation logic might fail
        public static boolean mayAllocateToNode(DiscoveryNode node) {
            return node.getRoles().contains(DiscoveryNodeRole.ML_ROLE) && node.getVersion().onOrAfter(VERSION_INTRODUCED);
        }

        public static final Version VERSION_INTRODUCED = Version.V_8_0_0;
        private static final ParseField MODEL_BYTES = new ParseField("model_bytes");
        public static final ParseField MODEL_THREADS = new ParseField("model_threads");
        public static final ParseField INFERENCE_THREADS = new ParseField("inference_threads");
        private static final ConstructingObjectParser<TaskParams, Void> PARSER = new ConstructingObjectParser<>(
            "trained_model_deployment_params",
            true,
            a -> new TaskParams((String)a[0], (Long)a[1], (int) a[2], (int) a[3])
        );
        static {
            PARSER.declareString(ConstructingObjectParser.constructorArg(), TrainedModelConfig.MODEL_ID);
            PARSER.declareLong(ConstructingObjectParser.constructorArg(), MODEL_BYTES);
            PARSER.declareInt(ConstructingObjectParser.constructorArg(), INFERENCE_THREADS);
            PARSER.declareInt(ConstructingObjectParser.constructorArg(), MODEL_THREADS);
        }

        public static TaskParams fromXContent(XContentParser parser) {
            return PARSER.apply(parser, null);
        }

        /**
         * This has been found to be approximately 300MB on linux by manual testing.
         * We also subtract 30MB that we always add as overhead (see MachineLearning.NATIVE_EXECUTABLE_CODE_OVERHEAD).
         * TODO Check if it is substantially different in other platforms.
         */
        private static final ByteSizeValue MEMORY_OVERHEAD = ByteSizeValue.ofMb(270);

        private final String modelId;
        private final long modelBytes;
        private final int inferenceThreads;
        private final int modelThreads;

        public TaskParams(String modelId, long modelBytes, int inferenceThreads, int modelThreads) {
            this.modelId = Objects.requireNonNull(modelId);
            this.modelBytes = modelBytes;
            if (modelBytes < 0) {
                throw new IllegalArgumentException("modelBytes must be non-negative");
            }
            this.inferenceThreads = inferenceThreads;
            if (inferenceThreads < 1) {
                throw new IllegalArgumentException(INFERENCE_THREADS + " must be positive");
            }
            this.modelThreads = modelThreads;
            if (modelThreads < 1) {
                throw new IllegalArgumentException(MODEL_THREADS + " must be positive");
            }
        }

        public TaskParams(StreamInput in) throws IOException {
            this.modelId = in.readString();
            this.modelBytes = in.readVLong();
            this.inferenceThreads = in.readVInt();
            this.modelThreads = in.readVInt();
        }

        public String getModelId() {
            return modelId;
        }

        public long estimateMemoryUsageBytes() {
            // While loading the model in the process we need twice the model size.
            return MEMORY_OVERHEAD.getBytes() + 2 * modelBytes;
        }

        public Version getMinimalSupportedVersion() {
            return VERSION_INTRODUCED;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(modelId);
            out.writeVLong(modelBytes);
            out.writeVInt(inferenceThreads);
            out.writeVInt(modelThreads);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(TrainedModelConfig.MODEL_ID.getPreferredName(), modelId);
            builder.field(MODEL_BYTES.getPreferredName(), modelBytes);
            builder.field(INFERENCE_THREADS.getPreferredName(), inferenceThreads);
            builder.field(MODEL_THREADS.getPreferredName(), modelThreads);
            builder.endObject();
            return builder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(modelId, modelBytes, inferenceThreads, modelThreads);
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if (o == null || getClass() != o.getClass()) return false;

            TaskParams other = (TaskParams) o;
            return Objects.equals(modelId, other.modelId)
                && modelBytes == other.modelBytes
                && inferenceThreads == other.inferenceThreads
                && modelThreads == other.modelThreads;
        }

        @Override
        public String getMlId() {
            return modelId;
        }

        public long getModelBytes() {
            return modelBytes;
        }

        public int getInferenceThreads() {
            return inferenceThreads;
        }

        public int getModelThreads() {
            return modelThreads;
        }
    }

    public interface TaskMatcher {

        static boolean match(Task task, String expectedId) {
            if (task instanceof TaskMatcher) {
                if (Strings.isAllOrWildcard(expectedId)) {
                    return true;
                }
                String expectedDescription = trainedModelDeploymentTaskId(expectedId);
                return expectedDescription.equals(task.getDescription());
            }
            return false;
        }
    }
}
