// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.cloud.utils.nio;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import com.cloud.utils.concurrency.NamedThreadFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

public class NettyClient {
    private static final Logger s_logger = Logger.getLogger(NettyClient.class);

    private String host;
    private final int port;
    private final int workers;
    private final HandlerFactory factory;
    private Channel channel;
    private EventLoopGroup workerGroup;
    private Bootstrap bootstrap;
    private boolean connected = false;
    private final BlockingQueue<Runnable> workerQueue;
    protected ExecutorService _executor;

    public NettyClient(String name, String host, int port, int workers, HandlerFactory handlerFactory) {
        this.host = host;
        this.port = port;
        this.workers = workers;
        this.factory = handlerFactory;
        workerQueue = new LinkedBlockingQueue<>(5 * workers);
        _executor = new ThreadPoolExecutor(workers, 5 * workers, 1, TimeUnit.DAYS,
                workerQueue, new NamedThreadFactory(name + "-Handler"), new ThreadPoolExecutor.AbortPolicy());
    }

    public void start() throws InterruptedException {
        workerGroup = new NioEventLoopGroup();
        try {
            bootstrap = new Bootstrap();
            bootstrap.group(workerGroup);
            bootstrap.channel(NioSocketChannel.class);
            bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
            bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline().addLast(new ByteHandler());
                }
            });

            connect(bootstrap, workerGroup);
        } finally {
            workerGroup.shutdownGracefully();
        }
    }

    public static ByteBuf convertToByteBuf(ByteBuffer[] buffers) {
        // Calculate the total length of all ByteBuffer elements
        int totalLength = 0;
        for (ByteBuffer buffer : buffers) {
            totalLength += buffer.remaining();
        }

        // Allocate a new Netty ByteBuf
        ByteBuf nettyByteBuf = Unpooled.buffer(totalLength);

        // Copy data from each ByteBuffer into the Netty ByteBuf
        for (ByteBuffer buffer : buffers) {
            nettyByteBuf.writeBytes(buffer);
        }

        return nettyByteBuf;
    }

    // Method to send bytes to the server
    public void send(ByteBuffer[] data) throws ClosedChannelException {
        if (!channel.isOpen()) {
            throw new ClosedChannelException();
        }
        ByteBuf buf = convertToByteBuf(data);
        channel.writeAndFlush(buf);
    }

    // Method to send bytes to the server
    public void sendBytes(byte[] data) throws ClosedChannelException {
        if (!channel.isOpen()) {
            throw new ClosedChannelException();
        }
        ByteBuf buf = Unpooled.copiedBuffer(data);
        channel.writeAndFlush(buf);
    }

    // Explicit method to close the client connection
    public void stop() {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
        connected = false;
    }

    // Handler to process incoming bytes from the server
    public class ByteHandler extends SimpleChannelInboundHandler<ByteBuf> {

        @Override
        protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
            // Read incoming bytes from the server
            byte[] bytes = new byte[msg.readableBytes()];
            msg.readBytes(bytes);
            final Task task = factory.create(Task.Type.DATA, bytes);
            if (task != null) {
                _executor.submit(task);
            }
        }
    }

    private void connect(Bootstrap b, EventLoopGroup group) {
        b.connect(new InetSocketAddress(host, port)).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                System.out.println("Connected to the server successfully!");
                channel = future.channel();
                final Task task = factory.create(Task.Type.CONNECT, null);
                _executor.submit(task);
                connected = true;
            }
            factory.handleConnect(connected);
        });
    }

    public void reconnect(String host) {
        this.host = host;
        connect(bootstrap, workerGroup);
    }

    public boolean isChannelOpen() {
        return channel != null && channel.isOpen();
    }

    public void schedule(final Task task) throws ClosedChannelException {
        if (!isChannelOpen()) {
            throw new ClosedChannelException();
        }
        try {
            _executor.submit(task);
        } catch (final RejectedExecutionException e) {
            s_logger.warn("Exception occurred when submitting the task", e);
        }
    }
}

