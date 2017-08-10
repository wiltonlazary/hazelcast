/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client.spi;

import com.hazelcast.core.Partition;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.serialization.Data;

/**
 * Partition service for Hazelcast clients.
 *
 * Allows to retrieve information about the partition count, the partition owner or the partitionId of a key.
 */
public interface ClientPartitionService {

    Address getPartitionOwner(int partitionId);

    int getPartitionId(Data key);

    int getPartitionId(Object key);

    int getPartitionCount();

    Partition getPartition(int partitionId);
}
