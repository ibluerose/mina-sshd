/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.common.io.nio2;

import java.io.IOException;
import java.net.SocketOption;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.NetworkChannel;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.sshd.common.Closeable;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.FactoryManagerHolder;
import org.apache.sshd.common.PropertyResolver;
import org.apache.sshd.common.io.IoHandler;
import org.apache.sshd.common.io.IoService;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.Pair;
import org.apache.sshd.common.util.closeable.AbstractInnerCloseable;

/**
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public abstract class Nio2Service extends AbstractInnerCloseable implements IoService, FactoryManagerHolder {
    public static final Map<String, Pair<SocketOption<?>, Object>> CONFIGURABLE_OPTIONS =
            Collections.unmodifiableMap(new LinkedHashMap<String, Pair<SocketOption<?>, Object>>() {
                // Not serializing it
                private static final long serialVersionUID = 1L;

                {
                    put(FactoryManager.SOCKET_KEEPALIVE, new Pair<SocketOption<?>, Object>(StandardSocketOptions.SO_KEEPALIVE, null));
                    put(FactoryManager.SOCKET_LINGER, new Pair<SocketOption<?>, Object>(StandardSocketOptions.SO_LINGER, null));
                    put(FactoryManager.SOCKET_RCVBUF, new Pair<SocketOption<?>, Object>(StandardSocketOptions.SO_RCVBUF, null));
                    put(FactoryManager.SOCKET_REUSEADDR, new Pair<SocketOption<?>, Object>(StandardSocketOptions.SO_REUSEADDR, DEFAULT_REUSE_ADDRESS));
                    put(FactoryManager.SOCKET_SNDBUF, new Pair<SocketOption<?>, Object>(StandardSocketOptions.SO_SNDBUF, null));
                    put(FactoryManager.TCP_NODELAY, new Pair<SocketOption<?>, Object>(StandardSocketOptions.TCP_NODELAY, null));
                }
            });

    protected final Map<Long, IoSession> sessions;
    protected final AtomicBoolean disposing = new AtomicBoolean();
    private final FactoryManager manager;
    private final IoHandler handler;
    private final AsynchronousChannelGroup group;

    protected Nio2Service(FactoryManager manager, IoHandler handler, AsynchronousChannelGroup group) {
        if (log.isTraceEnabled()) {
            log.trace("Creating {}", getClass().getSimpleName());
        }
        this.manager = Objects.requireNonNull(manager, "No factory manager provided");
        this.handler = Objects.requireNonNull(handler, "No I/O handler provided");
        this.group = Objects.requireNonNull(group, "No async. channel group provided");
        this.sessions = new ConcurrentHashMap<>();
    }

    protected AsynchronousChannelGroup getChannelGroup() {
        return group;
    }

    @Override
    public FactoryManager getFactoryManager() {
        return manager;
    }

    public IoHandler getIoHandler() {
        return handler;
    }

    public void dispose() {
        try {
            long maxWait = Closeable.getMaxCloseWaitTime(getFactoryManager());
            boolean successful = close(true).await(maxWait);
            if (!successful) {
                throw new SocketTimeoutException("Failed to receive closure confirmation within " + maxWait + " millis");
            }
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug(e.getClass().getSimpleName() + " while stopping service: " + e.getMessage());
            }

            if (log.isTraceEnabled()) {
                log.trace("Stop exception details", e);
            }
        }
    }

    @Override
    protected Closeable getInnerCloseable() {
        return builder().parallel(sessions.values()).build();
    }

    @Override
    public Map<Long, IoSession> getManagedSessions() {
        return Collections.unmodifiableMap(sessions);
    }

    public void sessionClosed(Nio2Session session) {
        sessions.remove(session.getId());
    }

    @SuppressWarnings("unchecked")
    protected <S extends NetworkChannel> S setSocketOptions(S socket) throws IOException {
        Collection<? extends SocketOption<?>> supported = socket.supportedOptions();
        if (GenericUtils.isEmpty(supported)) {
            return socket;
        }

        for (Map.Entry<String, Pair<SocketOption<?>, Object>> ce : CONFIGURABLE_OPTIONS.entrySet()) {
            String property = ce.getKey();
            Pair<SocketOption<?>, Object> defConfig = ce.getValue();
            @SuppressWarnings("rawtypes")
            SocketOption option = defConfig.getKey();
            setOption(socket, property, option, defConfig.getValue());
        }

        return socket;
    }

    protected <T> boolean setOption(NetworkChannel socket, String property, SocketOption<T> option, T defaultValue) throws IOException {
        PropertyResolver manager = getFactoryManager();
        String valStr = manager.getString(property);
        T val = defaultValue;
        if (!GenericUtils.isEmpty(valStr)) {
            Class<T> type = option.type();
            if (type == Integer.class) {
                val = type.cast(Integer.valueOf(valStr));
            } else if (type == Boolean.class) {
                val = type.cast(Boolean.valueOf(valStr));
            } else {
                throw new IllegalStateException("Unsupported socket option type (" + type + ") " + property + "=" + valStr);
            }
        }

        if (val == null) {
            return false;
        }

        Collection<? extends SocketOption<?>> supported = socket.supportedOptions();
        if (GenericUtils.isEmpty(supported) || (!supported.contains(option))) {
            if (log.isDebugEnabled()) {
                log.debug("Unsupported socket option ({}) to set using {}={}", option, property, val);
            }
            return false;
        }

        try {
            socket.setOption(option, val);
            if (log.isDebugEnabled()) {
                log.debug("setOption({})[{}] from property={}", option, val, property);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Unable (" + e.getClass().getSimpleName() + ")"
                   + " to set socket option " + option
                   + " using property " + property + "=" + val
                   + ": " + e.getMessage());
            return false;
        }
    }
}
