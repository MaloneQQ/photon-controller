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

import com.vmware.photon.controller.api.HostState;
import com.vmware.photon.controller.api.UsageTag;
import com.vmware.photon.controller.cloudstore.dcp.entity.HostService;
import com.vmware.photon.controller.common.config.ConfigBuilder;
import com.vmware.photon.controller.common.dcp.QueryTaskUtils;
import com.vmware.photon.controller.common.dcp.TaskUtils;
import com.vmware.photon.controller.common.dcp.exceptions.DcpRuntimeException;
import com.vmware.photon.controller.common.dcp.validation.Immutable;
import com.vmware.photon.controller.deployer.DeployerConfig;
import com.vmware.photon.controller.deployer.dcp.constant.DeployerDefaults;
import com.vmware.photon.controller.deployer.dcp.entity.ContainerService;
import com.vmware.photon.controller.deployer.dcp.entity.VmService;
import com.vmware.photon.controller.deployer.dcp.util.ControlFlags;
import com.vmware.photon.controller.deployer.dcp.util.MiscUtils;
import com.vmware.photon.controller.deployer.dcp.workflow.CreateManagementPlaneLayoutWorkflowFactoryService;
import com.vmware.photon.controller.deployer.dcp.workflow.CreateManagementPlaneLayoutWorkflowService;
import com.vmware.photon.controller.deployer.helpers.ReflectionUtils;
import com.vmware.photon.controller.deployer.helpers.TestHelper;
import com.vmware.photon.controller.deployer.helpers.dcp.TestEnvironment;
import com.vmware.photon.controller.deployer.helpers.dcp.TestHost;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.TaskState;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.NodeGroupBroadcastResponse;
import com.vmware.xenon.services.common.QueryTask;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import org.mockito.internal.matchers.NotNull;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * This class implements tests for the {@link AllocateHostResourceTaskService} class.
 */
public class AllocateHostResourceTaskServiceTest {

  private TestHost host;
  private AllocateHostResourceTaskService service;
  private com.vmware.photon.controller.cloudstore.dcp.helpers.TestEnvironment cloudStoreMachine;

  /**
   * Dummy function to make IntelliJ think that this is a test class.
   */
  @Test
  private void dummy() {
  }

  private AllocateHostResourceTaskService.State buildValidStartupState() {
    return buildValidStartupState(
        TaskState.TaskStage.CREATED);
  }

  private AllocateHostResourceTaskService.State buildValidStartupState(
      TaskState.TaskStage stage) {

    AllocateHostResourceTaskService.State state = new AllocateHostResourceTaskService.State();
    state.taskState = new TaskState();
    state.taskState.stage = stage;
    state.hostServiceLink = "HOST_SERVICE_LINK";
    state.controlFlags = ControlFlags.CONTROL_FLAG_OPERATION_PROCESSING_DISABLED;

    return state;
  }

  private AllocateHostResourceTaskService.State buildValidPatchState() {
    return buildValidPatchState(TaskState.TaskStage.STARTED);
  }

  private AllocateHostResourceTaskService.State buildValidPatchState(TaskState.TaskStage stage) {

    AllocateHostResourceTaskService.State state = new AllocateHostResourceTaskService.State();
    state.taskState = new TaskState();
    state.taskState.stage = stage;

    return state;
  }

  private TestEnvironment createTestEnvironment(
      DeployerConfig deployerConfig,
      ListeningExecutorService listeningExecutorService,
      int hostCount)
      throws Throwable {
    cloudStoreMachine = com.vmware.photon.controller.cloudstore.dcp.helpers.TestEnvironment.create(1);

    return new TestEnvironment.Builder()
        .containersConfig(deployerConfig.getContainersConfig())
        .deployerContext(deployerConfig.getDeployerContext())
        .listeningExecutorService(listeningExecutorService)
        .cloudServerSet(cloudStoreMachine.getServerSet())
        .hostCount(hostCount)
        .build();
  }

  /**
   * Tests for the constructors.
   */
  public class InitializationTest {

    @BeforeMethod
    public void setUp() throws Throwable {
      service = new AllocateHostResourceTaskService();
    }

    /**
     * Tests that the service starts with the expected capabilities.
     */
    @Test
    public void testCapabilities() {

      EnumSet<Service.ServiceOption> expected = EnumSet.of(
          Service.ServiceOption.OWNER_SELECTION,
          Service.ServiceOption.PERSISTENCE,
          Service.ServiceOption.REPLICATION);
      assertThat(service.getOptions(), is(expected));
    }
  }

  /**
   * Tests for the handleStart method.
   */
  public class HandleStartTest {

    @BeforeClass
    public void setUpClass() throws Throwable {
      host = TestHost.create();
    }

    @BeforeMethod
    public void setUpTest() throws Throwable {
      service = new AllocateHostResourceTaskService();
    }

    @AfterMethod
    public void tearDownTest() throws Throwable {
      try {
        host.deleteServiceSynchronously();
      } catch (ServiceHost.ServiceNotFoundException e) {
        // Exceptions are expected in the case where a service instance was not successfully created.
      }
    }

    @AfterClass
    public void tearDownClass() throws Throwable {
      TestHost.destroy(host);
    }

    /**
     * This test verifies that service instances can be created with specific
     * start states.
     *
     * @param stage Supplies the stage of state.
     * @throws Throwable Throws exception if any error is encountered.
     */
    @Test(dataProvider = "validStartStates")
    public void testMinimalStartState(TaskState.TaskStage stage) throws Throwable {

      AllocateHostResourceTaskService.State startState = buildValidStartupState(stage);
      Operation startOp = host.startServiceSynchronously(service, startState);
      assertThat(startOp.getStatusCode(), is(200));

      AllocateHostResourceTaskService.State savedState = host.getServiceState(
          AllocateHostResourceTaskService.State.class);
      assertThat(savedState.taskState, notNullValue());
      assertThat(savedState.hostServiceLink, is("HOST_SERVICE_LINK"));
    }

    @DataProvider(name = "validStartStates")
    public Object[][] getValidStartStates() {

      return new Object[][]{
          {TaskState.TaskStage.CREATED},
          {TaskState.TaskStage.STARTED},
          {TaskState.TaskStage.FINISHED},
          {TaskState.TaskStage.FAILED},
          {TaskState.TaskStage.CANCELLED}
      };
    }

    /**
     * This test verifies that the task state of a service instance which is started
     * in a terminal state is not modified on startup when state transitions are
     * enabled.
     *
     * @param stage Supplies the stage of the state.
     * @throws Throwable Throws an exception if any error is encountered.
     */
    @Test(dataProvider = "startStateNotChanged")
    public void testMinimalStartStateNotChanged(TaskState.TaskStage stage) throws Throwable {

      AllocateHostResourceTaskService.State startState = buildValidStartupState(stage);
      Operation startOp = host.startServiceSynchronously(service, startState);
      assertThat(startOp.getStatusCode(), is(200));

      AllocateHostResourceTaskService.State savedState =
          host.getServiceState(AllocateHostResourceTaskService.State.class);
      assertThat(savedState.taskState, notNullValue());
      assertThat(savedState.taskState.stage, is(stage));
      assertThat(savedState.hostServiceLink, is("HOST_SERVICE_LINK"));
    }

    @DataProvider(name = "startStateNotChanged")
    public Object[][] getStartStateNotChanged() {

      return new Object[][]{
          {TaskState.TaskStage.FINISHED},
          {TaskState.TaskStage.FAILED},
          {TaskState.TaskStage.CANCELLED}
      };
    }

    /**
     * This test verifies that the service handles the missing of the specified list of attributes
     * in the start state.
     *
     * @param attributeName Supplies the attribute name.
     * @throws Throwable
     */
    @Test(expectedExceptions = IllegalStateException.class, dataProvider = "attributeNames")
    public void testMissingStateValue(String attributeName) throws Throwable {
      AllocateHostResourceTaskService.State startState = buildValidStartupState();
      Field declaredField = startState.getClass().getDeclaredField(attributeName);
      declaredField.set(startState, null);

      host.startServiceSynchronously(service, startState);
    }

    @DataProvider(name = "attributeNames")
    public Object[][] getAttributeNames() {
      List<String> notNullAttributes
          = ReflectionUtils.getAttributeNamesWithAnnotation(AllocateHostResourceTaskService.State.class,
          NotNull.class);
      return TestHelper.toDataProvidersList(notNullAttributes);
    }
  }

  /**
   * Tests for the handlePatch method.
   */
  public class HandlePatchTest {

    @BeforeClass
    public void setUpClass() throws Throwable {
      host = TestHost.create();
    }

    @BeforeMethod
    public void setUpTest() {
      service = new AllocateHostResourceTaskService();
    }

    @AfterMethod
    public void tearDownTest() throws Throwable {
      host.deleteServiceSynchronously();
    }

    @AfterClass
    public void tearDownClass() throws Throwable {
      TestHost.destroy(host);
    }

    /**
     * This test verifies that legal stage transitions succeed.
     *
     * @param startStage  Supplies the stage of the start state.
     * @param targetStage Supplies the stage of the target state.
     * @throws Throwable Throws an exception if any error is encountered.
     */
    @Test(dataProvider = "validStageUpdates")
    public void testValidStageUpdates(
        TaskState.TaskStage startStage,
        TaskState.TaskStage targetStage)
        throws Throwable {

      AllocateHostResourceTaskService.State startState = buildValidStartupState(startStage);
      host.startServiceSynchronously(service, startState);

      AllocateHostResourceTaskService.State patchState = buildValidPatchState(targetStage);
      Operation patchOp = Operation
          .createPatch(UriUtils.buildUri(host, TestHost.SERVICE_URI, null))
          .setBody(patchState);

      Operation resultOp = host.sendRequestAndWait(patchOp);
      assertThat(resultOp.getStatusCode(), is(200));

      AllocateHostResourceTaskService.State savedState =
          host.getServiceState(AllocateHostResourceTaskService.State.class);
      assertThat(savedState.taskState.stage, is(targetStage));
    }

    @DataProvider(name = "validStageUpdates")
    public Object[][] getValidStageUpdates()
        throws Throwable {

      return new Object[][]{
          {TaskState.TaskStage.CREATED, TaskState.TaskStage.STARTED},
          {TaskState.TaskStage.CREATED, TaskState.TaskStage.FINISHED},
          {TaskState.TaskStage.CREATED, TaskState.TaskStage.FAILED},
          {TaskState.TaskStage.CREATED, TaskState.TaskStage.CANCELLED},

          {TaskState.TaskStage.STARTED, TaskState.TaskStage.STARTED},
          {TaskState.TaskStage.STARTED, TaskState.TaskStage.FINISHED},
          {TaskState.TaskStage.STARTED, TaskState.TaskStage.FAILED},
          {TaskState.TaskStage.STARTED, TaskState.TaskStage.CANCELLED},
      };
    }

    /**
     * This test verifies that illegal stage transitions fail, where
     * the start state is invalid.
     *
     * @param startStage  Supplies the stage of the start state.
     * @param targetStage Supplies the stage of the target state.
     * @throws Throwable Throws an exception if any error is encountered.
     */
    @Test(dataProvider = "illegalStageUpdatesInvalidPatch")
    public void testIllegalStageUpdatesInvalidPatch(
        TaskState.TaskStage startStage,
        TaskState.TaskStage targetStage)
        throws Throwable {

      AllocateHostResourceTaskService.State startState = buildValidStartupState(startStage);
      host.startServiceSynchronously(service, startState);

      AllocateHostResourceTaskService.State patchState = buildValidPatchState(targetStage);
      Operation patchOp = Operation
          .createPatch(UriUtils.buildUri(host, TestHost.SERVICE_URI, null))
          .setBody(patchState);

      try {
        host.sendRequestAndWait(patchOp);
        fail("Patch handling should throw in response to invalid start state");
      } catch (DcpRuntimeException e) {
      }
    }

    @DataProvider(name = "illegalStageUpdatesInvalidPatch")
    public Object[][] getIllegalStageUpdatesInvalidPatch() {

      return new Object[][]{
          {TaskState.TaskStage.CREATED, TaskState.TaskStage.CREATED},
          {TaskState.TaskStage.STARTED, TaskState.TaskStage.CREATED},

          {TaskState.TaskStage.FINISHED, TaskState.TaskStage.CREATED},
          {TaskState.TaskStage.FINISHED, TaskState.TaskStage.STARTED},
          {TaskState.TaskStage.FINISHED, TaskState.TaskStage.FINISHED},
          {TaskState.TaskStage.FINISHED, TaskState.TaskStage.FAILED},
          {TaskState.TaskStage.FINISHED, TaskState.TaskStage.CANCELLED},

          {TaskState.TaskStage.FAILED, TaskState.TaskStage.CREATED},
          {TaskState.TaskStage.FAILED, TaskState.TaskStage.STARTED},
          {TaskState.TaskStage.FAILED, TaskState.TaskStage.FINISHED},
          {TaskState.TaskStage.FAILED, TaskState.TaskStage.FAILED},
          {TaskState.TaskStage.FAILED, TaskState.TaskStage.CANCELLED},

          {TaskState.TaskStage.CANCELLED, TaskState.TaskStage.CREATED},
          {TaskState.TaskStage.CANCELLED, TaskState.TaskStage.STARTED},
          {TaskState.TaskStage.CANCELLED, TaskState.TaskStage.FINISHED},
          {TaskState.TaskStage.CANCELLED, TaskState.TaskStage.FAILED},
          {TaskState.TaskStage.CANCELLED, TaskState.TaskStage.CANCELLED},
      };
    }

    /**
     * This test verifies that the service handles the presence of the specified list of attributes
     * in the start state.
     *
     * @param attributeName Supplies the attribute name.
     * @throws Throwable
     */
    @Test(expectedExceptions = DcpRuntimeException.class, dataProvider = "attributeNames")
    public void testInvalidPatchStateValue(String attributeName) throws Throwable {
      AllocateHostResourceTaskService.State startState = buildValidStartupState();
      host.startServiceSynchronously(service, startState);

      AllocateHostResourceTaskService.State patchState = buildValidPatchState();
      Field declaredField = patchState.getClass().getDeclaredField(attributeName);
      if (declaredField.getType() == Boolean.class) {
        declaredField.set(patchState, Boolean.FALSE);
      } else if (declaredField.getType() == Integer.class) {
        declaredField.set(patchState, new Integer(0));
      } else {
        declaredField.set(patchState, declaredField.getType().newInstance());
      }

      Operation patchOp = Operation
          .createPatch(UriUtils.buildUri(host, TestHost.SERVICE_URI, null))
          .setBody(patchState);
      host.sendRequestAndWait(patchOp);
    }

    @DataProvider(name = "attributeNames")
    public Object[][] getAttributeNames() {
      List<String> immutableAttributes
          = ReflectionUtils.getAttributeNamesWithAnnotation(AllocateHostResourceTaskService.State.class,
          Immutable.class);
      return TestHelper.toDataProvidersList(immutableAttributes);
    }
  }

  /**
   * End-to-end tests for the build runtime configuration task.
   */
  public class EndToEndTest {
    private static final String configFilePath = "/config.yml";

    private TestEnvironment machine;
    private ListeningExecutorService listeningExecutorService;
    private AllocateHostResourceTaskService.State startState;

    private DeployerConfig deployerConfig;

    @BeforeClass
    public void setUpClass() throws Throwable {
      listeningExecutorService = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(1));
      deployerConfig = ConfigBuilder.build(DeployerConfig.class,
          this.getClass().getResource(configFilePath).getPath());
      TestHelper.setContainersConfig(deployerConfig);
    }

    @BeforeMethod
    public void setUpTest() throws Exception {
      startState = buildValidStartupState();
      startState.controlFlags = 0x0;
    }

    @AfterMethod
    public void tearDownTest() throws Throwable {

      if (null != machine) {
        machine.stop();
        machine = null;
      }

      if (null != cloudStoreMachine) {
        cloudStoreMachine.stop();
        cloudStoreMachine = null;
      }
      startState = null;
    }

    @AfterClass
    public void tearDownClass() throws Throwable {
      listeningExecutorService.shutdown();
    }

    @Test
    private void testTaskSuccess() throws Throwable {
      machine = createTestEnvironment(deployerConfig, listeningExecutorService, 1);

      HostService.State hostService = createHostEntitiesAndAllocateVmsAndContainers(1, 3, 8, 8192, false);
      startState.hostServiceLink = hostService.documentSelfLink;

      AllocateHostResourceTaskService.State finalState =
          machine.callServiceAndWaitForState(
              AllocateHostResourceTaskFactoryService.SELF_LINK,
              startState,
              AllocateHostResourceTaskService.State.class,
              (state) -> TaskState.TaskStage.STARTED.ordinal() < state.taskState.stage.ordinal());

      TestHelper.assertTaskStateFinished(finalState.taskState);

      String vmServiceLink = getVmService(hostService.documentSelfLink);
      Set<ContainerService.State> containerServices = getContainerServices(vmServiceLink);
      assertThat(containerServices.size(), is(9));
      assertThat(containerServices.stream().mapToInt(cs -> cs.memoryMb).sum(), lessThan(8192));
      assertThat(containerServices.stream().mapToInt(cs -> cs.memoryMb).max().getAsInt(), lessThan(8192));
      containerServices.stream().forEach(cs -> assertThat(cs.cpuShares,
          lessThanOrEqualTo(ContainerService.State.DOCKER_CPU_SHARES_MAX)));
      containerServices.stream().forEach(cs -> assertThat(cs.cpuShares,
          greaterThanOrEqualTo(ContainerService.State.DOCKER_CPU_SHARES_MIN)));
      containerServices.stream().forEach(cs -> assertTrue(cs.dynamicParameters.containsKey("memoryMb")));
      containerServices.stream().forEach(cs -> assertEquals(new Integer(cs.dynamicParameters.get("memoryMb")),
          cs.memoryMb));
    }

    @Test
    private void testTaskSuccessWithMixedHostConfig() throws Throwable {
      machine = createTestEnvironment(deployerConfig, listeningExecutorService, 1);

      HostService.State hostService = createHostEntitiesAndAllocateVmsAndContainers(1, 3, 8, 8192, true);
      startState.hostServiceLink = hostService.documentSelfLink;

      AllocateHostResourceTaskService.State finalState =
          machine.callServiceAndWaitForState(
              AllocateHostResourceTaskFactoryService.SELF_LINK,
              startState,
              AllocateHostResourceTaskService.State.class,
              (state) -> TaskState.TaskStage.STARTED.ordinal() < state.taskState.stage.ordinal());

      TestHelper.assertTaskStateFinished(finalState.taskState);

      String vmServiceLink = getVmService(hostService.documentSelfLink);
      Set<ContainerService.State> containerServices = getContainerServices(vmServiceLink);
      assertThat(containerServices.size(), is(9));
      assertThat(containerServices.stream().mapToInt(cs -> cs.memoryMb).sum(), lessThan((int) (8192 *
          DeployerDefaults.MANAGEMENT_VM_TO_HOST_RESOURCE_RATIO)));
      assertThat(containerServices.stream().mapToInt(cs -> cs.memoryMb).max().getAsInt(), lessThan((int) (8192 *
          DeployerDefaults.MANAGEMENT_VM_TO_HOST_RESOURCE_RATIO)));
      containerServices.stream().forEach(cs -> assertThat(cs.cpuShares,
          lessThanOrEqualTo(ContainerService.State.DOCKER_CPU_SHARES_MAX)));
      containerServices.stream().forEach(cs -> assertThat(cs.cpuShares,
          greaterThanOrEqualTo(ContainerService.State.DOCKER_CPU_SHARES_MIN)));
      containerServices.stream().forEach(cs -> assertTrue(cs.dynamicParameters.containsKey("memoryMb")));
      containerServices.stream().forEach(cs -> assertEquals(new Integer(cs.dynamicParameters.get("memoryMb")),
          cs.memoryMb));
    }

    @Test
    private void testTaskSuccessWithoutHostConfig() throws Throwable {
      machine = createTestEnvironment(deployerConfig, listeningExecutorService, 1);

      HostService.State hostService = createHostEntitiesAndAllocateVmsAndContainers(1, 3, null, null, false);
      startState.hostServiceLink = hostService.documentSelfLink;

      AllocateHostResourceTaskService.State finalState =
          machine.callServiceAndWaitForState(
              AllocateHostResourceTaskFactoryService.SELF_LINK,
              startState,
              AllocateHostResourceTaskService.State.class,
              (state) -> TaskState.TaskStage.STARTED.ordinal() < state.taskState.stage.ordinal());

      TestHelper.assertTaskStateFinished(finalState.taskState);
    }

    @Test
    private void testTaskFailureInvalidHostLink() throws Throwable {
      machine = createTestEnvironment(deployerConfig, listeningExecutorService, 1);

      AllocateHostResourceTaskService.State finalState =
          machine.callServiceAndWaitForState(
              AllocateHostResourceTaskFactoryService.SELF_LINK,
              startState,
              AllocateHostResourceTaskService.State.class,
              (state) -> TaskState.TaskStage.STARTED.ordinal() < state.taskState.stage.ordinal());

      assertThat(finalState.taskState.stage, is(TaskState.TaskStage.FAILED));
    }

    private String getVmService(String hostServiceLink) throws Throwable {

      QueryTask.Query kindClause = new QueryTask.Query()
          .setTermPropertyName(ServiceDocument.FIELD_NAME_KIND)
          .setTermMatchValue(Utils.buildKind(VmService.State.class));

      QueryTask.Query nameClause = new QueryTask.Query()
          .setTermPropertyName(VmService.State.FIELD_NAME_HOST_SERVICE_LINK)
          .setTermMatchValue(hostServiceLink);

      QueryTask.QuerySpecification querySpecification = new QueryTask.QuerySpecification();
      querySpecification.query.addBooleanClause(kindClause);
      querySpecification.query.addBooleanClause(nameClause);

      QueryTask queryTask = QueryTask.create(querySpecification).setDirect(true);

      NodeGroupBroadcastResponse queryResponse = machine.sendBroadcastQueryAndWait(queryTask);
      Set<String> documentLinks = QueryTaskUtils.getBroadcastQueryResults(queryResponse);

      assertThat(documentLinks.size(), is(1));
      return documentLinks.iterator().next();
    }

    private Set<ContainerService.State> getContainerServices(String vmServiceLink)
        throws Throwable {
      QueryTask.Query kindClause = new QueryTask.Query()
          .setTermPropertyName(ServiceDocument.FIELD_NAME_KIND)
          .setTermMatchValue(Utils.buildKind(ContainerService.State.class));

      QueryTask.Query containerTemplateServiceLinkClause = new QueryTask.Query()
          .setTermPropertyName(ContainerService.State.FIELD_NAME_VM_SERVICE_LINK)
          .setTermMatchValue(vmServiceLink);

      QueryTask.QuerySpecification querySpecification = new QueryTask.QuerySpecification();
      querySpecification.query.addBooleanClause(kindClause);
      querySpecification.query.addBooleanClause(containerTemplateServiceLinkClause);

      QueryTask queryTask = QueryTask.create(querySpecification).setDirect(true);

      NodeGroupBroadcastResponse queryResponse = machine.sendBroadcastQueryAndWait(queryTask);
      Set<String> documentLinks = QueryTaskUtils.getBroadcastQueryResults(queryResponse);

      Set<ContainerService.State> containerServices = new HashSet<>();
      for (String documentLink : documentLinks) {
        containerServices.add(machine.getServiceState(documentLink, ContainerService.State.class));
      }

      return containerServices;
    }

    private HostService.State createHostEntitiesAndAllocateVmsAndContainers(
        int mgmtCount, int cloudCount, Integer cpuCount, Integer memoryMb, boolean mixedHost) throws Throwable {

      HostService.State mgmtHostService = null;

      for (int i = 0; i < mgmtCount; i++) {
        Set<String> usageTags = new HashSet<>();
        usageTags.add(UsageTag.MGMT.name());
        if (mixedHost) {
          usageTags.add(UsageTag.CLOUD.name());
        }
        HostService.State hostService = TestHelper.getHostServiceStartState(usageTags, HostState.READY);
        hostService.memoryMb = memoryMb;
        hostService.cpuCount = cpuCount;
        if (i == 0) {
          mgmtHostService = TestHelper.createHostService(cloudStoreMachine, hostService);
        } else {
          TestHelper.createHostService(cloudStoreMachine, hostService);
        }
      }

      for (int i = 0; i < cloudCount; i++) {
        HostService.State hostService = TestHelper.getHostServiceStartState(Collections.singleton(UsageTag.CLOUD.name
            ()), HostState.READY);
        hostService.memoryMb = memoryMb;
        hostService.cpuCount = cpuCount;
        TestHelper.createHostService(cloudStoreMachine, hostService);
      }

      CreateManagementPlaneLayoutWorkflowService.State workflowStartState =
          new CreateManagementPlaneLayoutWorkflowService.State();

      workflowStartState.isAuthEnabled = true;
      workflowStartState.hostQuerySpecification = MiscUtils.generateHostQuerySpecification(null, UsageTag.MGMT.name());

      CreateManagementPlaneLayoutWorkflowService.State serviceState =
          machine.callServiceAndWaitForState(
              CreateManagementPlaneLayoutWorkflowFactoryService.SELF_LINK,
              workflowStartState,
              CreateManagementPlaneLayoutWorkflowService.State.class,
              (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

      TestHelper.assertTaskStateFinished(serviceState.taskState);
      TestHelper.createDeploymentService(cloudStoreMachine);
      return mgmtHostService;
    }
  }
}
