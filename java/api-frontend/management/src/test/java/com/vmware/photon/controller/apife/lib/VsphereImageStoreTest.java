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

package com.vmware.photon.controller.apife.lib;

import com.vmware.photon.controller.apife.config.ImageConfig;
import com.vmware.photon.controller.apife.exceptions.internal.InternalException;
import com.vmware.photon.controller.common.clients.HostClient;
import com.vmware.photon.controller.common.clients.HostClientFactory;
import com.vmware.photon.controller.common.clients.exceptions.DirectoryNotFoundException;
import com.vmware.photon.controller.common.clients.exceptions.ImageInUseException;
import com.vmware.photon.controller.common.clients.exceptions.ImageNotFoundException;
import com.vmware.photon.controller.common.clients.exceptions.RpcException;
import com.vmware.photon.controller.host.gen.DeleteImageResponse;
import com.vmware.photon.controller.host.gen.DeleteImageResultCode;
import com.vmware.photon.controller.host.gen.ServiceTicketResponse;
import com.vmware.photon.controller.host.gen.ServiceTicketResultCode;
import com.vmware.transfer.nfc.HostServiceTicket;
import com.vmware.transfer.nfc.NfcClient;

import org.powermock.modules.testng.PowerMockTestCase;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.spy;
import static org.testng.Assert.fail;

import java.io.IOException;

/**
 * Test {@link VsphereImageStore}.
 */
public class VsphereImageStoreTest extends PowerMockTestCase {

  private VsphereImageStore imageStore;
  private HostClientFactory hostClientFactory;
  private HostClient hostClient;
  private ImageConfig imageConfig;
  private String imageId;

  /**
   * Dummy test case to make Intellij recognize this as a test class.
   */
  @Test
  private void dummy() {
  }

  /**
   * Tests the createImage method.
   */
  public class CreateImageTest {
    private NfcClient nfcClient;
    private ServiceTicketResponse serviceTicketResponse;

    @BeforeMethod
    public void setUp() {
      hostClientFactory = mock(HostClientFactory.class);
      hostClient = mock(HostClient.class);
      when(hostClientFactory.create()).thenReturn(hostClient);

      imageConfig = new ImageConfig();
      imageConfig.setDatastore("datastore-name");
      imageConfig.setEndpoint("10.146.1.1");

      imageStore = spy(new VsphereImageStore(hostClientFactory, imageConfig));
      imageId = "image-id";

      nfcClient = mock(NfcClient.class);

      serviceTicketResponse = new ServiceTicketResponse(ServiceTicketResultCode.OK);
      serviceTicketResponse.setTicket(new com.vmware.photon.controller.resource.gen.HostServiceTicket());
    }

    @Test
    public void testSuccess() throws Exception {
      doReturn(nfcClient).when(imageStore).getNfcClient(any(HostServiceTicket.class));
      when(hostClient.getNfcServiceTicket(anyString())).thenReturn(serviceTicketResponse);

      Image imageFolder = spy(imageStore.createImage(imageId));
      assertThat(imageFolder, notNullValue());
    }

    @Test(expectedExceptions = RuntimeException.class)
    public void testWithHostClientException() throws Exception {
      doReturn(nfcClient).when(imageStore).getNfcClient(any(HostServiceTicket.class));
      when(hostClient.getNfcServiceTicket(anyString())).thenThrow(new Exception());

      imageStore.createImage(imageId);
    }

    @Test(expectedExceptions = RuntimeException.class)
    public void testWithNullServiceTicket() throws Exception {
      doReturn(nfcClient).when(imageStore).getNfcClient(any(HostServiceTicket.class));
      when(hostClient.getNfcServiceTicket(anyString())).thenReturn(null);

      imageStore.createImage(imageId);
    }
  }

  /**
   * Tests for deleteImage method.
   */
  public class DeleteImageTest {

    @BeforeMethod
    public void setUp() {
      hostClientFactory = mock(HostClientFactory.class);
      hostClient = mock(HostClient.class);
      when(hostClientFactory.create()).thenReturn(hostClient);

      imageConfig = new ImageConfig();
      imageConfig.setDatastore("datastore-name");
      imageConfig.setEndpoint("10.146.1.1");

      imageStore = spy(new VsphereImageStore(hostClientFactory, imageConfig));
      imageId = "image-id";
    }

    @Test
    public void testSuccess() throws Throwable {
      doReturn(new DeleteImageResponse(DeleteImageResultCode.OK))
          .when(hostClient).deleteImage(imageId, imageConfig.getDatastore());
      imageStore.deleteImage(imageId);
      verify(hostClient).deleteImage(imageId, imageConfig.getDatastore());
    }

    /**
     * Tests that appropriate exceptions are swallowed.
     *
     * @throws Throwable
     */
    @Test(dataProvider = "IgnoredExceptions")
    public void testIgnoredExceptions(Exception ex) throws Throwable {
      doThrow(ex).when(hostClient).deleteImage(imageId, imageConfig.getDatastore());

      imageStore.deleteImage(imageId);
      verify(hostClient).deleteImage(imageId, imageConfig.getDatastore());
    }

    @DataProvider(name = "IgnoredExceptions")
    public Object[][] getIgnoredExceptionsData() {
      return new Object[][]{
          {new ImageInUseException("Image in use")},
          {new ImageNotFoundException("Image not found")}
      };
    }

    /**
     * Tests that exceptions are wrapped and rethrown.
     *
     * @throws Throwable
     */
    @Test
    public void testExceptions() throws Throwable {
      doThrow(new InterruptedException("InterruptedException")).when(hostClient).deleteImage(
          imageId, imageConfig.getDatastore());

      try {
        imageStore.deleteImage(imageId);
        fail("did not propagate the exception");
      } catch (InternalException ex) {
        assertThat(ex.getCause().getMessage(), is("InterruptedException"));
      }
      verify(hostClient).deleteImage(imageId, imageConfig.getDatastore());
    }
  }

  /**
   * Tests for deleting the image folder.
   */
  public class DeleteUploadFolderTest {

    @BeforeMethod
    public void setUp() throws RpcException, InterruptedException, InternalException {
      imageId = "image-id";

      imageConfig = new ImageConfig();
      imageConfig.setDatastore("datastore-name");
      imageConfig.setEndpoint("10.146.1.1");

      hostClientFactory = mock(HostClientFactory.class);
      hostClient = mock(HostClient.class);
      when(hostClientFactory.create()).thenReturn(hostClient);

      imageStore = spy(new VsphereImageStore(hostClientFactory, imageConfig));
    }

    @Test
    public void testDeleteFolderSuccess() throws RpcException, InterruptedException, InternalException, IOException {
      imageStore.deleteUploadFolder(imageId);
      verify(hostClient, times(1)).deleteDirectory(anyString(), anyString());
    }

    @Test
    public void testDeleteFolderSwallowException() throws RpcException, InterruptedException,
        InternalException, IOException {
      doThrow(new DirectoryNotFoundException("Failed to delete folder")).when(hostClient).deleteDirectory(anyString(),
          anyString());
      imageStore.deleteUploadFolder(imageId);
      verify(hostClient, times(1)).deleteDirectory(anyString(), anyString());
    }

    @Test
    public void testDeleteFolderThrowsRpcException() throws RpcException, InterruptedException,
        InternalException, IOException {
      doThrow(new RpcException("Rpc failed")).when(hostClient).deleteDirectory(anyString(), anyString());
      try {
        imageStore.deleteUploadFolder(imageId);
        fail("should have thrown internal exception");
      } catch (InternalException e) {
        verify(hostClient, times(1)).deleteDirectory(anyString(), anyString());
      }
    }
  }
}
