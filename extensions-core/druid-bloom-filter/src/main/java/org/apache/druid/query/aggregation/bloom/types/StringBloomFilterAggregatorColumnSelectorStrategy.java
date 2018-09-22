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

package org.apache.druid.query.aggregation.bloom.types;

import org.apache.druid.segment.DimensionSelector;
import org.apache.hive.common.util.BloomKFilter;

public class StringBloomFilterAggregatorColumnSelectorStrategy
    implements BloomFilterAggregatorColumnSelectorStrategy<DimensionSelector>
{
  @Override
  public void add(DimensionSelector selector, BloomKFilter bloomFilter)
  {
    if (selector.getRow().size() > 1) {
      String[] strings = (String[]) selector.getObject();
      for (String value : strings) {
        if (value == null) {
          bloomFilter.addBytes(null, 0, 0);
        } else {
          bloomFilter.addString(value);
        }
      }
    } else {
      String value = (String) selector.getObject();
      if (value == null) {
        bloomFilter.addBytes(null, 0, 0);
      } else {
        bloomFilter.addString(value);
      }
    }
  }
}
