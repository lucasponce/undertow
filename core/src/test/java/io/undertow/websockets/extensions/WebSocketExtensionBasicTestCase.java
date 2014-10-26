/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.undertow.websockets.extensions;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.undertow.Undertow;
import io.undertow.testutils.HttpOneOnly;
import io.undertow.util.StringWriteChannelListener;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketExtension;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.client.WebSocketClient;
import io.undertow.websockets.client.WebSocketClientNegotiation;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedBinaryMessage;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.StreamSinkFrameChannel;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSocketFrameType;
import io.undertow.websockets.core.WebSocketLogger;
import io.undertow.websockets.core.WebSocketVersion;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import org.junit.Assert;
import org.junit.Test;
import org.xnio.BufferAllocator;
import org.xnio.ByteBufferSlicePool;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Pool;
import org.xnio.Xnio;
import org.xnio.XnioWorker;

import static io.undertow.Handlers.path;

/**
 *
 * A test class for WebSocket client scenarios with extensions.
 *
 * @author Lucas Ponce
 */
@HttpOneOnly
public class WebSocketExtensionBasicTestCase {

    final Pool<ByteBuffer> buffer = new ByteBufferSlicePool(BufferAllocator.BYTE_BUFFER_ALLOCATOR, 1024, 1024);

    public static WebSocketProtocolHandshakeHandler webSocketDebugHandler() {
        return new WebSocketProtocolHandshakeHandler(new WebSocketConnectionCallback() {
            @Override
            public void onConnect(final WebSocketHttpExchange exchange, final WebSocketChannel channel) {
                WebSocketLogger.EXTENSION_LOGGER.info("onConnect() ");
                channel.getReceiveSetter().set(new DebugExtensionsListener());
                channel.resumeReceives();
            }
        });
    }

    @Test
    public void testLongTextMessage() throws Exception {

        Undertow server;
        XnioWorker client;

        Xnio xnio = Xnio.getInstance(WebSocketExtensionBasicTestCase.class.getClassLoader());
        client = xnio.createWorker(OptionMap.builder()
                .set(Options.WORKER_IO_THREADS, 2)
                .set(Options.CONNECTION_HIGH_WATER, 1000000)
                .set(Options.CONNECTION_LOW_WATER, 1000000)
                .set(Options.WORKER_TASK_CORE_THREADS, 30)
                .set(Options.WORKER_TASK_MAX_THREADS, 30)
                .set(Options.TCP_NODELAY, true)
                .set(Options.CORK, true)
                .getMap());

        WebSocketProtocolHandshakeHandler handler = webSocketDebugHandler()
                .addExtension(new PerMessageDeflateExtension());

        DebugExtensionsHeaderHandler debug = new DebugExtensionsHeaderHandler(handler);

        server = Undertow.builder()
                .addHttpListener(8080, "localhost")
                .setHandler(path().addPrefixPath("/", debug))
                .build();
        server.start();

        final String SEC_WEBSOCKET_EXTENSIONS = "permessage-deflate; client_no_context_takeover; client_max_window_bits";
        List<WebSocketExtension> extensions = WebSocketExtension.parse(SEC_WEBSOCKET_EXTENSIONS);

        final WebSocketClientNegotiation negotiation = new WebSocketClientNegotiation(null, extensions);

        WebSocketClient wsClient = new WebSocketClient();
        wsClient.addExtension(new PerMessageDeflateExtension(true));
        final WebSocketChannel clientChannel = wsClient.connect(client, buffer, OptionMap.EMPTY, new URI("http://localhost:8080"), WebSocketVersion.V13, negotiation).get();

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>();

        clientChannel.getReceiveSetter().set(new AbstractReceiveListener() {
            @Override
            protected void onFullTextMessage(WebSocketChannel channel, BufferedTextMessage message) throws IOException {
                String data = message.getData();
                WebSocketLogger.ROOT_LOGGER.info("onFullTextMessage() - Client - Received: " + data.getBytes().length + " bytes.");
                result.set(data);
                latch.countDown();
            }

            @Override
            protected void onFullCloseMessage(WebSocketChannel channel, BufferedBinaryMessage message) throws IOException {
                WebSocketLogger.ROOT_LOGGER.info("onFullCloseMessage");
            }

            @Override
            protected void onError(WebSocketChannel channel, Throwable error) {
                WebSocketLogger.ROOT_LOGGER.info("onError");
                super.onError(channel, error);
                error.printStackTrace();
                latch.countDown();
            }

        });
        clientChannel.resumeReceives();

        int LONG_MSG = 132 * 1024;
        StringBuilder longMsg = new StringBuilder(LONG_MSG);

        for (int i = 0; i < LONG_MSG; i++) {
            longMsg.append(new Integer(i).toString().charAt(0));
        }

        StreamSinkFrameChannel sendChannel = clientChannel.send(WebSocketFrameType.TEXT, LONG_MSG);
        new StringWriteChannelListener(longMsg.toString()).setup(sendChannel);

        latch.await(10, TimeUnit.SECONDS);
        Assert.assertEquals(longMsg.toString(), result.get());
        clientChannel.sendClose();

        client.shutdown();
        server.stop();
    }

    @Test
    public void testLongMessageWithoutExtensions() throws Exception {

        Undertow server;
        XnioWorker client;

        Xnio xnio = Xnio.getInstance(WebSocketExtensionBasicTestCase.class.getClassLoader());
        client = xnio.createWorker(OptionMap.builder()
                .set(Options.WORKER_IO_THREADS, 2)
                .set(Options.CONNECTION_HIGH_WATER, 1000000)
                .set(Options.CONNECTION_LOW_WATER, 1000000)
                .set(Options.WORKER_TASK_CORE_THREADS, 30)
                .set(Options.WORKER_TASK_MAX_THREADS, 30)
                .set(Options.TCP_NODELAY, true)
                .set(Options.CORK, true)
                .getMap());


        WebSocketProtocolHandshakeHandler handler = webSocketDebugHandler()
                .addExtension(new PerMessageDeflateExtension());

        DebugExtensionsHeaderHandler debug = new DebugExtensionsHeaderHandler(handler);

        server = Undertow.builder()
                .addHttpListener(8080, "localhost")
                .setHandler(path().addPrefixPath("/", debug))
                .build();
        server.start();

        final WebSocketClientNegotiation negotiation = null;

        WebSocketClient wsClient = new WebSocketClient();
        final WebSocketChannel clientChannel = wsClient.connect(client, buffer, OptionMap.EMPTY, new URI("http://localhost:8080"), WebSocketVersion.V13, negotiation).get();

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>();

        clientChannel.getReceiveSetter().set(new AbstractReceiveListener() {
            @Override
            protected void onFullTextMessage(WebSocketChannel channel, BufferedTextMessage message) throws IOException {
                String data = message.getData();
                WebSocketLogger.ROOT_LOGGER.info("onFullTextMessage() - Client - Received: " + data.getBytes().length + " bytes");
                result.set(data);
                latch.countDown();
            }

            @Override
            protected void onFullCloseMessage(WebSocketChannel channel, BufferedBinaryMessage message) throws IOException {
                WebSocketLogger.ROOT_LOGGER.info("onFullCloseMessage");
            }

            @Override
            protected void onError(WebSocketChannel channel, Throwable error) {
                WebSocketLogger.ROOT_LOGGER.info("onError");
                super.onError(channel, error);
                error.printStackTrace();
                latch.countDown();
            }

        });
        clientChannel.resumeReceives();

        int LONG_MSG = 75 * 1024;
        StringBuilder longMsg = new StringBuilder(LONG_MSG);

        for (int i = 0; i < LONG_MSG; i++) {
            longMsg.append(new Integer(i).toString().charAt(0));
        }

        StreamSinkFrameChannel sendChannel = clientChannel.send(WebSocketFrameType.TEXT, LONG_MSG);
        new StringWriteChannelListener(longMsg.toString()).setup(sendChannel);

        latch.await(10, TimeUnit.SECONDS);

        Assert.assertEquals(longMsg.toString(), result.get());
        clientChannel.sendClose();

        client.shutdown();
        server.stop();
    }
}
