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
 * A test class for WebSocket Extensions headers.
 *
 * @author Lucas Ponce
 */
public class WebSocketExtensionHeadersTest {

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

    /**
     * Simulate an extensions request.
     *
     * <pre>{@code

            GET / HTTP/1.1
            User-Agent: AutobahnTestSuite/0.7.0-0.9.0
            Host: localhost:7777
            Upgrade: WebSocket
            Connection: Upgrade
            Pragma: no-cache
            Cache-Control: no-cache
            Sec-WebSocket-Key: pRAuwtkO0SUKzufqA2g+ig==
            Sec-WebSocket-Extensions: permessage-deflate; client_no_context_takeover; client_max_window_bits
            Sec-WebSocket-Version: 13

     * }
     * </pre>
     */
    @Test
    public void testExtensionsHeaders() throws Exception {

        Undertow server;
        XnioWorker client;

        Xnio xnio = Xnio.getInstance(WebSocketExtensionHeadersTest.class.getClassLoader());
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
        final String SEC_WEBSOCKET_EXTENSIONS_EXPECTED = "[permessage-deflate; client_no_context_takeover]";  // List format
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
                WebSocketLogger.ROOT_LOGGER.info("onFullTextMessage - Client - Received: " + data.getBytes().length + " bytes . Data: " + data);
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

        StreamSinkFrameChannel sendChannel = clientChannel.send(WebSocketFrameType.TEXT, "Hello, World!".length());
        new StringWriteChannelListener("Hello, World!").setup(sendChannel);

        latch.await(10, TimeUnit.SECONDS);
        Assert.assertEquals("Hello, World!", result.get());
        clientChannel.sendClose();

        Assert.assertEquals(SEC_WEBSOCKET_EXTENSIONS_EXPECTED, debug.getResponseExtensions().toString());

        client.shutdown();
        server.stop();
    }
}
