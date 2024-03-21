/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.internal.common.util;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.TransportType;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.unix.DomainSocketChannel;
import io.netty.channel.unix.ServerDomainSocketChannel;
import io.netty.util.Version;

/**
 * Provides the properties required by {@link TransportType} by loading /dev/epoll and io_uring transport
 * classes dynamically, so that Armeria does not have hard dependencies on them.
 * See: <a href="https://github.com/line/armeria/issues/3243">line/armeria#3243</a>
 */
public final class TransportTypeProvider {

    private static final Logger logger = LoggerFactory.getLogger(TransportTypeProvider.class);

    static {
        if (Flags.warnNettyVersions()) {
            final String howToDisableWarning =
                    "This means 1) you specified Netty versions inconsistently in your build or " +
                    "2) the Netty JARs in the classpath were repackaged or shaded incorrectly. " +
                    "Specify the '-Dcom.linecorp.armeria.warnNettyVersions=false' JVM option to " +
                    "disable this warning at the risk of unexpected Netty behavior, if you think " +
                    "it is a false positive.";

            final Map<String, Version> nettyVersions =
                    Version.identify(TransportTypeProvider.class.getClassLoader());

            final Set<String> distinctNettyVersions = nettyVersions.values().stream().filter(v -> {
                final String artifactId = v.artifactId();
                return artifactId != null &&
                       artifactId.startsWith("netty") &&
                       !artifactId.startsWith("netty-incubator") &&
                       !artifactId.startsWith("netty-tcnative");
            }).map(Version::artifactVersion).collect(toImmutableSet());

            switch (distinctNettyVersions.size()) {
                case 0:
                    logger.warn("Using Netty with unknown version. {}", howToDisableWarning);
                    break;
                case 1:
                    if (logger.isDebugEnabled()) {
                        logger.debug("Using Netty {}", distinctNettyVersions.iterator().next());
                    }
                    break;
                default:
                    logger.warn("Inconsistent Netty versions detected: {} {}",
                                nettyVersions, howToDisableWarning);
            }
        }
    }

    public static final TransportTypeProvider NIO = new TransportTypeProvider(
            "NIO", NioServerSocketChannel.class, NioSocketChannel.class, null, null, NioDatagramChannel.class,
            NioEventLoopGroup.class, NioEventLoop.class, NioEventLoopGroup::new, null);

    public static final TransportTypeProvider EPOLL = of(
            "EPOLL",
            ChannelUtil.channelPackageName(),
            ".epoll.Epoll",
            ".epoll.EpollServerSocketChannel",
            ".epoll.EpollSocketChannel",
            ".epoll.EpollServerDomainSocketChannel",
            ".epoll.EpollDomainSocketChannel",
            ".epoll.EpollDatagramChannel",
            ".epoll.EpollEventLoopGroup",
            ".epoll.EpollEventLoop");

    public static final TransportTypeProvider KQUEUE = of(
            "KQUEUE",
            ChannelUtil.channelPackageName(),
            ".kqueue.KQueue",
            ".kqueue.KQueueServerSocketChannel",
            ".kqueue.KQueueSocketChannel",
            ".kqueue.KQueueServerDomainSocketChannel",
            ".kqueue.KQueueDomainSocketChannel",
            ".kqueue.KQueueDatagramChannel",
            ".kqueue.KQueueEventLoopGroup",
            ".kqueue.KQueueEventLoop");

    public static final TransportTypeProvider IO_URING = of(
            "IO_URING",
            ChannelUtil.incubatorChannelPackageName(),
            ".uring.IOUring",
            ".uring.IOUringServerSocketChannel",
            ".uring.IOUringSocketChannel",
            null, null,
            ".uring.IOUringDatagramChannel",
            ".uring.IOUringEventLoopGroup",
            ".uring.IOUringEventLoop");

    private static TransportTypeProvider of(
            String name, @Nullable String channelPackageName, String entryPointTypeName,
            String serverSocketChannelTypeName, String socketChannelTypeName,
            @Nullable String domainServerSocketChannelTypeName, @Nullable String domainSocketChannelTypeName,
            String datagramChannelTypeName,
            String eventLoopGroupTypeName, String eventLoopTypeName) {

        if (channelPackageName == null) {
            return new TransportTypeProvider(
                    name, null, null, null, null, null, null, null, null,
                    new IllegalStateException("Failed to determine the shaded package name"));
        }

        // TODO(trustin): Do not try to load io_uring unless explicitly specified so JVM doesn't crash.
        //                https://github.com/netty/netty-incubator-transport-io_uring/issues/92
        if ("IO_URING".equals(name) && !"io_uring".equals(Ascii.toLowerCase(
                System.getProperty("com.linecorp.armeria.transportType", "")))) {
            return new TransportTypeProvider(
                    name, null, null, null, null, null, null, null, null,
                    new IllegalStateException("io_uring not enabled explicitly"));
        }

        try {
            // Make sure the native libraries were loaded.
            final Throwable unavailabilityCause = (Throwable)
                    findClass(channelPackageName, entryPointTypeName)
                            .getMethod("unavailabilityCause")
                            .invoke(null);

            if (unavailabilityCause != null) {
                throw unavailabilityCause;
            }

            // Load the required classes and constructors.
            final Class<? extends ServerSocketChannel> ssc =
                    findClass(channelPackageName, serverSocketChannelTypeName);
            final Class<? extends SocketChannel> sc =
                    findClass(channelPackageName, socketChannelTypeName);

            final Class<? extends ServerDomainSocketChannel> sdsc =
                    findClass(channelPackageName, domainServerSocketChannelTypeName);
            final Class<? extends DomainSocketChannel> dsc =
                    findClass(channelPackageName, domainSocketChannelTypeName);

            final Class<? extends DatagramChannel> dc =
                    findClass(channelPackageName, datagramChannelTypeName);

            final Class<? extends EventLoopGroup> elg =
                    findClass(channelPackageName, eventLoopGroupTypeName);
            final Class<? extends EventLoop> el =
                    findClass(channelPackageName, eventLoopTypeName);
            final BiFunction<Integer, ThreadFactory, ? extends EventLoopGroup> elgc =
                    findEventLoopGroupConstructor(elg);

            return new TransportTypeProvider(name, ssc, sc, sdsc, dsc, dc, elg, el, elgc, null);
        } catch (Throwable cause) {
            return new TransportTypeProvider(name, null, null, null, null, null, null, null, null,
                                             Exceptions.peel(cause));
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static <T> Class<T> findClass(String channelPackageName,
                                          @Nullable String className) throws Exception {
        if (className == null) {
            return null;
        }

        return (Class<T>) Class.forName(channelPackageName + className, false,
                                        TransportTypeProvider.class.getClassLoader());
    }

    private static BiFunction<Integer, ThreadFactory, ? extends EventLoopGroup> findEventLoopGroupConstructor(
            Class<? extends EventLoopGroup> eventLoopGroupType) throws Exception {
        final MethodHandle constructor =
                MethodHandles.lookup().unreflectConstructor(
                        eventLoopGroupType.getConstructor(int.class, ThreadFactory.class));

        return (nThreads, threadFactory) -> {
            try {
                return (EventLoopGroup) constructor.invoke(nThreads, threadFactory);
            } catch (Throwable t) {
                return Exceptions.throwUnsafely(Exceptions.peel(t));
            }
        };
    }

    private final String name;
    @Nullable
    private final Class<? extends ServerSocketChannel> serverChannelType;
    @Nullable
    private final Class<? extends SocketChannel> socketChannelType;
    @Nullable
    private final Class<? extends ServerDomainSocketChannel> domainServerChannelType;
    @Nullable
    private final Class<? extends DomainSocketChannel> domainSocketChannelType;
    @Nullable
    private final Class<? extends DatagramChannel> datagramChannelType;
    @Nullable
    private final Class<? extends EventLoopGroup> eventLoopGroupType;
    @Nullable
    private final Class<? extends EventLoop> eventLoopType;
    @Nullable
    private final BiFunction<Integer, ThreadFactory, ? extends EventLoopGroup> eventLoopGroupConstructor;
    @Nullable
    private final Throwable unavailabilityCause;

    private TransportTypeProvider(
            String name,
            @Nullable
            Class<? extends ServerSocketChannel> serverChannelType,
            @Nullable
            Class<? extends SocketChannel> socketChannelType,
            @Nullable
            Class<? extends ServerDomainSocketChannel> domainServerChannelType,
            @Nullable
            Class<? extends DomainSocketChannel> domainSocketChannelType,
            @Nullable
            Class<? extends DatagramChannel> datagramChannelType,
            @Nullable
            Class<? extends EventLoopGroup> eventLoopGroupType,
            @Nullable
            Class<? extends EventLoop> eventLoopType,
            @Nullable
            BiFunction<Integer, ThreadFactory, ? extends EventLoopGroup> eventLoopGroupConstructor,
            @Nullable
            Throwable unavailabilityCause) {

        assert (serverChannelType == null &&
                socketChannelType == null &&
                domainServerChannelType == null &&
                domainSocketChannelType == null &&
                datagramChannelType == null &&
                eventLoopGroupType == null &&
                eventLoopType == null &&
                eventLoopGroupConstructor == null &&
                unavailabilityCause != null) ||
               (serverChannelType != null &&
                socketChannelType != null &&
                datagramChannelType != null &&
                eventLoopGroupType != null &&
                eventLoopType != null &&
                eventLoopGroupConstructor != null &&
                unavailabilityCause == null);

        assert domainServerChannelType != null && domainSocketChannelType != null ||
               domainServerChannelType == null && domainSocketChannelType == null
                : domainServerChannelType + ", " + domainSocketChannelType;

        this.name = name;
        this.serverChannelType = serverChannelType;
        this.socketChannelType = socketChannelType;
        this.domainServerChannelType = domainServerChannelType;
        this.domainSocketChannelType = domainSocketChannelType;
        this.datagramChannelType = datagramChannelType;
        this.eventLoopGroupType = eventLoopGroupType;
        this.eventLoopType = eventLoopType;
        this.eventLoopGroupConstructor = eventLoopGroupConstructor;
        this.unavailabilityCause = unavailabilityCause;
    }

    public Class<? extends ServerSocketChannel> serverChannelType() {
        return ensureSupported(serverChannelType);
    }

    public Class<? extends SocketChannel> socketChannelType() {
        return ensureSupported(socketChannelType);
    }

    public boolean supportsDomainSockets() {
        return domainSocketChannelType != null;
    }

    public Class<? extends ServerDomainSocketChannel> domainServerChannelType() {
        return ensureSupported(domainServerChannelType);
    }

    public Class<? extends DomainSocketChannel> domainSocketChannelType() {
        return ensureSupported(domainSocketChannelType);
    }

    public Class<? extends DatagramChannel> datagramChannelType() {
        return ensureSupported(datagramChannelType);
    }

    public Class<? extends EventLoopGroup> eventLoopGroupType() {
        return ensureSupported(eventLoopGroupType);
    }

    public Class<? extends EventLoop> eventLoopType() {
        return ensureSupported(eventLoopType);
    }

    public BiFunction<Integer, ThreadFactory, ? extends EventLoopGroup> eventLoopGroupConstructor() {
        return ensureSupported(eventLoopGroupConstructor);
    }

    @Nullable
    public Throwable unavailabilityCause() {
        return unavailabilityCause;
    }

    private <T> T ensureSupported(@Nullable T value) {
        if (value == null) {
            throw new IllegalStateException(
                    "transport '" + name + "' not available: " + unavailabilityCause, unavailabilityCause);
        }
        return value;
    }
}
