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

package org.apache.druid.query.aggregation.bloom;

import org.apache.druid.query.aggregation.Aggregator;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.AggregatorUtil;
import org.apache.druid.query.aggregation.BufferAggregator;
import org.apache.druid.query.aggregation.NoopAggregator;
import org.apache.druid.query.aggregation.NoopBufferAggregator;
import org.apache.druid.query.cache.CacheKeyBuilder;
import org.apache.druid.segment.ColumnSelectorFactory;
import org.apache.druid.segment.ColumnValueSelector;
import org.apache.druid.segment.NilColumnValueSelector;
import org.apache.hive.common.util.BloomKFilter;

import java.util.Collections;
import java.util.List;

public class BloomFilterMergeAggregatorFactory extends BloomFilterAggregatorFactory
{
  private final String fieldName;

  BloomFilterMergeAggregatorFactory(String name, String field, Integer maxNumEntries)
  {
    super(name, null, maxNumEntries);
    this.fieldName = field;
  }

  @Override
  public Aggregator factorize(final ColumnSelectorFactory metricFactory)
  {
    final ColumnValueSelector<BloomKFilter> selector = metricFactory.makeColumnValueSelector(fieldName);
    if (selector instanceof NilColumnValueSelector) {
      return NoopAggregator.instance();
    }
    return new BloomFilterMergeAggregator(selector, getMaxNumEntries());
  }

  @Override
  public BufferAggregator factorizeBuffered(final ColumnSelectorFactory metricFactory)
  {
    final ColumnValueSelector<BloomKFilter> selector = metricFactory.makeColumnValueSelector(fieldName);
    if (selector instanceof NilColumnValueSelector) {
      return NoopBufferAggregator.instance();
    }
    return new BloomFilterMergeBufferAggregator(selector, getMaxNumEntries());
  }

  @Override
  public List<AggregatorFactory> getRequiredColumns()
  {
    return Collections.singletonList(new BloomFilterMergeAggregatorFactory(getName(), fieldName, getMaxNumEntries()));
  }

  @Override
  public byte[] getCacheKey()
  {
    return new CacheKeyBuilder(AggregatorUtil.BLOOM_FILTER_MERGE_CACHE_TYPE_ID)
        .appendString(fieldName)
        .appendInt(getMaxNumEntries())
        .build();
  }
}
