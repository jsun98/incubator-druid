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

package org.apache.druid.indexing.kafka;

import org.apache.druid.indexing.seekablestream.common.OrderedSequenceNumber;

import javax.validation.constraints.NotNull;

public class KafkaSequenceNumber extends OrderedSequenceNumber<Long>
{
  private KafkaSequenceNumber(Long sequenceNumber, boolean isExclusive)
  {
    super(sequenceNumber, false);
  }

  public static KafkaSequenceNumber of(Long sequenceNumber)
  {
    return new KafkaSequenceNumber(sequenceNumber, false);
  }

  @Override
  public int compareTo(
      @NotNull OrderedSequenceNumber<Long> o
  )
  {
    return this.get().compareTo(o.get());
  }

  @Override
  public boolean equals(Object o)
  {
    if (!(o instanceof KafkaSequenceNumber)) {
      return false;
    }
    return this.compareTo((KafkaSequenceNumber) o) == 0;
  }

  @Override
  public int hashCode()
  {
    return super.hashCode();
  }


}
