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

package com.vmware.photon.controller.common.dcp;

import com.vmware.photon.controller.common.dcp.exceptions.BadRequestException;
import com.vmware.photon.controller.common.dcp.exceptions.DcpRuntimeException;
import com.vmware.photon.controller.common.dcp.exceptions.DocumentNotFoundException;
import com.vmware.photon.controller.common.logging.LoggingUtils;
import com.vmware.photon.controller.common.thrift.ServerSet;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.OperationJoin;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.ServiceDocumentQueryResult;
import com.vmware.xenon.common.ServiceErrorResponse;
import com.vmware.xenon.common.TaskState;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.common.http.netty.NettyHttpServiceClient;
import com.vmware.xenon.services.common.QueryTask;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * REST client to access DCP services.
 */
public class DcpRestClient implements DcpClient {

  private static final long POST_OPERATION_EXPIRATION_MICROS = TimeUnit.SECONDS.toMicros(60);
  private long postOperationExpirationMicros = POST_OPERATION_EXPIRATION_MICROS;
  private static final long GET_OPERATION_EXPIRATION_MICROS = TimeUnit.SECONDS.toMicros(120);
  private long getOperationExpirationMicros = GET_OPERATION_EXPIRATION_MICROS;
  private static final long QUERY_OPERATION_EXPIRATION_MICROS = TimeUnit.SECONDS.toMicros(60);
  private long queryOperationExpirationMicros = QUERY_OPERATION_EXPIRATION_MICROS;
  private static final long DELETE_OPERATION_EXPIRATION_MICROS = TimeUnit.SECONDS.toMicros(60);
  private long deleteOperationExpirationMicros = DELETE_OPERATION_EXPIRATION_MICROS;
  private static final long PATCH_OPERATION_EXPIRATION_MICROS = TimeUnit.SECONDS.toMicros(60);
  private long patchOperationExpirationMicros = PATCH_OPERATION_EXPIRATION_MICROS;
  private static final long DEFAULT_OPERATION_LATCH_TIMEOUT_MICROS = TimeUnit.SECONDS.toMicros(90);
  private static final long SERVICE_DOCUMENT_STATUS_CHECK_INTERVAL_MILLIS = 100L;
  private long serviceDocumentStatusCheckIntervalMillis = SERVICE_DOCUMENT_STATUS_CHECK_INTERVAL_MILLIS;
  private static final Logger logger = LoggerFactory.getLogger(DcpRestClient.class);
  private NettyHttpServiceClient client;
  private ServerSet serverSet;
  private URI localHostUri;
  private InetAddress localHostInetAddress;

  @Inject
  public DcpRestClient(ServerSet serverSet, ExecutorService executor) {
    checkNotNull(serverSet, "Cannot construct DcpRestClient with null serverSet");
    checkNotNull(executor, "Cannot construct DcpRestClient with null executor");

    this.serverSet = serverSet;
    try {
      client = (NettyHttpServiceClient) NettyHttpServiceClient.create(
          DcpRestClient.class.getCanonicalName(),
          executor,
          Executors.newScheduledThreadPool(0));
    } catch (URISyntaxException uriSyntaxException) {
      logger.error("ctor: URISyntaxException={}", uriSyntaxException.toString());
      throw new RuntimeException(uriSyntaxException);
    }

    this.localHostUri = OperationUtils.getLocalHostUri();
    this.localHostInetAddress = OperationUtils.getLocalHostInetAddress();
  }

  public void start() {
    client.start();
    logger.info("client started");
  }

  public void stop() {
    client.stop();
    logger.info("client stopped");
  }

  @Override
  public Operation post(String serviceSelfLink, ServiceDocument body)
      throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException {
    URI serviceUri = getServiceUri(serviceSelfLink);

    Operation postOperation = Operation
        .createPost(serviceUri)
        .setUri(serviceUri)
        .setExpiration(Utils.getNowMicrosUtc() + getPostOperationExpirationMicros())
        .setBody(body)
        .setReferer(this.localHostUri)
        .setContextId(LoggingUtils.getRequestId());

    return send(postOperation);
  }

  @Override
  public Operation get(String documentSelfLink)
      throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException {
    URI serviceUri = getServiceUri(documentSelfLink);

    Operation getOperation = Operation
        .createGet(serviceUri)
        .setUri(serviceUri)
        .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_NO_QUEUING)
        .setExpiration(Utils.getNowMicrosUtc() + getGetOperationExpirationMicros())
        .setReferer(this.localHostUri)
        .setContextId(LoggingUtils.getRequestId());

    return send(getOperation);
  }

  @Override
  public Operation get(URI documentServiceUri)
      throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException {

    Operation getOperation = Operation
        .createGet(documentServiceUri)
        .setUri(documentServiceUri)
        .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_NO_QUEUING)
        .setExpiration(Utils.getNowMicrosUtc() + getGetOperationExpirationMicros())
        .setReferer(this.localHostUri)
        .setContextId(LoggingUtils.getRequestId());

    return send(getOperation);
  }

  @Override
  public Map<String, Operation> get(Collection<String> documentSelfLinks, int batchSize)
      throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException {

    if (documentSelfLinks.isEmpty()) {
      throw new IllegalArgumentException("documentSelfLinks collection cannot be empty");
    }

    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be greater than zero");
    }

    int batchCount = 1 + (documentSelfLinks.size() - 1) / batchSize;
    Map<Long, Operation> operations = new HashMap<>(documentSelfLinks.size());
    Map<Long, String> sourceLinks = new HashMap<>(documentSelfLinks.size());
    for (String documentSelfLink : documentSelfLinks) {
      URI serviceUri = getServiceUri(documentSelfLink);

      Operation getOperation = Operation
          .createGet(serviceUri)
          .setUri(serviceUri)
          .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_NO_QUEUING)
          .setExpiration(Utils.getNowMicrosUtc() + batchCount * getGetOperationExpirationMicros())
          .setReferer(this.localHostUri)
          .setContextId(LoggingUtils.getRequestId());

      operations.put(getOperation.getId(), getOperation);
      sourceLinks.put(getOperation.getId(), documentSelfLink);
    }

    return send(operations, sourceLinks, batchSize);
  }

  @Override
  public Operation delete(String documentSelfLink, ServiceDocument body)
      throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException {
    URI serviceUri = getServiceUri(documentSelfLink);

    Operation deleteOperation = Operation
        .createDelete(serviceUri)
        .setUri(serviceUri)
        .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_NO_QUEUING)
        .setExpiration(Utils.getNowMicrosUtc() + getDeleteOperationExpirationMicros())
        .setReferer(this.localHostUri)
        .setContextId(LoggingUtils.getRequestId())
        .setBody(body);

    return send(deleteOperation);
  }

  @Override
  public Operation postToBroadcastQueryService(QueryTask.QuerySpecification spec)
      throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException {

    URI serviceUri = UriUtils.buildBroadcastRequestUri(
        getServiceUri(ServiceUriPaths.CORE_LOCAL_QUERY_TASKS),
        ServiceUriPaths.DEFAULT_NODE_SELECTOR);

    QueryTask query = QueryTask.create(spec)
        .setDirect(true);

    Operation queryOperation = Operation
        .createPost(serviceUri)
        .setUri(serviceUri)
        .setExpiration(Utils.getNowMicrosUtc() + getQueryOperationExpirationMicros())
        .setBody(query)
        .setReferer(this.localHostUri)
        .setContextId(LoggingUtils.getRequestId());

    return send(queryOperation);
  }

  @Override
  public Operation patch(String serviceSelfLink, ServiceDocument body)
      throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException {
    URI serviceUri = getServiceUri(serviceSelfLink);

    Operation patchOperation = Operation
        .createPatch(serviceUri)
        .setUri(serviceUri)
        .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_NO_QUEUING)
        .setExpiration(Utils.getNowMicrosUtc() + getPatchOperationExpirationMicros())
        .setBody(body)
        .setReferer(this.localHostUri)
        .setContextId(LoggingUtils.getRequestId());

    return send(patchOperation);
  }

  @Override
  public Operation query(QueryTask.QuerySpecification spec, boolean isDirect)
      throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException {
    return query(QueryTask.create(spec).setDirect(isDirect));
  }

  @Override
  public Operation query(QueryTask queryTask)
      throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException {

    URI queryFactoryUri = getServiceUri(ServiceUriPaths.CORE_QUERY_TASKS);

    Operation queryOperation = Operation
        .createPost(queryFactoryUri)
        .setUri(queryFactoryUri)
        .setExpiration(Utils.getNowMicrosUtc() + getQueryOperationExpirationMicros())
        .setBody(queryTask)
        .setReferer(this.localHostUri)
        .setContextId(LoggingUtils.getRequestId());

    return send(queryOperation);
  }

  /**
   * Executes a DCP query which will query for documents of type T.
   * Any other filter clauses are optional.
   * This allows for a query that returns all documents of type T.
   * This also expands the content of the resulting documents.
   *
   * @param documentType
   * @param terms
   * @param <T>
   * @return
   * @throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException
   */
  @Override
  public <T extends ServiceDocument> List<T> queryDocuments(Class<T> documentType,
                                                            ImmutableMap<String, String> terms)
      throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException {
    checkNotNull(documentType, "Cannot query documents with null documentType");

    QueryTask.QuerySpecification spec = QueryTaskUtils.buildQuerySpec(documentType, terms);
    spec.options = EnumSet.of(QueryTask.QuerySpecification.QueryOption.EXPAND_CONTENT);
    Operation result = postToBroadcastQueryService(spec);

    return QueryTaskUtils.getQueryResultDocuments(documentType, result);
  }

  /**
   * Executes a DCP query which queries for documents of type T.
   * The query terms are optional.
   * The pageSize is also optional. If it is not provided, the complete document will be retrieved.
   *
   * @param documentType
   * @param terms
   * @param pageSize
   * @param expandContent
   * @param <T>
   * @return
   * @throws BadRequestException
   * @throws DocumentNotFoundException
   * @throws TimeoutException
   * @throws InterruptedException
   */
  @Override
  public <T extends ServiceDocument> ServiceDocumentQueryResult queryDocuments(Class<T> documentType,
                                                                               ImmutableMap<String, String> terms,
                                                                               Optional<Integer> pageSize,
                                                                               boolean expandContent)
      throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException {

    checkNotNull(documentType, "Cannot query documents with null documentType");
    if (pageSize.isPresent()) {
      checkArgument(pageSize.get() >= 1, "Cannot query documents with a page size less than 1");
    }

    QueryTask.QuerySpecification spec = QueryTaskUtils.buildQuerySpec(documentType, terms);
    spec.options = EnumSet.of(QueryTask.QuerySpecification.QueryOption.BROADCAST);
    if (expandContent) {
      spec.options.add(QueryTask.QuerySpecification.QueryOption.EXPAND_CONTENT);
    }
    if (pageSize.isPresent()) {
      spec.resultLimit = pageSize.get();
    }

    // Indirect call. DCP will not return the results. Instead the service URI
    // established will be obtained here, and it will be used to get the results
    // after the query is in FINISHED stage.
    Operation result = query(spec, false);
    URI queryServiceUri = QueryTaskUtils.getServiceDocumentUri(result);

    // Wait for the query task to finish and then retrieve the documents
    result = waitForTaskToFinish(queryServiceUri);
    ServiceDocumentQueryResult queryResult = result.getBody(QueryTask.class).results;

    if (pageSize.isPresent() && queryResult.nextPageLink != null) {
      // Pagination case, the first query always return empty set. Need to
      // go ahead and get the first page if the nextPageLink is not null.
      return queryDocumentPage(queryResult.nextPageLink);
    } else {
      // No pagination, the result already has the content.
      return queryResult;
    }
  }

  /**
   * Query a document page using the given page link.
   *
   * @param pageLink
   * @return
   * @throws BadRequestException
   * @throws DocumentNotFoundException
   * @throws TimeoutException
   * @throws InterruptedException
   */
  @Override
  public ServiceDocumentQueryResult queryDocumentPage(String pageLink)
      throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException {

    checkNotNull(pageLink, "Cannot query documents with null pageLink");
    checkArgument(!pageLink.isEmpty(), "Cannot query documents with empty pageLink");

    Operation result = get(pageLink);

    return result.getBody(QueryTask.class).results;
  }

  /**
   * Executes a DCP query which will query for documents of type T.
   * Any other filter clauses are optional.
   * This allows for a query that returns all documents of type T.
   * This returns the links to the resulting documents.
   *
   * @param documentType
   * @param terms
   * @param <T>
   * @return
   * @throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException
   */
  @Override
  public <T extends ServiceDocument> List<String> queryDocumentsForLinks(Class<T> documentType,
                                                                         ImmutableMap<String, String> terms)
      throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException {
    checkNotNull(documentType, "Cannot query documents with null documentType");

    QueryTask.QuerySpecification spec = QueryTaskUtils.buildQuerySpec(documentType, terms);
    Operation result = postToBroadcastQueryService(spec);
    Set<String> documentLinks = QueryTaskUtils.getQueryResultDocumentLinks(result);

    if (documentLinks.size() <= 0) {
      return ImmutableList.of();
    }

    return ImmutableList.copyOf(documentLinks);
  }

  /**
   * This method sifts through errors from DCP operations into checked and unchecked(RuntimeExceptions)
   * This is the default handling but it can be overridden by different clients based on their needs.
   *
   * @param requestedOperation
   * @param completedOperation
   * @return
   * @throws DocumentNotFoundException
   * @throws TimeoutException
   */
  @VisibleForTesting
  protected void handleOperationResult(Operation requestedOperation, Operation completedOperation)
      throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException {

    switch (completedOperation.getStatusCode()) {
      case Operation.STATUS_CODE_OK:
      case Operation.STATUS_CODE_ACCEPTED:
        return;
      case Operation.STATUS_CODE_NOT_FOUND:
        throw new DocumentNotFoundException(requestedOperation, completedOperation);
      case Operation.STATUS_CODE_TIMEOUT:
        TimeoutException timeoutException =
            new TimeoutException(completedOperation.getBody(ServiceErrorResponse.class).message);
        handleTimeoutException(completedOperation, timeoutException);
        break;
      case Operation.STATUS_CODE_BAD_REQUEST:
        throw new BadRequestException(requestedOperation, completedOperation);
      default:
        handleUnknownError(requestedOperation, completedOperation);
    }
  }

  @VisibleForTesting
  protected void handleOperationResults(Map<Long, Operation> requestedOps, Collection<Operation> completedOps)
      throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException {
    for (Operation completedOp : completedOps) {
      handleOperationResult(requestedOps.get(completedOp.getId()), completedOp);
    }
  }

  @VisibleForTesting
  protected void handleTimeoutException(Operation operation, TimeoutException timeoutException)
      throws TimeoutException {
    logger.warn("send: TIMEOUT {}, Message={}",
        createLogMessageWithStatusAndBody(operation),
        timeoutException.getMessage());
    throw timeoutException;
  }

  @VisibleForTesting
  protected void handleTimeoutException(OperationJoin operationJoin, TimeoutException timeoutException)
      throws TimeoutException {
    logger.warn("send: TIMEOUT {}, Message={}",
        createLogMessageWithStatusAndBody(operationJoin.getOperations()),
        timeoutException.getMessage());
    throw timeoutException;
  }

  @VisibleForTesting
  protected void handleInterruptedException(Operation operation, InterruptedException interruptedException)
      throws InterruptedException {
    logger.warn("send: INTERRUPTED {}, Exception={}",
        createLogMessageWithStatusAndBody(operation),
        interruptedException);
    throw interruptedException;
  }

  @VisibleForTesting
  protected void handleInterruptedException(OperationJoin operationJoin, InterruptedException interruptedException)
      throws InterruptedException {
    logger.warn("send: INTERRUPTED {}, Exception={}",
        createLogMessageWithStatusAndBody(operationJoin.getOperations()),
        interruptedException);
    throw interruptedException;
  }

  @VisibleForTesting
  protected OperationLatch createOperationLatch(Operation operation) {
    return new OperationLatch(operation);
  }

  @VisibleForTesting
  protected OperationJoinLatch createOperationJoinLatch(OperationJoin operationJoin) {
    return new OperationJoinLatch(operationJoin);
  }

  @VisibleForTesting
  protected Operation send(Operation requestedOperation)
      throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException {
    logger.info("send: STARTED {}", createLogMessageWithBody(requestedOperation));
    OperationLatch operationLatch = createOperationLatch(requestedOperation);

    client.send(requestedOperation);

    Operation completedOperation = null;
    try {
      completedOperation = operationLatch.awaitOperationCompletion(DEFAULT_OPERATION_LATCH_TIMEOUT_MICROS);
      logCompletedOperation(completedOperation);
      handleOperationResult(requestedOperation, completedOperation);
    } catch (TimeoutException timeoutException) {
      handleTimeoutException(requestedOperation, timeoutException);
    } catch (InterruptedException interruptedException) {
      handleInterruptedException(requestedOperation, interruptedException);
    }
    //this maybe null due to client side exceptions caught above.
    return completedOperation;
  }

  @VisibleForTesting
  protected Map<String, Operation> send(Map<Long, Operation> requestedOperations,
                                        Map<Long, String> sourceLinks,
                                        int batchSize)
      throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException {
    logger.info("send: STARTED {}", createLogMessageWithBody(requestedOperations.values()));
    OperationJoin operationJoin = OperationJoin.create(requestedOperations.values());
    OperationJoinLatch operationJoinLatch = createOperationJoinLatch(operationJoin);
    operationJoin.sendWith(client, batchSize);

    Map<String, Operation> result = null;

    try {
      int batchCount = 1 + (requestedOperations.size() - 1) / batchSize;
      operationJoinLatch.await(batchCount * DEFAULT_OPERATION_LATCH_TIMEOUT_MICROS, TimeUnit.MICROSECONDS);
      Collection<Operation> completedOperations = operationJoin.getOperations();
      logCompletedOperations(completedOperations);
      handleOperationResults(requestedOperations, completedOperations);
      result = new HashMap<>(completedOperations.size());
      for (Operation operation : completedOperations) {
        result.put(sourceLinks.get(operation.getId()), operation);
      }
    } catch (TimeoutException timeoutException) {
      handleTimeoutException(operationJoin, timeoutException);
    } catch (InterruptedException interruptedException) {
      handleInterruptedException(operationJoin, interruptedException);
    }

    return result;
  }

  @VisibleForTesting
  protected long getPostOperationExpirationMicros() {
    return postOperationExpirationMicros;
  }

  @VisibleForTesting
  protected long getGetOperationExpirationMicros() {
    return getOperationExpirationMicros;
  }

  @VisibleForTesting
  protected long getQueryOperationExpirationMicros() {
    return queryOperationExpirationMicros;
  }

  @VisibleForTesting
  protected long getDeleteOperationExpirationMicros() {
    return deleteOperationExpirationMicros;
  }

  @VisibleForTesting
  protected long getPatchOperationExpirationMicros() {
    return patchOperationExpirationMicros;
  }

  @VisibleForTesting
  protected long getServiceDocumentStatusCheckIntervalMillis() {
    return serviceDocumentStatusCheckIntervalMillis;
  }

  protected int getPort(InetSocketAddress inetSocketAddress) {
    return inetSocketAddress.getPort();
  }

  private void handleUnknownError(Operation requestedOperation, Operation completedOperation) {
    throw new DcpRuntimeException(requestedOperation, completedOperation);
  }

  private InetSocketAddress getRandomInetSocketAddress() {
    // we need to getServers every time to support dynamic addition and removal of servers.
    return ServiceUtils.selectRandomItem(serverSet.getServers());
  }

  @VisibleForTesting
  protected URI getServiceUri(String path) {

    //check if any of the hosts are available locally
    java.util.Optional<InetSocketAddress> localInetSocketAddress =
        this.serverSet.getServers().stream().filter(
            (InetSocketAddress i) -> i.getAddress().equals(this.localHostInetAddress))
            .findFirst();

    InetSocketAddress selectedInetSocketAddress;
    if (localInetSocketAddress.isPresent()) {
      // let the dcp host decide if a network hop across hosts is required for
      // the requested operation.
      selectedInetSocketAddress = localInetSocketAddress.get();
    } else {
      selectedInetSocketAddress = getRandomInetSocketAddress();
    }

    int port = getPort(selectedInetSocketAddress);
    String address = selectedInetSocketAddress.getAddress().getHostAddress();

    try {
      return new URI("http", null, address, port, path, null, null);
    } catch (URISyntaxException uriSyntaxException) {
      logger.error("createUriFromServerSet: URISyntaxException path={} exception={} port={}",
          path, uriSyntaxException, port);
      throw new RuntimeException(uriSyntaxException);
    }
  }

  private void logCompletedOperation(Operation completedOperation) {
    if (completedOperation.getStatusCode() == Operation.STATUS_CODE_OK) {
      switch (completedOperation.getAction()) {
        case DELETE:
          // fall through
        case PATCH:
          // fall through
        case PUT:
          // for successful DELETE, PATCH and PUT we do not need to log the status and body.
          logger.info("send: SUCCESS {}",
              createLogMessageWithoutStatusAndBody(completedOperation));
          break;
        case POST:
          // fall through
        case GET:
          // fall through
        default:
          // for successful POST and GET we do not need to log the status,
          // but we need need to log the body to see what was returned for the posted query or get.
          logger.info("send: SUCCESS {}",
              createLogMessageWithBody(completedOperation));
      }
    } else {
      if (completedOperation.getStatusCode() == Operation.STATUS_CODE_NOT_FOUND) {
        logger.info("send: COMPLETED {}", createLogMessageWithStatus(completedOperation));
      } else {
        logger.warn("send: WARN {}", createLogMessageWithStatusAndBody(completedOperation));
      }
    }
  }

  private void logCompletedOperations(Collection<Operation> operations) {
    for (Operation completedOp : operations) {
      logCompletedOperation(completedOp);
    }
  }

  private String createLogMessageWithoutStatusAndBody(Operation operation) {
    return String.format(
        "Action={%s}, OperationId={%s}, Uri={%s}, Referer={%s}, jsonBody={NOT LOGGED}",
        operation.getAction(),
        operation.getId(),
        operation.getUri(),
        operation.getReferer());
  }

  private String createLogMessageWithoutStatusAndBody(Collection<Operation> operations) {
    StringBuilder stringBuilder = new StringBuilder();
    for (Operation operation : operations) {
      stringBuilder.append(createLogMessageWithoutStatusAndBody(operation)).append("\n");
    }
    return stringBuilder.toString();
  }

  private String createLogMessageWithBody(Operation operation) {
    return String.format(
        "Action={%s}, OperationId={%s}, Uri={%s}, Referer={%s}, jsonBody={%s}",
        operation.getAction(),
        operation.getId(),
        operation.getUri(),
        operation.getReferer(),
        Utils.toJson(operation.getBodyRaw()));
  }

  private String createLogMessageWithBody(Collection<Operation> operations) {
    StringBuilder stringBuilder = new StringBuilder();
    for (Operation operation : operations) {
      stringBuilder.append(createLogMessageWithBody(operation)).append("\n");
    }
    return stringBuilder.toString();
  }

  private String createLogMessageWithStatus(Operation operation) {
    return String.format(
        "Action={%s}, StatusCode={%s}, OperationId={%s}, Uri={%s}, Referer={%s}, jsonBody={NOT LOGGED}",
        operation.getAction(),
        operation.getStatusCode(),
        operation.getId(),
        operation.getUri(),
        operation.getReferer());
  }

  private String createLogMessageWithStatus(Collection<Operation> operations) {
    StringBuilder stringBuilder = new StringBuilder();
    for (Operation operation : operations) {
      stringBuilder.append(createLogMessageWithStatus(operation)).append("\n");
    }
    return stringBuilder.toString();
  }

  private String createLogMessageWithStatusAndBody(Operation operation) {
    return String.format(
        "Action={%s}, StatusCode={%s}, OperationId={%s}, Uri={%s}, Referer={%s}, jsonBody={%s}",
        operation.getAction(),
        operation.getStatusCode(),
        operation.getId(),
        operation.getUri(),
        operation.getReferer(),
        Utils.toJson(operation.getBodyRaw()));
  }

  private String createLogMessageWithStatusAndBody(Collection<Operation> operations) {
    StringBuilder stringBuilder = new StringBuilder();
    for (Operation operation : operations) {
      stringBuilder.append(createLogMessageWithStatusAndBody(operation)).append("\n");
    }
    return stringBuilder.toString();
  }

  private Operation waitForTaskToFinish(URI serviceUri)
      throws BadRequestException, DocumentNotFoundException, TimeoutException, InterruptedException {

    Operation result = null;
    do {
      result = get(serviceUri);
      TaskState.TaskStage taskStage = QueryTaskUtils.getServiceState(result);
      if (taskStage == TaskState.TaskStage.FINISHED
          || taskStage == TaskState.TaskStage.FAILED
          || taskStage == TaskState.TaskStage.CANCELLED) {

        return result;
      }

      Thread.sleep(getServiceDocumentStatusCheckIntervalMillis());
    } while (Utils.getNowMicrosUtc() <= result.getExpirationMicrosUtc());

    throw new TimeoutException(String.format("Timeout:{%s}, TimeUnit:{%s}", result.getExpirationMicrosUtc(),
        TimeUnit.MICROSECONDS));
  }
}
