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

package org.apache.druid.segment.incremental;

import com.carrotsearch.junitbenchmarks.AbstractBenchmark;
import com.carrotsearch.junitbenchmarks.BenchmarkOptions;
import com.carrotsearch.junitbenchmarks.Clock;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.MapBasedInputRow;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.query.Druids;
import org.apache.druid.query.FinalizeResultsQueryRunner;
import org.apache.druid.query.QueryPlus;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QueryRunnerFactory;
import org.apache.druid.query.QueryRunnerTestHelper;
import org.apache.druid.query.Result;
import org.apache.druid.query.aggregation.Aggregator;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.CountAggregatorFactory;
import org.apache.druid.query.aggregation.DoubleSumAggregatorFactory;
import org.apache.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.druid.query.timeseries.TimeseriesQuery;
import org.apache.druid.query.timeseries.TimeseriesQueryEngine;
import org.apache.druid.query.timeseries.TimeseriesQueryQueryToolChest;
import org.apache.druid.query.timeseries.TimeseriesQueryRunnerFactory;
import org.apache.druid.query.timeseries.TimeseriesResultValue;
import org.apache.druid.segment.IncrementalIndexSegment;
import org.apache.druid.segment.Segment;
import org.joda.time.Interval;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Extending AbstractBenchmark means only runs if explicitly called
 */
@RunWith(Parameterized.class)
public class OnheapIncrementalIndexBenchmark extends AbstractBenchmark
{
  private static AggregatorFactory[] factories;
  static final int DIMENSION_COUNT = 5;

  static {

    final ArrayList<AggregatorFactory> ingestAggregatorFactories = new ArrayList<>(DIMENSION_COUNT + 1);
    ingestAggregatorFactories.add(new CountAggregatorFactory("rows"));
    for (int i = 0; i < DIMENSION_COUNT; ++i) {
      ingestAggregatorFactories.add(
          new LongSumAggregatorFactory(
              StringUtils.format("sumResult%s", i),
              StringUtils.format("Dim_%s", i)
          )
      );
      ingestAggregatorFactories.add(
          new DoubleSumAggregatorFactory(
              StringUtils.format("doubleSumResult%s", i),
              StringUtils.format("Dim_%s", i)
          )
      );
    }
    factories = ingestAggregatorFactories.toArray(new AggregatorFactory[0]);
  }

  private static final class MapIncrementalIndex extends OnheapIncrementalIndex
  {
    private final AtomicInteger indexIncrement = new AtomicInteger(0);
    ConcurrentHashMap<Integer, Aggregator[]> indexedMap = new ConcurrentHashMap<Integer, Aggregator[]>();

    public MapIncrementalIndex(
        IncrementalIndexSchema incrementalIndexSchema,
        boolean deserializeComplexMetrics,
        boolean concurrentEventAdd,
        boolean sortFacts,
        int maxRowCount,
        long maxBytesInMemory
    )
    {
      super(
          incrementalIndexSchema,
          deserializeComplexMetrics,
          concurrentEventAdd,
          sortFacts,
          maxRowCount,
          maxBytesInMemory,
          false,
          true,
          false
      );
    }

    public MapIncrementalIndex(
        long minTimestamp,
        Granularity gran,
        AggregatorFactory[] metrics,
        int maxRowCount,
        long maxBytesInMemory
    )
    {
      super(
          new IncrementalIndexSchema.Builder()
              .withMinTimestamp(minTimestamp)
              .withQueryGranularity(gran)
              .withMetrics(metrics)
              .build(),
          true,
          false,
          true,
          maxRowCount,
          maxBytesInMemory,
          false,
          true,
          false
      );
    }

    @Override
    protected Aggregator[] concurrentGet(int offset)
    {
      // All get operations should be fine
      return indexedMap.get(offset);
    }

    @Override
    protected void concurrentSet(int offset, Aggregator[] value)
    {
      indexedMap.put(offset, value);
    }

    @Override
    protected AddToFactsResult addToFacts(
        InputRow row,
        IncrementalIndexRow key,
        ThreadLocal<InputRow> rowContainer,
        Supplier<InputRow> rowSupplier,
        boolean skipMaxRowsInMemoryCheck // ignore for benchmark
    ) throws IndexSizeExceededException
    {

      final Integer priorIdex = getFacts().getPriorIndex(key);

      Aggregator[] aggs;
      final AggregatorFactory[] metrics = getMetrics();
      final AtomicInteger numEntries = getNumEntries();
      final AtomicLong sizeInBytes = getBytesInMemory();
      if (null != priorIdex) {
        aggs = indexedMap.get(priorIdex);
      } else {
        aggs = new Aggregator[metrics.length];

        for (int i = 0; i < metrics.length; i++) {
          final AggregatorFactory agg = metrics[i];
          aggs[i] = agg.factorize(
              makeColumnSelectorFactory(agg, rowSupplier, getDeserializeComplexMetrics())
          );
        }
        Integer rowIndex;

        do {
          rowIndex = indexIncrement.incrementAndGet();
        } while (null != indexedMap.putIfAbsent(rowIndex, aggs));


        // Last ditch sanity checks
        if ((numEntries.get() >= maxRowCount || sizeInBytes.get() >= maxBytesInMemory)
            && getFacts().getPriorIndex(key) == IncrementalIndexRow.EMPTY_ROW_INDEX) {
          throw new IndexSizeExceededException("Maximum number of rows or max bytes reached");
        }
        final int prev = getFacts().putIfAbsent(key, rowIndex);
        if (IncrementalIndexRow.EMPTY_ROW_INDEX == prev) {
          numEntries.incrementAndGet();
          sizeInBytes.incrementAndGet();
        } else {
          // We lost a race
          aggs = indexedMap.get(prev);
          // Free up the misfire
          indexedMap.remove(rowIndex);
          // This is expected to occur ~80% of the time in the worst scenarios
        }
      }

      rowContainer.set(row);

      for (Aggregator agg : aggs) {
        synchronized (agg) {
          agg.aggregate();
        }
      }

      rowContainer.set(null);

      return new AddToFactsResult(numEntries.get(), sizeInBytes.get(), new ArrayList<>());
    }

    @Override
    public int getLastRowIndex()
    {
      return indexIncrement.get() - 1;
    }
  }

  @Parameterized.Parameters
  public static Collection<Object[]> getParameters()
  {
    return ImmutableList.of(
        new Object[]{OnheapIncrementalIndex.class},
        new Object[]{MapIncrementalIndex.class}
    );
  }

  private final Class<? extends OnheapIncrementalIndex> incrementalIndex;

  public OnheapIncrementalIndexBenchmark(Class<? extends OnheapIncrementalIndex> incrementalIndex)
  {
    this.incrementalIndex = incrementalIndex;
  }


  private static MapBasedInputRow getLongRow(long timestamp, int rowID, int dimensionCount)
  {
    List<String> dimensionList = new ArrayList<String>(dimensionCount);
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
    for (int i = 0; i < dimensionCount; i++) {
      String dimName = StringUtils.format("Dim_%d", i);
      dimensionList.add(dimName);
      builder.put(dimName, new Integer(rowID).longValue());
    }
    return new MapBasedInputRow(timestamp, dimensionList, builder.build());
  }

  @Ignore
  @Test
  @BenchmarkOptions(callgc = true, clock = Clock.REAL_TIME, warmupRounds = 10, benchmarkRounds = 20)
  public void testConcurrentAddRead()
      throws InterruptedException, ExecutionException, NoSuchMethodException, IllegalAccessException,
             InvocationTargetException, InstantiationException
  {

    final int taskCount = 30;
    final int concurrentThreads = 3;
    final int elementsPerThread = 1 << 15;

    final IncrementalIndex incrementalIndex = this.incrementalIndex.getConstructor(
        IncrementalIndexSchema.class,
        boolean.class,
        boolean.class,
        boolean.class,
        boolean.class,
        int.class
    ).newInstance(
        new IncrementalIndexSchema.Builder().withMetrics(factories).build(),
        true,
        true,
        false,
        true,
        elementsPerThread * taskCount
    );
    final ArrayList<AggregatorFactory> queryAggregatorFactories = new ArrayList<>(DIMENSION_COUNT + 1);
    queryAggregatorFactories.add(new CountAggregatorFactory("rows"));
    for (int i = 0; i < DIMENSION_COUNT; ++i) {
      queryAggregatorFactories.add(
          new LongSumAggregatorFactory(
              StringUtils.format("sumResult%s", i),
              StringUtils.format("sumResult%s", i)
          )
      );
      queryAggregatorFactories.add(
          new DoubleSumAggregatorFactory(
              StringUtils.format("doubleSumResult%s", i),
              StringUtils.format("doubleSumResult%s", i)
          )
      );
    }

    final ListeningExecutorService indexExecutor = MoreExecutors.listeningDecorator(
        Executors.newFixedThreadPool(
            concurrentThreads,
            new ThreadFactoryBuilder()
                .setDaemon(false)
                .setNameFormat("index-executor-%d")
                .setPriority(Thread.MIN_PRIORITY)
                .build()
        )
    );
    final ListeningExecutorService queryExecutor = MoreExecutors.listeningDecorator(
        Executors.newFixedThreadPool(
            concurrentThreads,
            new ThreadFactoryBuilder()
                .setDaemon(false)
                .setNameFormat("query-executor-%d")
                .build()
        )
    );
    final long timestamp = System.currentTimeMillis();
    final Interval queryInterval = Intervals.of("1900-01-01T00:00:00Z/2900-01-01T00:00:00Z");
    final List<ListenableFuture<?>> indexFutures = new ArrayList<>();
    final List<ListenableFuture<?>> queryFutures = new ArrayList<>();
    final Segment incrementalIndexSegment = new IncrementalIndexSegment(incrementalIndex, null);
    final QueryRunnerFactory factory = new TimeseriesQueryRunnerFactory(
        new TimeseriesQueryQueryToolChest(),
        new TimeseriesQueryEngine(),
        QueryRunnerTestHelper.NOOP_QUERYWATCHER
    );
    final AtomicInteger currentlyRunning = new AtomicInteger(0);
    final AtomicBoolean concurrentlyRan = new AtomicBoolean(false);
    final AtomicBoolean someoneRan = new AtomicBoolean(false);
    for (int j = 0; j < taskCount; j++) {
      indexFutures.add(
          indexExecutor.submit(
              new Runnable()
              {
                @Override
                public void run()
                {
                  currentlyRunning.incrementAndGet();
                  try {
                    for (int i = 0; i < elementsPerThread; i++) {
                      incrementalIndex.add(getLongRow(timestamp + i, 1, DIMENSION_COUNT));
                    }
                  }
                  catch (IndexSizeExceededException e) {
                    throw new RuntimeException(e);
                  }
                  currentlyRunning.decrementAndGet();
                  someoneRan.set(true);
                }
              }
          )
      );

      queryFutures.add(
          queryExecutor.submit(
              new Runnable()
              {
                @Override
                public void run()
                {
                  QueryRunner<Result<TimeseriesResultValue>> runner = new FinalizeResultsQueryRunner<Result<TimeseriesResultValue>>(
                      factory.createRunner(incrementalIndexSegment),
                      factory.getToolchest()
                  );
                  TimeseriesQuery query = Druids.newTimeseriesQueryBuilder()
                                                .dataSource("xxx")
                                                .granularity(Granularities.ALL)
                                                .intervals(ImmutableList.of(queryInterval))
                                                .aggregators(queryAggregatorFactories)
                                                .build();
                  List<Result<TimeseriesResultValue>> results = runner.run(QueryPlus.wrap(query)).toList();
                  for (Result<TimeseriesResultValue> result : results) {
                    if (someoneRan.get()) {
                      Assert.assertTrue(result.getValue().getDoubleMetric("doubleSumResult0") > 0);
                    }
                  }
                  if (currentlyRunning.get() > 0) {
                    concurrentlyRan.set(true);
                  }
                }
              }
          )
      );

    }
    List<ListenableFuture<?>> allFutures = new ArrayList<>(queryFutures.size() + indexFutures.size());
    allFutures.addAll(queryFutures);
    allFutures.addAll(indexFutures);
    Futures.allAsList(allFutures).get();
    //Assert.assertTrue("Did not hit concurrency, please try again", concurrentlyRan.get());
    queryExecutor.shutdown();
    indexExecutor.shutdown();
    QueryRunner<Result<TimeseriesResultValue>> runner = new FinalizeResultsQueryRunner<Result<TimeseriesResultValue>>(
        factory.createRunner(incrementalIndexSegment),
        factory.getToolchest()
    );
    TimeseriesQuery query = Druids.newTimeseriesQueryBuilder()
                                  .dataSource("xxx")
                                  .granularity(Granularities.ALL)
                                  .intervals(ImmutableList.of(queryInterval))
                                  .aggregators(queryAggregatorFactories)
                                  .build();
    List<Result<TimeseriesResultValue>> results = runner.run(QueryPlus.wrap(query)).toList();
    final int expectedVal = elementsPerThread * taskCount;
    for (Result<TimeseriesResultValue> result : results) {
      Assert.assertEquals(elementsPerThread, result.getValue().getLongMetric("rows").intValue());
      for (int i = 0; i < DIMENSION_COUNT; ++i) {
        Assert.assertEquals(
            StringUtils.format("Failed long sum on dimension %d", i),
            expectedVal,
            result.getValue().getLongMetric(StringUtils.format("sumResult%s", i)).intValue()
        );
        Assert.assertEquals(
            StringUtils.format("Failed double sum on dimension %d", i),
            expectedVal,
            result.getValue().getDoubleMetric(StringUtils.format("doubleSumResult%s", i)).intValue()
        );
      }
    }
  }
}
