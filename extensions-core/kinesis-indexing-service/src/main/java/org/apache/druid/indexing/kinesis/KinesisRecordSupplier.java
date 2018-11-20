/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.indexing.kinesis;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder;
import com.amazonaws.services.kinesis.model.GetRecordsRequest;
import com.amazonaws.services.kinesis.model.GetRecordsResult;
import com.amazonaws.services.kinesis.model.ProvisionedThroughputExceededException;
import com.amazonaws.services.kinesis.model.Record;
import com.amazonaws.services.kinesis.model.ResourceNotFoundException;
import com.amazonaws.services.kinesis.model.Shard;
import com.amazonaws.services.kinesis.model.ShardIteratorType;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.util.AwsHostNameUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Queues;
import org.apache.druid.common.aws.AWSCredentialsUtils;
import org.apache.druid.indexing.kinesis.aws.ConstructibleAWSCredentialsConfig;
import org.apache.druid.indexing.seekablestream.common.OrderedPartitionableRecord;
import org.apache.druid.indexing.seekablestream.common.RecordSupplier;
import org.apache.druid.indexing.seekablestream.common.StreamPartition;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.emitter.EmittingLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class KinesisRecordSupplier implements RecordSupplier<String, String>
{
  private static final EmittingLogger log = new EmittingLogger(KinesisRecordSupplier.class);
  private static final long PROVISIONED_THROUGHPUT_EXCEEDED_BACKOFF_MS = 3000;
  private static final long EXCEPTION_RETRY_DELAY_MS = 10000;

  private class PartitionResource
  {
    private final StreamPartition<String> streamPartition;
    private final AmazonKinesis kinesisProxy;
    private final Object startLock = new Object();

    private String currIterator; // tracks current position
    private volatile String shardIterator;
    private volatile boolean started;
    private volatile boolean stopRequested;

    public PartitionResource(
        StreamPartition<String> streamPartition,
        AmazonKinesis kinesisProxy
    )
    {
      this.streamPartition = streamPartition;
      this.kinesisProxy = kinesisProxy;
    }

    public void start()
    {
      synchronized (startLock) {
        if (started) {
          return;
        }

        log.info(
            "Starting scheduled fetch runnable for stream[%s] partition[%s]",
            streamPartition.getStream(),
            streamPartition.getPartitionId()
        );

        stopRequested = false;
        started = true;

        rescheduleRunnable(fetchDelayMillis);
      }
    }

    public void stop()
    {
      log.info(
          "Stopping scheduled fetch runnable for stream[%s] partition[%s]",
          streamPartition.getStream(),
          streamPartition.getPartitionId()
      );
      stopRequested = true;
    }


    private Runnable getRecordRunnable()
    {
      return () -> {

        if (stopRequested) {
          started = false;
          stopRequested = false;

          log.info("Worker for partition[%s] has been stopped", streamPartition.getPartitionId());
          return;
        }


        try {

          if (shardIterator == null) {
            log.info("shardIterator[%s] has been closed and has no more records", streamPartition.getPartitionId());

            // add an end-of-shard marker so caller knows this shard is closed
            OrderedPartitionableRecord<String, String> endOfShardRecord = new OrderedPartitionableRecord<>(
                streamPartition.getStream(),
                streamPartition.getPartitionId(),
                OrderedPartitionableRecord.END_OF_SHARD_MARKER,
                null
            );

            if (!records.offer(endOfShardRecord, recordBufferOfferTimeout, TimeUnit.MILLISECONDS)) {
              log.warn("OrderedPartitionableRecord buffer full, retrying in [%,dms]", recordBufferFullWait);
              rescheduleRunnable(recordBufferFullWait);
            }

            return;
          }

          GetRecordsResult recordsResult = kinesisProxy.getRecords(new GetRecordsRequest().withShardIterator(
              shardIterator).withLimit(recordsPerFetch));

          // list will come back empty if there are no records
          for (Record kinesisRecord : recordsResult.getRecords()) {
            final List<byte[]> data;


            data = Collections.singletonList(toByteArray(kinesisRecord.getData()));

            final OrderedPartitionableRecord<String, String> record = new OrderedPartitionableRecord<>(
                streamPartition.getStream(),
                streamPartition.getPartitionId(),
                kinesisRecord.getSequenceNumber(),
                data
            );


            if (log.isTraceEnabled()) {
              log.trace(
                  "Stream[%s] / partition[%s] / sequenceNum[%s] / bufferRemainingCapacity[%d]: %s",
                  record.getStream(),
                  record.getPartitionId(),
                  record.getSequenceNumber(),
                  records.remainingCapacity(),
                  record.getData().stream().map(StringUtils::fromUtf8).collect(Collectors.toList())
              );
            }

            // If the buffer was full and we weren't able to add the message, grab a new stream iterator starting
            // from this message and back off for a bit to let the buffer drain before retrying.
            if (!records.offer(record, recordBufferOfferTimeout, TimeUnit.MILLISECONDS)) {
              log.warn(
                  "OrderedPartitionableRecord buffer full, storing iterator and retrying in [%,dms]",
                  recordBufferFullWait
              );

              shardIterator = kinesisProxy.getShardIterator(
                  record.getStream(),
                  record.getPartitionId(),
                  ShardIteratorType.AT_SEQUENCE_NUMBER.toString(),
                  record.getSequenceNumber()
              ).getShardIterator();

              rescheduleRunnable(recordBufferFullWait);
              return;
            }
          }

          shardIterator = recordsResult.getNextShardIterator(); // will be null if the shard has been closed

          rescheduleRunnable(fetchDelayMillis);
        }
        catch (ProvisionedThroughputExceededException e) {
          long retryMs = Math.max(PROVISIONED_THROUGHPUT_EXCEEDED_BACKOFF_MS, fetchDelayMillis);
          rescheduleRunnable(retryMs);
        }
        catch (Throwable e) {
          log.error(e, "getRecordRunnable exception, retrying in [%,dms]", EXCEPTION_RETRY_DELAY_MS);
          rescheduleRunnable(EXCEPTION_RETRY_DELAY_MS);
        }

      };
    }

    private void rescheduleRunnable(long delayMillis)
    {
      if (started && !stopRequested) {
        try {
          scheduledExec.schedule(getRecordRunnable(), delayMillis, TimeUnit.MILLISECONDS);
        }
        catch (RejectedExecutionException e) {
          log.info(
              "Rejecting fetch records runnable submission, worker for partition[%s] is not in a valid state",
              streamPartition.getPartitionId()
          );

        }
      } else {
        log.info("Worker for partition[%s] has been stopped", streamPartition.getPartitionId());
      }
    }
  }

  private final int recordsPerFetch;
  private final int fetchDelayMillis;
  private final boolean deaggregate;
  private final int recordBufferOfferTimeout;
  private final int recordBufferFullWait;
  private final int fetchSequenceNumberTimeout;
  private final int maxRecordsPerPoll;
  private final int fetchThreads;
  private final int recordBufferSize;

  private final AmazonKinesisClientBuilder kinesisBuilder;
  private ScheduledExecutorService scheduledExec;

  private final Map<String, AmazonKinesis> kinesisProxies = new ConcurrentHashMap<>();
  private final Map<StreamPartition<String>, PartitionResource> partitionResources = new ConcurrentHashMap<>();
  private BlockingQueue<OrderedPartitionableRecord<String, String>> records;

  private volatile boolean checkPartitionsStarted = false;
  private volatile boolean closed = false;

  public KinesisRecordSupplier(
      String endpoint,
      String awsAccessKeyId,
      String awsSecretAccessKey,
      int recordsPerFetch,
      int fetchDelayMillis,
      int fetchThreads,
      String awsAssumedRoleArn,
      String awsExternalId,
      boolean deaggregate,
      int recordBufferSize,
      int recordBufferOfferTimeout,
      int recordBufferFullWait,
      int fetchSequenceNumberTimeout,
      int maxRecordsPerPoll
  )
  {
    this.recordsPerFetch = recordsPerFetch;
    this.fetchDelayMillis = fetchDelayMillis;
    this.deaggregate = deaggregate;
    this.recordBufferOfferTimeout = recordBufferOfferTimeout;
    this.recordBufferFullWait = recordBufferFullWait;
    this.fetchSequenceNumberTimeout = fetchSequenceNumberTimeout;
    this.maxRecordsPerPoll = maxRecordsPerPoll;
    this.fetchThreads = fetchThreads;
    this.recordBufferSize = recordBufferSize;

    AWSCredentialsProvider awsCredentialsProvider = AWSCredentialsUtils.defaultAWSCredentialsProviderChain(
        new ConstructibleAWSCredentialsConfig(awsAccessKeyId, awsSecretAccessKey)
    );

    if (awsAssumedRoleArn != null) {
      log.info("Assuming role [%s] with externalId [%s]", awsAssumedRoleArn, awsExternalId);

      STSAssumeRoleSessionCredentialsProvider.Builder builder = new STSAssumeRoleSessionCredentialsProvider
          .Builder(awsAssumedRoleArn, StringUtils.format("druid-kinesis-%s", UUID.randomUUID().toString()))
          .withStsClient(AWSSecurityTokenServiceClientBuilder.standard()
                                                             .withCredentials(awsCredentialsProvider)
                                                             .build());

      if (awsExternalId != null) {
        builder.withExternalId(awsExternalId);
      }

      awsCredentialsProvider = builder.build();
    }
    kinesisBuilder = AmazonKinesisClientBuilder.standard()
                                               .withCredentials(awsCredentialsProvider)
                                               .withClientConfiguration(new ClientConfiguration())
                                               .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(
                                                   endpoint,
                                                   AwsHostNameUtils.parseRegion(
                                                       endpoint,
                                                       null
                                                   )
                                               ));

    log.info(
        "Creating fetch thread pool of size [%d] (Runtime.availableProcessors=%d)",
        fetchThreads,
        Runtime.getRuntime().availableProcessors()
    );

    scheduledExec = Executors.newScheduledThreadPool(
        fetchThreads,
        Execs.makeThreadFactory("KinesisRecordSupplier-Worker-%d")
    );

    records = new LinkedBlockingQueue<>(recordBufferSize);
  }


  @Override
  public void assign(Set<StreamPartition<String>> collection)
  {
    checkIfClosed();

    collection.forEach(
        streamPartition -> partitionResources.putIfAbsent(
            streamPartition,
            new PartitionResource(streamPartition, getKinesisProxy(streamPartition.getStream()))
        )
    );

    for (Iterator<Map.Entry<StreamPartition<String>, PartitionResource>> i = partitionResources.entrySet()
                                                                                               .iterator(); i.hasNext(); ) {
      Map.Entry<StreamPartition<String>, PartitionResource> entry = i.next();
      if (!collection.contains(entry.getKey())) {
        i.remove();
        entry.getValue().stop();
      }
    }

  }

  @Override
  public void seek(StreamPartition<String> partition, String sequenceNumber)
  {
    checkIfClosed();
    filterBufferAndResetFetchRunnable(ImmutableSet.of(partition));
    seekInternal(partition, sequenceNumber, ShardIteratorType.AT_SEQUENCE_NUMBER);
  }

  @Override
  public void seekToEarliest(Set<StreamPartition<String>> partitions)
  {
    checkIfClosed();
    filterBufferAndResetFetchRunnable(partitions);
    partitions.forEach(partition -> seekInternal(partition, null, ShardIteratorType.TRIM_HORIZON));
  }

  @Override
  public void seekToLatest(Set<StreamPartition<String>> partitions)
  {
    checkIfClosed();
    filterBufferAndResetFetchRunnable(partitions);
    partitions.forEach(partition -> seekInternal(partition, null, ShardIteratorType.LATEST));
  }

  @Override
  public Collection<StreamPartition<String>> getAssignment()
  {
    return partitionResources.keySet();
  }

  @Nonnull
  @Override
  public List<OrderedPartitionableRecord<String, String>> poll(long timeout)
  {
    checkIfClosed();
    if (checkPartitionsStarted) {
      partitionResources.values().forEach(PartitionResource::start);
      checkPartitionsStarted = false;
    }

    try {
      List<OrderedPartitionableRecord<String, String>> polledRecords = new ArrayList<>();
      Queues.drain(
          records,
          polledRecords,
          Math.min(Math.max(records.size(), 1), maxRecordsPerPoll),
          timeout,
          TimeUnit.MILLISECONDS
      );

      polledRecords = polledRecords.stream()
                                   .filter(x -> partitionResources.containsKey(x.getStreamPartition()))
                                   .collect(Collectors.toList());

      // update currIterator in each PartitionResource
      // first, build a map of shardId -> latest record we've polled
      // since polledRecords is ordered from earliest to latest, the final ordering of partitionSequenceMap
      // is guranteed to be latest
      Map<String, OrderedPartitionableRecord<String, String>> partitionSequenceMap = new HashMap<>();
      polledRecords.forEach(x -> partitionSequenceMap.put(x.getPartitionId(), x));

      // then get the next shardIterator for each shard and update currIterator
      partitionSequenceMap.forEach((shardId, record) -> partitionResources.get(record.getStreamPartition()).currIterator =
          record.getSequenceNumber().equals(OrderedPartitionableRecord.END_OF_SHARD_MARKER) ?
          null :
          getKinesisProxy(shardId)
              .getShardIterator(
                  record.getStream(),
                  record.getPartitionId(),
                  ShardIteratorType.AFTER_SEQUENCE_NUMBER.toString(),
                  record.getSequenceNumber()
              )
              .getShardIterator());


      return polledRecords;
    }
    catch (InterruptedException e) {
      log.warn(e, "Interrupted while polling");
      return Collections.emptyList();
    }

  }

  @Override
  public String getLatestSequenceNumber(StreamPartition<String> partition)
  {
    checkIfClosed();
    return getSequenceNumberInternal(partition, ShardIteratorType.LATEST);
  }

  @Override
  public String getEarliestSequenceNumber(StreamPartition<String> partition)
  {
    checkIfClosed();
    return getSequenceNumberInternal(partition, ShardIteratorType.TRIM_HORIZON);
  }

  @Nullable
  @Override
  public String getPosition(StreamPartition<String> partition)
  {
    checkIfClosed();
    if (partitionResources.containsKey(partition)) {
      String iter = partitionResources.get(partition).currIterator;
      if (iter == null) {
        log.warn(
            "attempting to get position in shard[%s], stream[%s] with null sharditerator, is shard closed or did you forget to seek?",
            partition.getPartitionId(),
            partition.getStream()
        );
      }
      return getSequenceNumberInternal(partition, iter);
    } else {
      throw new IAE(
          "attempting to get position in unassigned shard[%s], stream[%s]",
          partition.getPartitionId(),
          partition.getStream()
      );
    }
  }

  @Override
  public Set<String> getPartitionIds(String stream)
  {
    checkIfClosed();
    return getKinesisProxy(stream).describeStream(stream)
                                  .getStreamDescription()
                                  .getShards()
                                  .stream()
                                  .map(Shard::getShardId).collect(Collectors.toSet());
  }

  @Override
  public void close()
  {
    if (this.closed) {
      return;
    }

    assign(ImmutableSet.of());

    scheduledExec.shutdown();

    try {
      if (!scheduledExec.awaitTermination(EXCEPTION_RETRY_DELAY_MS, TimeUnit.MILLISECONDS)) {
        scheduledExec.shutdownNow();
      }
    }
    catch (InterruptedException e) {
      log.info(e, "InterruptedException while shutting down");
    }

    this.closed = true;
  }

  private AmazonKinesis getKinesisProxy(String streamName)
  {
    if (!kinesisProxies.containsKey(streamName)) {
      AmazonKinesis kinesis = kinesisBuilder.build();
      kinesisProxies.put(streamName, kinesis);
    }

    return kinesisProxies.get(streamName);
  }

  private void seekInternal(StreamPartition<String> partition, String sequenceNumber, ShardIteratorType iteratorEnum)
  {
    PartitionResource resource = partitionResources.get(partition);
    if (resource == null) {
      throw new ISE("Partition [%s] has not been assigned", partition);
    }

    log.debug(
        "Seeking partition [%s] to [%s]",
        partition.getPartitionId(),
        sequenceNumber != null ? sequenceNumber : iteratorEnum.toString()
    );

    AmazonKinesis kinesis = getKinesisProxy(partition.getStream());

    resource.shardIterator = kinesis.getShardIterator(
        partition.getStream(),
        partition.getPartitionId(),
        iteratorEnum.toString(),
        sequenceNumber
    ).getShardIterator();

    resource.currIterator = resource.shardIterator;

    resource.start();

    checkPartitionsStarted = true;
  }

  private void filterBufferAndResetFetchRunnable(Set<StreamPartition<String>> partitions)
  {
    scheduledExec.shutdown();

    try {
      if (!scheduledExec.awaitTermination(EXCEPTION_RETRY_DELAY_MS, TimeUnit.MILLISECONDS)) {
        scheduledExec.shutdownNow();
      }
    }
    catch (InterruptedException e) {
      log.info(e, "InterruptedException while shutting down");
    }

    scheduledExec = Executors.newScheduledThreadPool(
        fetchThreads,
        Execs.makeThreadFactory("KinesisRecordSupplier-Worker-%d")
    );

    // filter records in buffer and only retain ones whose partition was not seeked
    BlockingQueue<OrderedPartitionableRecord<String, String>> newQ = new LinkedBlockingQueue<>(recordBufferSize);
    records
        .parallelStream()
        .filter(x -> !partitions.contains(x.getStreamPartition()))
        .forEachOrdered(newQ::offer);

    records = newQ;

    // restart fetching threads
    partitionResources.values().forEach(x -> x.started = false);
    checkPartitionsStarted = true;
  }

  private String getSequenceNumberInternal(StreamPartition<String> partition, ShardIteratorType iteratorEnum)
  {
    AmazonKinesis kinesis = getKinesisProxy(partition.getStream());
    String shardIterator = null;
    try {
      shardIterator = kinesis.getShardIterator(
          partition.getStream(),
          partition.getPartitionId(),
          iteratorEnum.toString()
      ).getShardIterator();
    }
    catch (ResourceNotFoundException e) {
      log.warn("Caught ResourceNotFoundException: %s", e.getMessage());
    }

    return getSequenceNumberInternal(partition, shardIterator);
  }

  private String getSequenceNumberInternal(StreamPartition<String> partition, String shardIterator)
  {
    long timeoutMillis = System.currentTimeMillis() + fetchSequenceNumberTimeout;
    AmazonKinesis kinesis = getKinesisProxy(partition.getStream());


    while (shardIterator != null && System.currentTimeMillis() < timeoutMillis) {

      if (closed) {
        log.info("KinesisRecordSupplier closed while fetching sequenceNumber");
        return null;
      }

      GetRecordsResult recordsResult;
      try {
        recordsResult = kinesis.getRecords(new GetRecordsRequest().withShardIterator(shardIterator).withLimit(1000));
      }
      catch (ProvisionedThroughputExceededException e) {
        try {
          Thread.sleep(PROVISIONED_THROUGHPUT_EXCEEDED_BACKOFF_MS);
          continue;
        }
        catch (InterruptedException e1) {
          log.warn(e1, "Thread interrupted!");
          Thread.currentThread().interrupt();
          break;
        }
      }

      List<Record> records = recordsResult.getRecords();

      if (!records.isEmpty()) {
        return records.get(0).getSequenceNumber();
      }

      shardIterator = recordsResult.getNextShardIterator();
    }

    if (shardIterator == null) {
      log.info("Partition[%s] returned a null shard iterator, is the shard closed?", partition.getPartitionId());
      return OrderedPartitionableRecord.END_OF_SHARD_MARKER;
    }


    // if we reach here, it usually means either the shard has no more records, or records have not been
    // added to this shard
    log.warn(
        "timed out while trying to fetch position for shard[%s], likely no more records in shard",
        partition.getPartitionId()
    );
    return null;

  }

  private void checkIfClosed()
  {
    if (closed) {
      throw new ISE("Invalid operation - KinesisRecordSupplier has already been closed");
    }
  }

  /**
   * Returns an array with the content between the position and limit of "buffer". This may be the buffer's backing
   * array itself. Does not modify position or limit of the buffer.
   */
  private static byte[] toByteArray(final ByteBuffer buffer)
  {
    if (buffer.hasArray()
        && buffer.arrayOffset() == 0
        && buffer.position() == 0
        && buffer.array().length == buffer.limit()) {
      return buffer.array();
    } else {
      final byte[] retVal = new byte[buffer.remaining()];
      buffer.duplicate().get(retVal);
      return retVal;
    }
  }

  @VisibleForTesting
  public int bufferSize()
  {
    return records.size();
  }
}