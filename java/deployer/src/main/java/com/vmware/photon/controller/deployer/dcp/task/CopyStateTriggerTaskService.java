/*
 * Copyright 2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.vmware.photon.controller.deployer.dcp.task;

import com.vmware.photon.controller.common.dcp.InitializationUtils;
import com.vmware.photon.controller.common.dcp.OperationUtils;
import com.vmware.photon.controller.common.dcp.QueryTaskUtils;
import com.vmware.photon.controller.common.dcp.ServiceUriPaths;
import com.vmware.photon.controller.common.dcp.ServiceUtils;
import com.vmware.photon.controller.common.dcp.TaskUtils;
import com.vmware.photon.controller.common.dcp.ValidationUtils;
import com.vmware.photon.controller.common.dcp.validation.DefaultInteger;
import com.vmware.photon.controller.common.dcp.validation.DefaultLong;
import com.vmware.photon.controller.common.dcp.validation.DefaultString;
import com.vmware.photon.controller.common.dcp.validation.Immutable;
import com.vmware.photon.controller.common.dcp.validation.NotNull;
import com.vmware.photon.controller.deployer.dcp.util.ExceptionUtils;
import com.vmware.xenon.common.NodeSelectorService;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.OperationSequence;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.TaskState.TaskStage;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.NodeGroupBroadcastResponse;
import com.vmware.xenon.services.common.QueryTask;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * This class moves DCP state between two DCP clusters.
 */
public class CopyStateTriggerTaskService extends StatefulService {
  private static final long OWNER_SELECTION_TIMEOUT = TimeUnit.SECONDS.toMillis(10);

  private static final long DEFAULT_TRIGGER_INTERVAL = TimeUnit.MINUTES.toMicros(5);

  /**
   * Service execution stages.
   */
  public static enum ExecutionState {
    RUNNING,
    STOPPED
  }

  /**
   * This class defines the document state associated with a single
   * {@link CopyStateTriggerTaskService} instance.
   */
  public static class State extends ServiceDocument {
    public ExecutionState executionState;

    @Immutable
    @NotNull
    public String sourceIp;

    @Immutable
    @NotNull
    public Integer sourcePort;

    @Immutable
    @DefaultString(value = "http")
    public String sourceProtocol;

    @Immutable
    @NotNull
    public String destinationIp;

    @Immutable
    @NotNull
    public Integer destinationPort;

    @Immutable
    @DefaultString(value = "http")
    public String destinationProtocol;

    @Immutable
    @NotNull
    public String factoryLink;

    @Immutable
    @NotNull
    public String sourceFactoryLink;

    @Immutable
    @DefaultString(value = "taskState.stage")
    public String taskStateFieldName;

    @Immutable
    @DefaultInteger(value = 10)
    public Integer queryResultLimit;

    public Boolean pulse;

    @DefaultLong(value = 0)
    public Long triggersSuccess;

    @DefaultLong(value = 0)
    public Long triggersError;

    @Immutable
    @DefaultInteger(value = 0)
    public Integer controlFlags;
  }

  public CopyStateTriggerTaskService() {
    super(State.class);
    super.toggleOption(ServiceOption.PERSISTENCE, true);
    super.toggleOption(ServiceOption.INSTRUMENTATION, true);
    super.toggleOption(ServiceOption.PERIODIC_MAINTENANCE, true);
    super.setMaintenanceIntervalMicros(DEFAULT_TRIGGER_INTERVAL);
  }

  @Override
  public void handleStart(Operation start) {
    ServiceUtils.logInfo(this, "Starting service %s", getSelfLink());

    // Initialize the task stage
    State state = start.getBody(State.class);
    if (state.executionState == null) {
      state.executionState = ExecutionState.RUNNING;
    }
    if (state.pulse != null) {
      state.pulse = null;
    }
    InitializationUtils.initialize(state);

    try {
      validateState(state);
      start.setBody(state).complete();
    } catch (Throwable e) {
      ServiceUtils.logSevere(this, e);
      if (!OperationUtils.isCompleted(start)) {
        start.fail(e);
      }
    }
  }

  /**
   * Handle service patch.
   */
  @Override
  public void handlePatch(Operation patch) {
    try {
      State currentState = getState(patch);
      State patchState = patch.getBody(State.class);

      this.validatePatch(patchState);
      this.applyPatch(currentState, patchState);
      this.validateState(currentState);
      patch.complete();

      // Process and complete patch.
      processPatch(patch, currentState, patchState);
    } catch (Throwable e) {
      ServiceUtils.logSevere(this, e);
      if (!OperationUtils.isCompleted(patch)) {
        patch.fail(e);
      }
    }
  }

  /**
   * Handle service periodic maintenance calls.
   */
  @Override
  public void handleMaintenance(Operation post) {
    post.complete();

    Operation.CompletionHandler handler = new Operation.CompletionHandler() {
      @Override
      public void handle(Operation op, Throwable failure) {
        if (null != failure) {
          // query failed so abort and retry next time
          logFailure(failure);
          return;
        }

        NodeSelectorService.SelectOwnerResponse rsp = op.getBody(NodeSelectorService.SelectOwnerResponse.class);
        if (!getHost().getId().equals(rsp.ownerNodeId)) {
          ServiceUtils.logInfo(CopyStateTriggerTaskService.this,
              "Host[%s]: Not owner of scheduler [%s] (Owner Info [%s])",
              getHost().getId(), getSelfLink(), Utils.toJson(rsp));
          return;
        }

        State state = new State();
        state.pulse = true;
        sendSelfPatch(state);
      }
    };

    Operation selectOwnerOp = Operation
        .createPost(null)
        .setExpiration(ServiceUtils.computeExpirationTime(OWNER_SELECTION_TIMEOUT))
        .setCompletion(handler);
    getHost().selectOwner(null, getSelfLink(), selectOwnerOp);
  }

  /**
   * Process patch.
   */
  private void processPatch(Operation patch, final State currentState, final State patchState) {
    // If the triggered is stopped or this is not a pulse, exit.
    if (currentState.executionState != ExecutionState.RUNNING ||
        patchState.pulse == null || patchState.pulse != true) {
      return;
    }

    Operation copyStateTaskQuery = generateQueryCopyStateTaskQuery(currentState);
    OperationSequence.create(copyStateTaskQuery)
        .setCompletion((os, ts) -> {
          if (ts != null && !ts.isEmpty()) {
            failTrigger(currentState, ExceptionUtils.createMultiException(ts.values()));
            return;
          }
          NodeGroupBroadcastResponse queryResponse = os.get(copyStateTaskQuery.getId())
              .getBody(NodeGroupBroadcastResponse.class);
          List<CopyStateTaskService.State> copyStates = QueryTaskUtils
              .getBroadcastQueryDocuments(CopyStateTaskService.State.class, queryResponse);

          List<CopyStateTaskService.State> runningStates = copyStates
              .stream()
              .filter(state -> !TaskUtils.finalTaskStages.contains(state.taskState.stage))
              .collect(Collectors.toList());

          if (runningStates.isEmpty()) {
            long latestUpdateTime = copyStates.stream()
                .filter(state -> state.taskState.stage == TaskStage.FINISHED)
                .mapToLong(state -> state.lastDocumentUpdateTimeEpoc.longValue())
                .max()
                .orElse(0);
            CopyStateTaskService.State startState = buildCopyStateStartState(currentState, latestUpdateTime);
            startCopyStateTask(currentState, startState);
          }
        })
        .sendWith(this);
  }

  private void startCopyStateTask(State currentState, CopyStateTaskService.State startState) {
    sendRequest(
        Operation.createPost(UriUtils.buildUri(getHost(), CopyStateTaskFactoryService.SELF_LINK, null))
            .setBody(startState)
            .setCompletion((o, t) -> {
              if (t != null) {
                failTrigger(currentState, t);
                return;
              }
              succeedTrigger(currentState);
            }));
  }

  private CopyStateTaskService.State buildCopyStateStartState(State currentState, long lastestUpdateTime) {
    CopyStateTaskService.State state = new CopyStateTaskService.State();
    state.destinationIp = currentState.destinationIp;
    state.destinationPort = currentState.destinationPort;
    state.destinationProtocol = currentState.destinationProtocol;
    state.factoryLink = currentState.factoryLink;
    state.queryDocumentsChangedSinceEpoc = lastestUpdateTime;
    state.queryResultLimit = currentState.queryResultLimit;
    state.sourceFactoryLink = currentState.sourceFactoryLink;
    state.sourceIp = currentState.sourceIp;
    state.sourcePort = currentState.sourcePort;
    state.sourceProtocol = currentState.sourceProtocol;
    state.taskStateFieldName = currentState.taskStateFieldName;
    return state;
  }

  private void failTrigger(State currentState, Throwable throwable) {
    State newState = new State();
    ServiceUtils.logSevere(CopyStateTriggerTaskService.this, throwable);
    newState.triggersError = currentState.triggersError + 1;
    sendSelfPatch(newState);
  }

  private void succeedTrigger(State currentState) {
    State newState = new State();
    newState.triggersSuccess = currentState.triggersSuccess + 1;
    sendSelfPatch(newState);
  }

  private Operation generateQueryCopyStateTaskQuery(State currentState) {
    QueryTask.Query typeClause = new QueryTask.Query()
        .setTermPropertyName(ServiceDocument.FIELD_NAME_KIND)
        .setTermMatchValue(Utils.buildKind(CopyStateTaskService.State.class));
    QueryTask.QuerySpecification querySpecification = new QueryTask.QuerySpecification();
    querySpecification.query.addBooleanClause(typeClause);
    querySpecification.query.addBooleanClause(
        buildTermQuery(CopyStateTaskService.State.FIELD_NAME_DESTINATION_IP, currentState.destinationIp));
    querySpecification.query.addBooleanClause(
        buildTermQuery(
            CopyStateTaskService.State.FIELD_NAME_DESTINATION_PORT,
            currentState.destinationPort.toString()));
    querySpecification.query.addBooleanClause(
        buildTermQuery(CopyStateTaskService.State.FIELD_NAME_DESTINATION_PROTOCOL, currentState.destinationProtocol));
    querySpecification.query.addBooleanClause(
        buildTermQuery(CopyStateTaskService.State.FIELD_NAME_SOURCE_IP, currentState.sourceIp));
    querySpecification.query.addBooleanClause(
        buildTermQuery(CopyStateTaskService.State.FIELD_NAME_SOURCE_PORT, currentState.sourcePort.toString()));
    querySpecification.query.addBooleanClause(
        buildTermQuery(CopyStateTaskService.State.FIELD_NAME_SOURCE_PROTOCOL, currentState.sourceProtocol));
    querySpecification.query.addBooleanClause(
        buildTermQuery(CopyStateTaskService.State.FIELD_NAME_FACTORY_LINK, currentState.factoryLink));
    querySpecification.query.addBooleanClause(
        buildTermQuery(CopyStateTaskService.State.FIELD_NAME_SOURCE_FACTORY_LINK, currentState.sourceFactoryLink));
    querySpecification.options = EnumSet.of(QueryTask.QuerySpecification.QueryOption.EXPAND_CONTENT);
    return Operation
        .createPost(UriUtils.buildBroadcastRequestUri(
            UriUtils.buildUri(getHost(), ServiceUriPaths.CORE_LOCAL_QUERY_TASKS),
            ServiceUriPaths.DEFAULT_NODE_SELECTOR))
        .setBody(QueryTask.create(querySpecification).setDirect(true));
  }

  private QueryTask.Query buildTermQuery(String properyName, String matchValue) {
    return new QueryTask.Query()
        .setTermPropertyName(properyName)
        .setTermMatchValue(matchValue);
  }

  /**
   * Validate the service state for coherence.
   *
   * @param current
   */
  protected void validateState(State current) {
    ValidationUtils.validateState(current);
    checkNotNull(current.executionState, "ExecutionState cannot be null.");
    checkIsPositiveNumber(current.triggersSuccess, "triggersSuccess");
    checkIsPositiveNumber(current.triggersError, "triggersError");
  }

  /**
   * Validate patch correctness.
   *
   * @param patch
   */
  protected void validatePatch(State patch) {
    if (patch.triggersSuccess == null &&
        patch.triggersError == null &&
        patch.pulse == null) {
      checkArgument(patch.executionState != null, "ExecutionState cannot be null.");
    }
  }

  /**
   * Applies patch to current document state.
   *
   * @param current
   * @param patch
   */
  protected void applyPatch(State current, State patch) {
    if (patch.executionState != null) {
      current.executionState = patch.executionState;
    }
    current.triggersSuccess = updateLongWithMax(current.triggersSuccess, patch.triggersSuccess);
    current.triggersError = updateLongWithMax(current.triggersError, patch.triggersError);
  }

  /**
   * Update long value. Check for null and overflow.
   */
  private void checkIsPositiveNumber(Long value, String description) {
    checkNotNull(value == null, description + " cannot be null.");
    checkState(value >= 0, description + " cannot be negative.");
  }

  /**
   * Update long value. Check for null and overflow.
   */
  private Long updateLongWithMax(Long previousValue, Long newValue) {
    if (newValue == null) {
      return previousValue;
    }
    if (newValue < 0) {
      return 0L;
    }
    return Math.max(previousValue, newValue);
  }

  /**
   * Send a patch message to ourselves to update the execution stage.
   *
   * @param s
   */
  private void sendSelfPatch(State s) {
    Operation patch = Operation
        .createPatch(UriUtils.buildUri(getHost(), getSelfLink()))
        .setBody(s);
    sendRequest(patch);
  }

  /**
   * Log failed query.
   *
   * @param e
   */
  private void logFailure(Throwable e) {
    ServiceUtils.logSevere(this, e);
  }

}
