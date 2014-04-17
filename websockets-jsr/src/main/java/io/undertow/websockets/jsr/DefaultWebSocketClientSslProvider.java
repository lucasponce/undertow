package io.undertow.websockets.jsr;

import org.xnio.OptionMap;
import org.xnio.XnioWorker;
import org.xnio.ssl.JsseXnioSsl;
import org.xnio.ssl.XnioSsl;

import javax.net.ssl.SSLContext;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.Endpoint;
import java.net.URI;

/**
 * Client SSL provider that gets the SSL context in one of two ways.
 *
 * Either the {@link #setSslContext(javax.net.ssl.SSLContext)} method can
 * be invoked before connecting, and this context will be used for the next
 * client connection from this thread, or alternatively the
 * io.undertow.websocket.SSL_CONTEXT property can be set in the user properties
 * of the ClientEndpointConfig.
 *
 * @author Stuart Douglas
 */
public class DefaultWebSocketClientSslProvider implements WebsocketClientSslProvider {

    public static final String SSL_CONTEXT = "io.undertow.websocket.SSL_CONTEXT";

    private static final ThreadLocal<SSLContext> LOCAL_SSL_CONTEXT = new ThreadLocal<SSLContext>();

    @Override
    public XnioSsl getSsl(XnioWorker worker, Class<?> annotatedEndpoint, URI uri) {
        return getThreadLocalSsl(worker);
    }

    @Override
    public XnioSsl getSsl(XnioWorker worker, Object annotatedEndpointInstance, URI uri) {
        return getThreadLocalSsl(worker);
    }

    @Override
    public XnioSsl getSsl(XnioWorker worker, Endpoint endpoint, ClientEndpointConfig cec, URI uri) {
        XnioSsl ssl =  getThreadLocalSsl(worker);
        if(ssl != null) {
            return ssl;
        }
        //look for some SSL config
        SSLContext sslContext = (SSLContext) cec.getUserProperties().get(SSL_CONTEXT);

        if (sslContext != null) {
            return new JsseXnioSsl(worker.getXnio(), OptionMap.EMPTY, sslContext);
        }
        return null;
    }

    public static void setSslContext(final SSLContext context) {
        LOCAL_SSL_CONTEXT.set(context);
    }
    private XnioSsl getThreadLocalSsl(XnioWorker worker) {
        SSLContext val = LOCAL_SSL_CONTEXT.get();
        if (val != null) {
            LOCAL_SSL_CONTEXT.remove();
            return new JsseXnioSsl(worker.getXnio(), OptionMap.EMPTY, val);
        }
        return null;
    }

}