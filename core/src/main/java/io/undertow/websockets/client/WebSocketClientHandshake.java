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

package io.undertow.websockets.client;

import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSocketVersion;
import io.undertow.websockets.extensions.ExtensionHandshake;
import org.xnio.Pool;
import org.xnio.StreamConnection;
import org.xnio.http.HandshakeChecker;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;

/**
 * @author Stuart Douglas
 */
public abstract class WebSocketClientHandshake {

    protected final URI url;

    public static WebSocketClientHandshake create(final WebSocketVersion version, final URI uri) {
        return create(version, uri, null, null);
    }

    public static WebSocketClientHandshake create(final WebSocketVersion version, final URI uri, WebSocketClientNegotiation clientNegotiation, Set<ExtensionHandshake> clientExtensions) {
        switch (version) {
            case V13:
                return new WebSocket13ClientHandshake(uri, clientNegotiation, clientExtensions);
        }
        throw new IllegalArgumentException();
    }

    public WebSocketClientHandshake(final URI url) {
        this.url = url;
    }

    public abstract WebSocketChannel createChannel(final StreamConnection channel, final String wsUri, final Pool<ByteBuffer> bufferPool);

    public abstract Map<String, String> createHeaders();

    public abstract HandshakeChecker handshakeChecker(final URI uri, final Map<String, String> requestHeaders);


}
