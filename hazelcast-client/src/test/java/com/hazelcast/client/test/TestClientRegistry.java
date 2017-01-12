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

package com.hazelcast.client.test;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientAwsConfig;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.connection.AddressTranslator;
import com.hazelcast.client.connection.ClientConnectionManager;
import com.hazelcast.client.connection.nio.ClientConnection;
import com.hazelcast.client.connection.nio.ClientConnectionManagerImpl;
import com.hazelcast.client.impl.ClientConnectionManagerFactory;
import com.hazelcast.client.impl.HazelcastClientInstanceImpl;
import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.spi.impl.AwsAddressTranslator;
import com.hazelcast.client.spi.impl.DefaultAddressTranslator;
import com.hazelcast.client.spi.impl.discovery.DiscoveryAddressTranslator;
import com.hazelcast.client.spi.properties.ClientProperty;
import com.hazelcast.core.HazelcastException;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.instance.Node;
import com.hazelcast.instance.NodeState;
import com.hazelcast.instance.TestUtil;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Connection;
import com.hazelcast.nio.ConnectionType;
import com.hazelcast.nio.OutboundFrame;
import com.hazelcast.spi.discovery.integration.DiscoveryService;
import com.hazelcast.spi.exception.TargetDisconnectedException;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.test.mocknetwork.MockConnection;
import com.hazelcast.test.mocknetwork.TestNodeRegistry;
import com.hazelcast.util.ConcurrencyUtil;
import com.hazelcast.util.ConstructorFunction;
import com.hazelcast.util.ExceptionUtil;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;

class TestClientRegistry {

    private static final ILogger LOGGER = Logger.getLogger(HazelcastClient.class);

    private final TestNodeRegistry nodeRegistry;

    TestClientRegistry(TestNodeRegistry nodeRegistry) {
        this.nodeRegistry = nodeRegistry;
    }

    ClientConnectionManagerFactory createClientServiceFactory(String host, AtomicInteger ports) {
        return new MockClientConnectionManagerFactory(host, ports);
    }

    private class MockClientConnectionManagerFactory implements ClientConnectionManagerFactory {

        private final String host;
        private final AtomicInteger ports;

        MockClientConnectionManagerFactory(String host, AtomicInteger ports) {
            this.host = host;
            this.ports = ports;
        }

        @Override
        public ClientConnectionManager createConnectionManager(ClientConfig config, HazelcastClientInstanceImpl client,
                                                               DiscoveryService discoveryService) {
            final ClientAwsConfig awsConfig = config.getNetworkConfig().getAwsConfig();
            AddressTranslator addressTranslator;
            if (awsConfig != null && awsConfig.isEnabled()) {
                try {
                    addressTranslator = new AwsAddressTranslator(awsConfig, client.getLoggingService());
                } catch (NoClassDefFoundError e) {
                    LOGGER.log(Level.WARNING, "hazelcast-aws.jar might be missing!");
                    throw e;
                }
            } else if (discoveryService != null) {
                addressTranslator = new DiscoveryAddressTranslator(discoveryService,
                        client.getProperties().getBoolean(ClientProperty.DISCOVERY_SPI_PUBLIC_IP_ENABLED));
            } else {
                addressTranslator = new DefaultAddressTranslator();
            }
            return new MockClientConnectionManager(client, addressTranslator, host, ports);
        }
    }

    class MockClientConnectionManager extends ClientConnectionManagerImpl {
        private final String host;
        private final AtomicInteger ports;
        private final HazelcastClientInstanceImpl client;
        private final ConcurrentHashMap<Address, TwoWayBlockableExecutor.LockPair> addressBlockMap = new ConcurrentHashMap<Address, TwoWayBlockableExecutor.LockPair>();

        MockClientConnectionManager(HazelcastClientInstanceImpl client, AddressTranslator addressTranslator,
                                    String host, AtomicInteger ports) {
            super(client, addressTranslator);
            this.client = client;
            this.host = host;
            this.ports = ports;
        }

        @Override
        protected void initIOThreads(HazelcastClientInstanceImpl client) {
        }

        @Override
        protected void startIOThreads() {
        }

        @Override
        protected void shutdownIOThreads() {
        }

        @Override
        protected ClientConnection createSocketConnection(Address address) throws IOException {
            if (!alive) {
                throw new HazelcastException("ConnectionManager is not active!!!");
            }
            try {
                HazelcastInstance instance = nodeRegistry.getInstance(address);
                if (instance == null) {
                    throw new IOException("Can not connected to " + address + ": instance does not exist");
                }
                Node node = TestUtil.getNode(instance);
                Address localAddress = new Address(host, ports.incrementAndGet());
                TwoWayBlockableExecutor.LockPair lockPair = ConcurrencyUtil.getOrPutIfAbsent(addressBlockMap, address,
                        new ConstructorFunction<Address, TwoWayBlockableExecutor.LockPair>() {
                    @Override
                    public TwoWayBlockableExecutor.LockPair createNew(Address arg) {
                        return new TwoWayBlockableExecutor.LockPair(new ReentrantReadWriteLock(), new ReentrantReadWriteLock());
                    }
                });

                MockedClientConnection connection = new MockedClientConnection(client,
                        connectionIdGen.incrementAndGet(), node.nodeEngine, address, localAddress, lockPair);
                LOGGER.info("Created connection to endpoint: " + address + ", connection: " + connection);
                return connection;
            } catch (Exception e) {
                throw ExceptionUtil.rethrow(e, IOException.class);
            }
        }

        /**
         * Blocks incoming messages to client from given address
         */
        void blockFrom(Address address) {
            LOGGER.info("Blocked messages from " + address);
            TwoWayBlockableExecutor.LockPair executor = addressBlockMap.get(address);
            executor.blockIncoming();
        }

        /**
         * Unblocks incoming messages to client from given address
         */
        void unblockFrom(Address address) {
            LOGGER.info("Unblocked messages from " + address);
            TwoWayBlockableExecutor.LockPair lockPair = addressBlockMap.get(address);
            lockPair.unblockIncoming();
        }

        /**
         * Blocks outgoing messages from client to given address
         */
        void blockTo(Address address) {
            LOGGER.info("Blocked messages to " + address);
            TwoWayBlockableExecutor.LockPair lockPair = addressBlockMap.get(address);
            lockPair.blockOutgoing();
        }

        /**
         * Unblocks outgoing messages from client to given address
         */
        void unblockTo(Address address) {
            LOGGER.info("Unblocked messages to " + address);
            TwoWayBlockableExecutor.LockPair lockPair = addressBlockMap.get(address);
            lockPair.unblockOutgoing();
        }

    }

    private class MockedClientConnection extends ClientConnection {
        private volatile long lastReadTime;
        private volatile long lastWriteTime;
        private final NodeEngineImpl serverNodeEngine;
        private final Address remoteAddress;
        private final Address localAddress;
        private final Connection serverSideConnection;
        private final TwoWayBlockableExecutor executor;

        MockedClientConnection(HazelcastClientInstanceImpl client,
                               int connectionId, NodeEngineImpl serverNodeEngine,
                               Address address, Address localAddress,
                               TwoWayBlockableExecutor.LockPair lockPair) throws IOException {

            super(client, connectionId);
            this.serverNodeEngine = serverNodeEngine;
            this.remoteAddress = address;
            this.localAddress = localAddress;
            this.executor = new TwoWayBlockableExecutor(lockPair);
            this.serverSideConnection = new MockedNodeConnection(connectionId, remoteAddress,
                    localAddress, serverNodeEngine, this);
        }

        @Override
        public void start() throws IOException {
            // no init for mock connections
        }

        void handleClientMessage(final ClientMessage clientMessage) {
            executor.executeIncoming(new Runnable() {
                @Override
                public void run() {
                    lastReadTime = System.currentTimeMillis();
                    getConnectionManager().handleClientMessage(clientMessage, MockedClientConnection.this);
                }

                @Override
                public String toString() {
                    return "Runnable message " + clientMessage;
                }
            });
        }

        @Override
        public boolean write(final OutboundFrame frame) {
            final Node node = serverNodeEngine.getNode();
            if (node.getState() == NodeState.SHUT_DOWN) {
                return false;
            }
            executor.executeOutgoing(new Runnable() {
                @Override
                public String toString() {
                    return "Runnable message " + frame;
                }

                @Override
                public void run() {
                    ClientMessage newPacket = readFromPacket((ClientMessage) frame);
                    lastWriteTime = System.currentTimeMillis();
                    node.clientEngine.handleClientMessage(newPacket, serverSideConnection);


                }
            });
            return true;
        }

        private ClientMessage readFromPacket(ClientMessage packet) {
            return ClientMessage.createForDecode(packet.buffer(), 0);
        }

        @Override
        public long lastReadTimeMillis() {
            return lastReadTime;
        }

        @Override
        public long lastWriteTimeMillis() {
            return lastWriteTime;
        }

        @Override
        public InetAddress getInetAddress() {
            try {
                return remoteAddress.getInetAddress();
            } catch (UnknownHostException e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        public InetSocketAddress getRemoteSocketAddress() {
            try {
                return remoteAddress.getInetSocketAddress();
            } catch (UnknownHostException e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        public int getPort() {
            return remoteAddress.getPort();
        }

        @Override
        public InetSocketAddress getLocalSocketAddress() {
            try {
                return localAddress.getInetSocketAddress();
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        protected void innerClose() throws IOException {
            executor.executeOutgoing((new Runnable() {
                @Override
                public void run() {
                    serverSideConnection.close(null, null);

                }

                @Override
                public String toString() {
                    return "Client Closed EOF";
                }
            }));
            executor.shutdownIncoming();

        }

        void onServerClose(final String reason) {
            executor.executeIncoming(new Runnable() {
                @Override
                public String toString() {
                    return "Server Closed EOF";
                }

                @Override
                public void run() {
                    MockedClientConnection.this.close(reason, new TargetDisconnectedException("Mocked Remote socket closed"));
                }
            });
            executor.shutdownOutgoing();
        }
    }

    private class MockedNodeConnection extends MockConnection {

        private final MockedClientConnection responseConnection;
        private final int connectionId;

        MockedNodeConnection(int connectionId, Address localEndpoint, Address remoteEndpoint, NodeEngineImpl nodeEngine,
                             MockedClientConnection responseConnection) {
            super(localEndpoint, remoteEndpoint, nodeEngine);
            this.responseConnection = responseConnection;
            this.connectionId = connectionId;
            register();
        }

        private void register() {
            Node node = nodeEngine.getNode();
            node.getConnectionManager().registerConnection(getEndPoint(), this);
        }

        @Override
        public boolean write(OutboundFrame frame) {
            final ClientMessage packet = (ClientMessage) frame;
            if (isAlive()) {
                ClientMessage newPacket = readFromPacket(packet);
                responseConnection.handleClientMessage(newPacket);
                return true;
            }
            return false;
        }

        @Override
        public boolean isClient() {
            return true;
        }

        private ClientMessage readFromPacket(ClientMessage packet) {
            return ClientMessage.createForDecode(packet.buffer(), 0);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            MockedNodeConnection that = (MockedNodeConnection) o;

            if (connectionId != that.connectionId) {
                return false;
            }
            Address remoteEndpoint = getEndPoint();
            return !(remoteEndpoint != null ? !remoteEndpoint.equals(that.getEndPoint()) : that.getEndPoint() != null);
        }

        @Override
        public void close(String reason, Throwable cause) {
            super.close(reason, cause);
            responseConnection.onServerClose(reason);
        }

        @Override
        public int hashCode() {
            int result = connectionId;
            Address remoteEndpoint = getEndPoint();
            result = 31 * result + (remoteEndpoint != null ? remoteEndpoint.hashCode() : 0);
            return result;
        }

        @Override
        public ConnectionType getType() {
            return ConnectionType.JAVA_CLIENT;
        }

        @Override
        public String toString() {
            return "MockedNodeConnection{"
                    + " remoteEndpoint = " + getEndPoint()
                    + ", localEndpoint = " + localEndpoint
                    + ", connectionId = " + connectionId
                    + '}';
        }
    }
}
