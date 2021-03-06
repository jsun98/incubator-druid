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

package org.apache.druid.client.indexing;

import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.timeline.DataSegment;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

public class ClientAppendQueryTest
{
  private ClientAppendQuery clientAppendQuery;
  private static final String DATA_SOURCE = "data_source";
  private final DateTime start = DateTimes.nowUtc();
  private List<DataSegment> segments = Collections.singletonList(
      new DataSegment(DATA_SOURCE, new Interval(start, start.plus(1)), start.toString(), null, null, null, null, 0, 0)
  );

  @Before
  public void setUp()
  {
    clientAppendQuery = new ClientAppendQuery(DATA_SOURCE, segments);
  }

  @Test
  public void testGetType()
  {
    Assert.assertEquals("append", clientAppendQuery.getType());
  }

  @Test
  public void testGetDataSource()
  {
    Assert.assertEquals(DATA_SOURCE, clientAppendQuery.getDataSource());
  }

  @Test
  public void testGetSegments()
  {
    Assert.assertEquals(segments, clientAppendQuery.getSegments());
  }

  @Test
  public void testToString()
  {
    Assert.assertTrue(clientAppendQuery.toString().contains(DATA_SOURCE));
    Assert.assertTrue(clientAppendQuery.toString().contains(segments.toString()));
  }
}
