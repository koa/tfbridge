package ch.bergturbenthal.home.tfbridge.domain.client.impl;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.properties.MqttEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttWireMessage;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

@Slf4j
@Component
@RefreshScope
public class PahoMqttClient implements MqttClient {
  private final BridgeProperties                               properties;
  private final Map<String, Collection<FluxSink<MqttMessage>>> registeredSinks =
          new ConcurrentHashMap<>();
  private final Map<InetSocketAddress, MqttAsyncClient>        runningClients  = new ConcurrentHashMap<>();
  private       DiscoveryClient                                discoveryClient;

  public PahoMqttClient(final BridgeProperties properties, DiscoveryClient discoveryClient)
          throws MqttException {
    this.properties = properties;
    this.discoveryClient = discoveryClient;

    discover();
  }

  @Scheduled(fixedDelay = 60 * 1000)
  public void discover() throws MqttException {
    final MqttEndpoint mqtt = properties.getMqtt();
    final String service = mqtt.getService();
    final List<ServiceInstance> discoveryClientInstances = discoveryClient.getInstances(service);
    for (ServiceInstance serviceInstance : discoveryClientInstances) {
      final String hostAddress = serviceInstance.getHost();
      final int port = serviceInstance.getPort();
      final InetSocketAddress inetSocketAddress = new InetSocketAddress(hostAddress, port);
      // skip already running clients
      if (runningClients.containsKey(inetSocketAddress)) continue;
      String brokerAddress = "tcp://" + hostAddress + ":" + port;
      final MqttAsyncClient client = new MqttAsyncClient(brokerAddress, mqtt.getClientId());
      client.setCallback(
              new MqttCallback() {
                @Override
                public void connectionLost(final Throwable throwable) {
                  log.warn("Connection to " + hostAddress + " lost", throwable);
                  runningClients.remove(inetSocketAddress, client);
                }

                @Override
                public void messageArrived(final String s, final MqttMessage mqttMessage) {
                  registeredSinks
                          .getOrDefault(s, Collections.emptyList())
                          .forEach(sink -> sink.next(mqttMessage));
                }

                @Override
                public void deliveryComplete(final IMqttDeliveryToken iMqttDeliveryToken) {}
              });
      client.connect(
              null,
              new IMqttActionListener() {
                @Override
                public void onSuccess(final IMqttToken asyncActionToken) {
                  log.info("Connected: " + client.isConnected());
                  runningClients.put(inetSocketAddress, client);
                  final String[] topics = registeredSinks.keySet().toArray(new String[0]);
                  subscribeTopics(topics, client);
                }

                @Override
                public void onFailure(final IMqttToken asyncActionToken, final Throwable exception) {
                  log.warn("Cannot connect to " + hostAddress, exception);
                }
              });
    }
  }

  private void subscribeTopics(final String[] topics, final MqttAsyncClient client) {
    final int[] qos = new int[topics.length];
    Arrays.fill(qos, 1);
    try {
      client.subscribe(topics, qos);
    } catch (MqttException e) {
      log.warn("Cannot subscribe to topics " + Arrays.toString(topics), e);
    }
  }

  @Override
  public Flux<MqttWireMessage> publish(String topic, MqttMessage message) {
    return Flux.fromStream(runningClients.values().stream())
               .flatMap(
            client ->
                    Mono.create(
                            (MonoSink<MqttWireMessage> sink) -> {
                              try {
                                client.publish(
                                        topic,
                                        message,
                                        null,
                                        new IMqttActionListener() {
                                          @Override
                                          public void onSuccess(final IMqttToken iMqttToken) {
                                            sink.success(iMqttToken.getResponse());
                                          }

                                          @Override
                                          public void onFailure(
                                  final IMqttToken iMqttToken, final Throwable throwable) {
                                            sink.error(throwable);
                                          }
                                        });
                              } catch (MqttException e) {
                                sink.error(e);
                              }
                            }))
               .onErrorContinue((ex, value) -> log.warn("Cannot send message to mqtt", ex));
  }

  @Override
  public Flux<MqttMessage> listenTopic(String topic) {
    return Flux.create(
            (FluxSink<MqttMessage> sink) -> {
              sink.onDispose(
                      () -> {
                        synchronized (registeredSinks) {
                          final Collection<FluxSink<MqttMessage>> existingSubscriptions =
                                  registeredSinks.get(topic);
                          if (existingSubscriptions != null) existingSubscriptions.remove(sink);
                          if (existingSubscriptions == null || existingSubscriptions.isEmpty()) {
                            registeredSinks.remove(topic);
                            runningClients
                                    .values()
                                    .forEach(
                                            client -> {
                                              try {
                                                client.unsubscribe(topic);
                                              } catch (MqttException e) {
                                                log.error("Cannot unsubscribe from " + topic, e);
                                              }
                                            });
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
                  runningClients
                          .values()
                          .forEach(
                                  client -> {
                                    try {
                                      client.subscribe(topic, 1);
                                    } catch (MqttException e) {
                                      log.error("Cannot subscribe to " + topic, e);
                                    }
                                  });
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
