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

import io.undertow.util.FlexBase64;
import io.undertow.util.Headers;
import io.undertow.websockets.WebSocketExtension;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSocketMessages;
import io.undertow.websockets.core.WebSocketUtils;
import io.undertow.websockets.core.WebSocketVersion;
import io.undertow.websockets.core.protocol.version13.WebSocket13Channel;
import io.undertow.websockets.extensions.ExtensionFunction;
import io.undertow.websockets.extensions.ExtensionHandshake;
import org.xnio.Pool;
import org.xnio.StreamConnection;
import org.xnio.http.HandshakeChecker;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @author Stuart Douglas
 */
public class WebSocket13ClientHandshake extends WebSocketClientHandshake {

    public static final String MAGIC_NUMBER = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final WebSocketClientNegotiation negotiation;
    private final Set<ExtensionHandshake> clientExtensions;

    public WebSocket13ClientHandshake(final URI url, WebSocketClientNegotiation negotiation, Set<ExtensionHandshake> clientExtensions) {
        super(url);
        this.negotiation = negotiation;
        this.clientExtensions = clientExtensions;
    }

    public WebSocket13ClientHandshake(final URI url) {
        this(url, null, null);
    }

    @Override
    public WebSocketChannel createChannel(final StreamConnection channel, final String wsUri, final Pool<ByteBuffer> bufferPool) {
        if (negotiation != null && negotiation.getSelectedExtensions() != null && negotiation.getSelectedExtensions().size() > 0) {
            List<ExtensionFunction> negotiatedExtensions = new ArrayList<>();

            if (clientExtensions == null || clientExtensions.size() == 0) {
                throw WebSocketMessages.MESSAGES.badExtensionsConfiguredInClient();
            } else {

                List<WebSocketExtension> negotiatedExtensionsDef = negotiation.getSelectedExtensions();
                if (negotiatedExtensionsDef != null && negotiatedExtensionsDef.size() > 0) {
                    for (WebSocketExtension def : negotiatedExtensionsDef) {
                        for (ExtensionHandshake ext : clientExtensions) {
                            if (def.getName().equals(ext.getName())) {
                                negotiatedExtensions.add(ext.create());
                            }
                        }
                    }
                }

            }
            return new WebSocket13Channel(channel, bufferPool, wsUri, negotiation != null ? negotiation.getSelectedSubProtocol() : "", true, true, negotiatedExtensions, new HashSet<WebSocketChannel>());
        } else {
            return new WebSocket13Channel(channel, bufferPool, wsUri, negotiation != null ? negotiation.getSelectedSubProtocol() : "", true, false, null, new HashSet<WebSocketChannel>());
        }
    }


    public Map<String, String> createHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put(Headers.UPGRADE_STRING, "websocket");
        headers.put(Headers.CONNECTION_STRING, "upgrade");
        String key = createSecKey();
        headers.put(Headers.SEC_WEB_SOCKET_KEY_STRING, key);
        headers.put(Headers.SEC_WEB_SOCKET_VERSION_STRING, getVersion().toHttpHeaderValue());
        if (negotiation != null) {
            List<String> subProtocols = negotiation.getSupportedSubProtocols();
            if (subProtocols != null && !subProtocols.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                Iterator<String> it = subProtocols.iterator();
                while (it.hasNext()) {
                    sb.append(it.next());
                    if (it.hasNext()) {
                        sb.append(", ");
                    }
                }
                headers.put(Headers.SEC_WEB_SOCKET_PROTOCOL_STRING, sb.toString());
            }
            List<WebSocketExtension> extensions = negotiation.getSupportedExtensions();
            if (extensions != null && !extensions.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                Iterator<WebSocketExtension> it = extensions.iterator();
                while (it.hasNext()) {
                    WebSocketExtension next = it.next();
                    sb.append(next.getName());
                    for (WebSocketExtension.Parameter param : next.getParameters()) {
                        sb.append("; ");
                        sb.append(param.getName());
                        if (param.getValue() != null && param.getValue().length() > 0) {
                            sb.append("=");
                            sb.append(param.getValue());
                        }
                    }
                    if (it.hasNext()) {
                        sb.append(", ");
                    }
                }
                headers.put(Headers.SEC_WEB_SOCKET_EXTENSIONS_STRING, sb.toString());
            }
        }
        return headers;

    }

    protected String createSecKey() {
        SecureRandom random = new SecureRandom();
        byte[] data = new byte[16];
        for (int i = 0; i < 4; ++i) {
            int val = random.nextInt();
            data[i * 4] = (byte) val;
            data[i * 4 + 1] = (byte) ((val >> 8) & 0xFF);
            data[i * 4 + 2] = (byte) ((val >> 16) & 0xFF);
            data[i * 4 + 3] = (byte) ((val >> 24) & 0xFF);
        }
        return FlexBase64.encodeString(data, false);
    }

    @Override
    public HandshakeChecker handshakeChecker(final URI uri, final Map<String, String> requestHeaders) {
        final String sentKey = requestHeaders.get(Headers.SEC_WEB_SOCKET_KEY_STRING);
        return new HandshakeChecker() {
            @Override
            public void checkHandshake(Map<String, String> headers) throws IOException {
                if(negotiation != null) {
                    negotiation.afterRequest(headers);
                }
                String upgrade = headers.get(Headers.UPGRADE_STRING.toLowerCase(Locale.ENGLISH));
                if (upgrade == null || !upgrade.trim().equalsIgnoreCase("websocket")) {
                    throw WebSocketMessages.MESSAGES.noWebSocketUpgradeHeader();
                }
                String connHeader = headers.get(Headers.CONNECTION_STRING.toLowerCase(Locale.ENGLISH));
                if (connHeader == null || !connHeader.trim().equalsIgnoreCase("upgrade")) {
                    throw WebSocketMessages.MESSAGES.noWebSocketConnectionHeader();
                }
                String acceptKey = headers.get(Headers.SEC_WEB_SOCKET_ACCEPT_STRING.toLowerCase(Locale.ENGLISH));
                final String dKey = solve(sentKey);
                if (!dKey.equals(acceptKey)) {
                    throw WebSocketMessages.MESSAGES.webSocketAcceptKeyMismatch(dKey, acceptKey);
                }
                if (negotiation != null) {
                    String subProto = headers.get(Headers.SEC_WEB_SOCKET_PROTOCOL_STRING.toLowerCase(Locale.ENGLISH));
                    if (subProto != null && !subProto.isEmpty() && !negotiation.getSupportedSubProtocols().contains(subProto)) {
                        throw WebSocketMessages.MESSAGES.unsupportedProtocol(subProto, negotiation.getSupportedSubProtocols());
                    }
                    List<WebSocketExtension> extensions = Collections.emptyList();
                    String extHeader = headers.get(Headers.SEC_WEB_SOCKET_EXTENSIONS_STRING.toLowerCase(Locale.ENGLISH));
                    if (extHeader != null) {
                        extensions = WebSocketExtension.parse(extHeader);
                    }
                    negotiation.handshakeComplete(subProto, extensions);
                }
            }
        };
    }

    protected final String solve(final String nonceBase64) {
        try {
            final String concat = nonceBase64 + MAGIC_NUMBER;
            final MessageDigest digest = MessageDigest.getInstance("SHA1");

            digest.update(concat.getBytes(WebSocketUtils.UTF_8));
            final byte[] bytes = digest.digest();
            return FlexBase64.encodeString(bytes, false);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public WebSocketVersion getVersion() {
        return WebSocketVersion.V13;
    }
}
