/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.extension.siddhi.io.tcp.sink;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.apache.log4j.Logger;
import org.wso2.extension.siddhi.io.tcp.transport.TCPNettyClient;
import org.wso2.siddhi.annotation.Example;
import org.wso2.siddhi.annotation.Extension;
import org.wso2.siddhi.core.config.SiddhiAppContext;
import org.wso2.siddhi.core.exception.ConnectionUnavailableException;
import org.wso2.siddhi.core.exception.SiddhiAppCreationException;
import org.wso2.siddhi.core.stream.output.sink.Sink;
import org.wso2.siddhi.core.util.config.ConfigReader;
import org.wso2.siddhi.core.util.transport.DynamicOptions;
import org.wso2.siddhi.core.util.transport.Option;
import org.wso2.siddhi.core.util.transport.OptionHolder;
import org.wso2.siddhi.query.api.definition.StreamDefinition;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * Tcp sink extension.
 */
@Extension(
        name = "tcp",
        namespace = "sink",
        description = "TBD",
        examples = @Example(description = "TBD", syntax = "TBD")
)
public class TCPSink extends Sink {

    private static final String TCP_NO_DELAY = "tcp.no.delay";
    private static final String KEEP_ALIVE = "keep.alive";
    private static final String WORKER_THREADS = "worker.threads";
    private static final String DEFAULT_TCP_NO_DELAY = "true";
    private static final String DEFAULT_KEEP_ALIVE = "true";
    private static final String DEFAULT_WORKER_THREADS = "0";
    private static final String URL = "url";
    private static final String SYNC = "sync";
    private static final Logger log = Logger.getLogger(TCPSink.class);
    private TCPNettyClient tcpNettyClient;
    private String host;
    private int port;
    private String channelId;
    private Option syncOption;
    private Boolean sync = null;
    private String hostAndPort;

    @Override
    protected void init(StreamDefinition outputStreamDefinition, OptionHolder optionHolder,
                        ConfigReader sinkConfigReader, SiddhiAppContext siddhiAppContext) {
        String url = optionHolder.validateAndGetStaticValue(URL);
        syncOption = optionHolder.getOrCreateOption(SYNC, "false");
        if (syncOption.isStatic()) {
            sync = Boolean.parseBoolean(syncOption.getValue());
        }
        try {
            if (!url.startsWith("tcp:")) {
                throw new SiddhiAppCreationException("Malformed url '" + url + "' with wrong protocol found, " +
                        "expected in format 'tcp://<host>:<port>/<context>'");
            }
            URL aURL = new URL(url.replaceFirst("tcp", "http"));
            host = aURL.getHost();
            port = aURL.getPort();
            hostAndPort = host + ":" + port;
            channelId = aURL.getPath().substring(1);
        } catch (MalformedURLException e) {
            throw new SiddhiAppCreationException("Malformed url '" + url + "' found, expected in format " +
                    "'tcp://<host>:<port>/<context>'", e);
        }
        boolean tcpNoDelay = Boolean.parseBoolean(optionHolder.validateAndGetStaticValue(TCP_NO_DELAY,
                DEFAULT_TCP_NO_DELAY));
        boolean keepAlive = Boolean.parseBoolean(optionHolder.validateAndGetStaticValue(KEEP_ALIVE,
                DEFAULT_KEEP_ALIVE));
        int workerThreads = Integer.parseInt(optionHolder.validateAndGetStaticValue(WORKER_THREADS,
                DEFAULT_WORKER_THREADS));
        tcpNettyClient = new TCPNettyClient(workerThreads, keepAlive, tcpNoDelay);

    }

    @Override
    public Class[] getSupportedInputEventClasses() {
        return new Class[]{String.class, byte[].class, ByteBuffer.class};
    }

    @Override
    public String[] getSupportedDynamicOptions() {
        return new String[]{SYNC};
    }


    @Override
    public void connect() throws ConnectionUnavailableException {
        tcpNettyClient.connect(host, port);
        log.info("'tcp' sink at '" + getStreamDefinition().getId() + "' stream successfully connected to '" +
                hostAndPort + "'.");
    }

    @Override
    public void publish(Object payload, DynamicOptions dynamicOptions) throws ConnectionUnavailableException {
        try {
            byte[] message;
            if (payload instanceof String) {
                message = ((String) payload).getBytes(Charset.defaultCharset());
            } else if (payload instanceof ByteBuffer) {
                message = ((ByteBuffer) payload).array();
            } else {
                message = (byte[]) payload;
            }
            boolean isSync;
            if (sync != null) {
                isSync = sync;
            } else {
                isSync = Boolean.parseBoolean(syncOption.getValue(dynamicOptions));
            }
            if (isSync) {
                try {
                    ChannelFuture future = tcpNettyClient.send(channelId, message);
                    future.sync();
                    if (!future.isSuccess()) {
                        throw new ConnectionUnavailableException("Error sending events to '" + hostAndPort +
                                "' on channel '" + channelId + "', " + hostAndPort + ", " +
                                future.cause().getMessage(), future.cause());
                    }
                } catch (InterruptedException e) {
                    throw new ConnectionUnavailableException("Error sending events to '" + hostAndPort +
                            "' on channel '" + channelId + "', " + hostAndPort + ", " + e.getMessage(), e);
                }
            } else {
                ChannelFuture future = tcpNettyClient.send(channelId, message);
                future.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            log.error("Error sending events to '" + hostAndPort + "' on channel '" +
                                    channelId + "', " + future.cause() + ", dropping events ", future.cause());
                        }
                    }
                });
                if (future.isDone() && !future.isSuccess()) {
                    throw new ConnectionUnavailableException("Error sending events to '" + hostAndPort +
                            "' on channel '" + channelId + "', " + hostAndPort + ", " + future.cause().getMessage(),
                            future.cause());
                }
            }
        } catch (Throwable t) {
            throw new ConnectionUnavailableException("Error sending events to '" + hostAndPort + "' on channel '" +
                    channelId + "', " + hostAndPort + ", " + t.getMessage(), t);
        }
    }

    @Override
    public void disconnect() {
        if (tcpNettyClient != null) {
            tcpNettyClient.disconnect();
        }
    }

    @Override
    public void destroy() {
        if (tcpNettyClient != null) {
            tcpNettyClient.shutdown();
            tcpNettyClient = null;
        }
    }


    @Override
    public Map<String, Object> currentState() {
        return null;
    }

    @Override
    public void restoreState(Map<String, Object> state) {
        // no state
    }
}
