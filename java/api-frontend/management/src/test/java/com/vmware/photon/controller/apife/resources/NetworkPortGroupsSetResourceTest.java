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

package com.vmware.photon.controller.apife.resources;

import com.vmware.photon.controller.api.ResourceList;
import com.vmware.photon.controller.api.Task;
import com.vmware.photon.controller.apife.clients.NetworkFeClient;
import com.vmware.photon.controller.apife.resources.routes.NetworkResourceRoutes;
import com.vmware.photon.controller.apife.resources.routes.TaskResourceRoutes;

import com.google.common.collect.ImmutableList;
import org.mockito.Mock;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests {@link NetworkPortGroupsSetResource}.
 */
public class NetworkPortGroupsSetResourceTest extends ResourceTest {

  private String networkId = "network1";

  private String networkSetPortGroupsRoute =
      UriBuilder.fromPath(NetworkResourceRoutes.NETWORK_SET_PORTGROUPS_PATH).build(networkId).toString();

  private String taskId = "task1";

  private String taskRoutePath =
      UriBuilder.fromPath(TaskResourceRoutes.TASK_PATH).build(taskId).toString();

  @Mock
  private NetworkFeClient networkFeClient;

  @Override
  protected void setUpResources() throws Exception {
    addResource(new NetworkPortGroupsSetResource(networkFeClient));
  }

  @DataProvider(name = "ValidPortGroups")
  public Object[][] getValidPortGroups() {
    return new Object[][]{
        {new ArrayList<String>()},
        {ImmutableList.of("PG1", "PG2")},
    };
  }

  @Test(dataProvider = "ValidPortGroups")
  public void testSuccess(List<String> portGroups) throws Throwable {
    Task task = new Task();
    task.setId(taskId);

    when(networkFeClient.setPortGroups(networkId, portGroups)).thenReturn(task);

    Response response = client()
        .target(networkSetPortGroupsRoute)
        .request()
        .post(Entity.entity(new ResourceList<>(portGroups), MediaType.APPLICATION_JSON_TYPE));

    assertThat(response.getStatus(), is(Response.Status.CREATED.getStatusCode()));

    Task responseTask = response.readEntity(Task.class);
    assertThat(responseTask, is(task));
    assertThat(new URI(responseTask.getSelfLink()).isAbsolute(), is(true));
    assertThat(responseTask.getSelfLink().endsWith(taskRoutePath), is(true));
  }

  @Test
  public void testNullPortGroups() throws Throwable {
    Response response = client()
        .target(networkSetPortGroupsRoute)
        .request()
        .post(Entity.entity(null, MediaType.APPLICATION_JSON_TYPE));

    assertThat(response.getStatus(), is(400));
  }

}
