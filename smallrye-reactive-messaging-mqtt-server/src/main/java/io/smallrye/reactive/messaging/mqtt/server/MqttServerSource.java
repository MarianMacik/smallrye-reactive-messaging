package io.smallrye.reactive.messaging.mqtt.server;

import static io.netty.handler.codec.mqtt.MqttQoS.AT_LEAST_ONCE;
import static io.netty.handler.codec.mqtt.MqttQoS.EXACTLY_ONCE;
import static io.vertx.mqtt.MqttServerOptions.DEFAULT_PORT;
import static io.vertx.mqtt.MqttServerOptions.DEFAULT_TLS_PORT;

import java.util.concurrent.CompletableFuture;

import org.eclipse.microprofile.reactive.streams.operators.PublisherBuilder;
import org.eclipse.microprofile.reactive.streams.operators.ReactiveStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.reactivex.processors.BehaviorProcessor;
import io.vertx.mqtt.MqttServerOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.mqtt.MqttServer;

class MqttServerSource {

    private final Logger logger = LoggerFactory.getLogger(MqttServerSource.class);
    private final boolean broadcast;
    private final PublisherBuilder<MqttMessage> source;
    private final MqttServer mqttServer;

    private static MqttServerOptions mqttServerOptions(MqttServerConnectorIncomingConfiguration config) {
        final MqttServerOptions options = new MqttServerOptions();
        options.setAutoClientId(config.getAutoGeneratedClientId());
        options.setSsl(config.getSsl());
        // TODO set KeyCertOptions if SSL, c.f. https://vertx.io/docs/vertx-mqtt/java/#_handling_client_connection_disconnection_with_ssl_tls_support
        options.setMaxMessageSize(config.getMaxMessageSize());
        options.setTimeoutOnConnect(config.getTimeoutOnConnect());
        options.setReceiveBufferSize(config.getReceiveBufferSize());
        final int defaultPort = options.isSsl() ? DEFAULT_TLS_PORT : DEFAULT_PORT;
        options.setPort(config.getPort().orElse(defaultPort));
        options.setHost(config.getHost());
        return options;
    }

    MqttServerSource(Vertx vertx, MqttServerConnectorIncomingConfiguration config) {
        this.broadcast = config.getBroadcast();
        final MqttServerOptions options = mqttServerOptions(config);
        this.mqttServer = MqttServer.create(vertx, options);
        final BehaviorProcessor<MqttMessage> processor = BehaviorProcessor.create();

        mqttServer.exceptionHandler(error -> {
            logger.error("Exception thrown", error);
            processor.onError(error);
        });

        mqttServer.endpointHandler(endpoint -> {
            logger.debug("MQTT client [{}] request to connect, clean session = {}",
                    endpoint.clientIdentifier(), endpoint.isCleanSession());

            if (endpoint.auth() != null) {
                logger.trace("[username = {}, password = {}]", endpoint.auth().getUsername(),
                        endpoint.auth().getPassword());
            }
            if (endpoint.will() != null) {
                logger.trace("[will topic = {} msg = {} QoS = {} isRetain = {}]",
                        endpoint.will().getWillTopic(), endpoint.will().getWillMessageBytes(),
                        endpoint.will().getWillQos(), endpoint.will().isWillRetain());
            }

            logger.trace("[keep alive timeout = {}]", endpoint.keepAliveTimeSeconds());

            endpoint.exceptionHandler(
                    error -> logger.error("Error with client " + endpoint.clientIdentifier(), error));

            endpoint.disconnectHandler(
                    v -> logger.debug("MQTT client [{}] disconnected", endpoint.clientIdentifier()));

            endpoint.pingHandler(
                    v -> logger.trace("Ping received from client [{}]", endpoint.clientIdentifier()));

            endpoint.publishHandler(message -> {
                logger.debug("Just received message [{}] with QoS [{}] from client [{}]",
                        message.payload(),
                        message.qosLevel(), endpoint.clientIdentifier());

                processor.onNext(new MqttMessage(message, endpoint.clientIdentifier(), () -> {
                    if (message.qosLevel() == AT_LEAST_ONCE) {
                        logger.trace("Send PUBACK to client [{}] for message [{}]",
                                endpoint.clientIdentifier(),
                                message.messageId());
                        endpoint.publishAcknowledge(message.messageId());
                    } else if (message.qosLevel() == EXACTLY_ONCE) {
                        logger.trace("Send PUBREC to client [{}] for message [{}]",
                                endpoint.clientIdentifier(),
                                message.messageId());
                        endpoint.publishReceived(message.messageId());
                    }
                    return CompletableFuture.completedFuture(null);
                }));
            });

            endpoint.publishReleaseHandler(messageId -> {
                logger.trace("Send PUBCOMP to client [{}] for message [{}]", endpoint.clientIdentifier(),
                        messageId);
                endpoint.publishComplete(messageId);
            });

            endpoint.subscribeHandler(subscribeMessage -> {
                logger.trace("Received subscription message {} from client [{}], closing connection",
                        subscribeMessage, endpoint.clientIdentifier());
                endpoint.close();
            });

            // accept connection from the remote client
            // this implementation doesn't keep track of sessions
            endpoint.accept(false);
        });

        this.source = ReactiveStreams.fromPublisher(processor
                .delaySubscription(mqttServer.listen()
                        .onItem().invoke(ignored -> logger
                                .info("MQTT server listening on {}:{}", options.getHost(), mqttServer.actualPort()))
                        .onFailure().invoke(throwable -> logger.error("Failed to start MQTT server", throwable))
                        .toMulti()
                        .then(flow -> {
                            if (broadcast) {
                                return flow.broadcast().toAllSubscribers();
                            } else {
                                return flow;
                            }
                        }))
                .doOnSubscribe(subscription -> logger.debug("New subscriber added {}", subscription)));
    }

    synchronized PublisherBuilder<MqttMessage> source() {
        return source;
    }

    synchronized void close() {
        mqttServer.closeAndForget();
    }

    synchronized int port() {
        return mqttServer.actualPort();
    }
}
