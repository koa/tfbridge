package ch.bergturbenthal.home.tfbridge.domain.client.impl;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.properties.MqttEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttWireMessage;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

@Slf4j
@Component
@RefreshScope
public class PahoMqttClient implements MqttClient {
  private final BridgeProperties                               properties;
  private final IMqttAsyncClient                               asyncClient;
  private final Map<String, Collection<FluxSink<MqttMessage>>> registeredSinks =
          new ConcurrentHashMap<>();

  public PahoMqttClient(final BridgeProperties properties) throws MqttException {
    this.properties = properties;

    final MqttEndpoint mqtt = properties.getMqtt();
    final String hostAddress = mqtt.getAddress().getHostAddress();
    final int port = mqtt.getPort();
    String brokerAddress = "tcp://" + hostAddress + ":" + port;
    asyncClient = new MqttAsyncClient(brokerAddress, mqtt.getClientId());
    asyncClient.setCallback(
            new MqttCallback() {
              @Override
              public void connectionLost(final Throwable throwable) {}

              @Override
              public void messageArrived(final String s, final MqttMessage mqttMessage)
                      throws Exception {
                registeredSinks
                        .getOrDefault(s, Collections.emptyList())
                        .forEach(sink -> sink.next(mqttMessage));
              }

              @Override
              public void deliveryComplete(final IMqttDeliveryToken iMqttDeliveryToken) {}
            });
    asyncClient.connect();
  }

  @Override
  public Mono<MqttWireMessage> publish(String topic, MqttMessage message) {
    return Mono.create(
            sink -> {
              try {
                asyncClient.publish(
                        topic,
                        message,
                        null,
                        new IMqttActionListener() {
                          @Override
                          public void onSuccess(final IMqttToken iMqttToken) {
                            sink.success(iMqttToken.getResponse());
                          }

                          @Override
                          public void onFailure(final IMqttToken iMqttToken, final Throwable throwable) {
                            sink.error(throwable);
                          }
                        });
              } catch (MqttException e) {
                sink.error(e);
              }
            });
  }

  @Override
  public Flux<MqttMessage> listenTopic(String topic) {
    return Flux.create(
            sink -> {
              sink.onDispose(
                      () -> {
                        synchronized (registeredSinks) {
                          final Collection<FluxSink<MqttMessage>> existingSubscriptions =
                                  registeredSinks.get(topic);
                          if (existingSubscriptions != null) existingSubscriptions.remove(sink);
                          if (existingSubscriptions == null || existingSubscriptions.isEmpty()) {
                            registeredSinks.remove(topic);
                            try {
                              asyncClient.unsubscribe(topic);
                            } catch (MqttException e) {
                              log.error("Cannot unsubscribe from " + topic, e);
                            }
                          }
                        }
                      });
              synchronized (registeredSinks) {
                final Collection<FluxSink<MqttMessage>> existingSubscriptions =
                        registeredSinks.get(topic);
                if (existingSubscriptions != null) {
                  existingSubscriptions.add(sink);
                } else {
                  final Collection<FluxSink<MqttMessage>> newSubscriptions =
                          new ConcurrentLinkedDeque<>();
                  registeredSinks.put(topic, newSubscriptions);
                  newSubscriptions.add(sink);
                  try {
                    asyncClient.subscribe(topic, 1);
                  } catch (MqttException e) {
                    sink.error(e);
                  }
                }
              }
            });
  }

  @Override
  public void registerTopic(
          final String topic,
          final Consumer<MqttMessage> mqttMessageConsumer,
          final Consumer<Disposable> disposableConsumer) {
    disposableConsumer.accept(
            listenTopic(topic)
                    .subscribe(
                            mqttMessageConsumer,
                            ex -> {
                              log.warn("Error processing " + topic, ex);
                              registerTopic(topic, mqttMessageConsumer, disposableConsumer);
                            }));
  }
}
