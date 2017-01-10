/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.client.cache.impl.nearcache.invalidation;


import com.hazelcast.cache.impl.operation.CacheGetInvalidationMetaDataOperation;
import com.hazelcast.client.impl.HazelcastClientInstanceImpl;
import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.protocol.codec.CacheAssignAndGetUuidsCodec;
import com.hazelcast.client.spi.ClientClusterService;
import com.hazelcast.client.spi.ClientContext;
import com.hazelcast.client.spi.impl.ClientInvocation;
import com.hazelcast.core.Member;
import com.hazelcast.internal.nearcache.impl.invalidation.MetaDataFetcher;
import com.hazelcast.internal.nearcache.impl.invalidation.RepairingHandler;
import com.hazelcast.logging.Logger;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.spi.InternalCompletableFuture;
import com.hazelcast.spi.serialization.SerializationService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static com.hazelcast.client.impl.protocol.codec.CacheFetchNearCacheInvalidationMetadataCodec.decodeResponse;
import static com.hazelcast.client.impl.protocol.codec.CacheFetchNearCacheInvalidationMetadataCodec.encodeRequest;
import static com.hazelcast.cluster.memberselector.MemberSelectors.DATA_MEMBER_SELECTOR;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.logging.Level.WARNING;

/**
 * {@code MetaDataFetcher} for client side usage
 */
public class ClientCacheMetaDataFetcher extends MetaDataFetcher {

    private final ClientClusterService clusterService;
    private final SerializationService serializationService;
    private final HazelcastClientInstanceImpl clientImpl;

    public ClientCacheMetaDataFetcher(ClientContext clientContext) {
        super(Logger.getLogger(ClientCacheMetaDataFetcher.class));
        this.clusterService = clientContext.getClusterService();
        this.serializationService = clientContext.getSerializationService();
        this.clientImpl = (HazelcastClientInstanceImpl) clientContext.getHazelcastInstance();
    }

    @Override
    protected List<InternalCompletableFuture> scanMembers(List<String> names) {
        Collection<Member> members = clusterService.getMembers(DATA_MEMBER_SELECTOR);
        List<InternalCompletableFuture> futures = new ArrayList<InternalCompletableFuture>(members.size());

        for (Member member : members) {
            ClientMessage message = encodeRequest(names, member.getAddress());
            ClientInvocation invocation = new ClientInvocation(clientImpl, message, member.getAddress());
            futures.add(invocation.invoke());
        }

        return futures;
    }

    @Override
    protected void process(InternalCompletableFuture future, ConcurrentMap<String, RepairingHandler> handlers) {
        try {
            CacheGetInvalidationMetaDataOperation.MetaDataResponse response = extractResponse(future);
            repairUuids(response.getPartitionUuidList(), handlers);
            repairSequences(response.getNamePartitionSequenceList(), handlers);
        } catch (Exception e) {
            if (logger.isLoggable(WARNING)) {
                logger.log(WARNING, "Cant fetch invalidation meta-data [" + e.getMessage() + "]");
            }
        }
    }

    private CacheGetInvalidationMetaDataOperation.MetaDataResponse extractResponse(InternalCompletableFuture future)
            throws InterruptedException, ExecutionException, TimeoutException {

        ClientMessage message = ((ClientMessage) future.get(1, MINUTES));
        return serializationService.toObject(decodeResponse(message).response);
    }

    @Override
    public List<Object> assignAndGetUuids() throws Exception {
        ClientMessage request = CacheAssignAndGetUuidsCodec.encodeRequest();
        ClientInvocation invocation = new ClientInvocation(clientImpl, request);
        CacheAssignAndGetUuidsCodec.ResponseParameters responseParameters
                = CacheAssignAndGetUuidsCodec.decodeResponse(invocation.invoke().get());
        List<Data> response = responseParameters.response;
        List<Object> objects = new ArrayList<Object>(response.size());
        for (Data data : response) {
            objects.add(serializationService.toObject(data));
        }
        return objects;
    }
}
