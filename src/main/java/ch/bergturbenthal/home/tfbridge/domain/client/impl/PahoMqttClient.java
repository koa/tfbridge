package ch.bergturbenthal.home.tfbridge.domain.client.impl;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.properties.MqttEndpoint;
import lombok.Value;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
@RefreshScope
public class PahoMqttClient implements MqttClient {
  private static final Pattern                                 SPLIT_PATTERN    = Pattern.compile(Pattern.quote("/"));
  private final        Map<String, RegisteredListeners>        registeredSinks  = new ConcurrentHashMap<>();
  private final        Map<InetSocketAddress, MqttAsyncClient> runningClients   = new ConcurrentHashMap<>();
  private final        Map<String, MqttMessage>                retainedMessages = new ConcurrentHashMap<>();
  private final        BridgeProperties                        properties;
  private              DiscoveryClient                         discoveryClient;

  public PahoMqttClient(final BridgeProperties properties, DiscoveryClient discoveryClient)
          throws MqttException {
    this.properties = properties;
    this.discoveryClient = discoveryClient;

    discover();
  }

  @Scheduled(fixedDelay = 60 * 1000, initialDelay = 10 * 1000)
  public void discover() throws MqttException {
    // log.info("Discover");
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
                  if (mqttMessage.isRetained()) retainedMessages.put(s, mqttMessage);
                  if (log.isInfoEnabled()) {
                    log.info(" -> " + s + ": " + new String(mqttMessage.getPayload()));
                  }
                  final ch.bergturbenthal.home.tfbridge.domain.client.MqttClient.ReceivedMqttMessage
                          msg = new ImmutableReceivedMqttMessage(s, mqttMessage);
                  registeredSinks.values().stream()
                                 .filter(listener -> listener.getMatchingTopic().matcher(s).matches())
                                 .flatMap(l -> l.getListeners().stream())
                                 .collect(Collectors.toList())
                                 .forEach(sink -> sink.next(msg));
                  // log.info("-------------------------------------------------------");
                }

                @Override
                public void deliveryComplete(final IMqttDeliveryToken iMqttDeliveryToken) {}
              });
      client.connect(
              null,
              new IMqttActionListener() {
                @Override
                public void onSuccess(final IMqttToken asyncActionToken) {
                  // log.info("Connected: " + client.isConnected());
                  for (Map.Entry<String, MqttMessage> entry : retainedMessages.entrySet()) {
                    try {
                      // log.info("Deliver retained message on topic " + entry.getKey());
                      client.publish(entry.getKey(), entry.getValue());
                    } catch (MqttException e) {
                      log.warn("Cannot deliver retained message to " + hostAddress);
                    }
                  }
                  final String[] topics = registeredSinks.keySet().toArray(new String[0]);
                  subscribeTopics(topics, client);
                }

                @Override
                public void onFailure(final IMqttToken asyncActionToken, final Throwable exception) {
                  runningClients.remove(inetSocketAddress, client);
                  log.warn("Cannot connect to " + hostAddress, exception);
                }
              });
      runningClients.put(inetSocketAddress, client);
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
    if (log.isInfoEnabled()) {
      log.info(" <- " + topic + ": " + new String(message.getPayload()));
    }
    if (message.isRetained()) {
      retainedMessages.put(topic, message);
    } else retainedMessages.remove(topic);

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
  public Flux<MqttClient.ReceivedMqttMessage> listenTopic(String topic) {
    final Pattern topicPattern = parseTopic(topic);
    final Flux<ReceivedMqttMessage> retainedStream =
            Flux.fromStream(
                    retainedMessages.entrySet().stream()
                                    .filter(e -> topicPattern.matcher(e.getKey()).matches())
                                    .map(e -> new ImmutableReceivedMqttMessage(e.getKey(), e.getValue())));
    final Flux<ReceivedMqttMessage> liveStream =
            Flux.create(
                    (FluxSink<ReceivedMqttMessage> sink) -> {
                      sink.onDispose(
                              () -> {
                                synchronized (registeredSinks) {
                                  final RegisteredListeners existingSubscriptions = registeredSinks.get(topic);
                                  if (existingSubscriptions != null)
                                    existingSubscriptions.getListeners().remove(sink);
                                  if (existingSubscriptions == null
                                          || existingSubscriptions.getListeners().isEmpty()) {
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
                        final RegisteredListeners existingSubscriptions = registeredSinks.get(topic);
                        if (existingSubscriptions != null) {
                          existingSubscriptions.getListeners().add(sink);
                        } else {
                          final Collection<FluxSink<ReceivedMqttMessage>> newSubscriptions =
                                  new ConcurrentLinkedDeque<>();

                          final RegisteredListeners newListeners =
                                  new RegisteredListeners(topicPattern, newSubscriptions);
                          registeredSinks.put(topic, newListeners);
                          newSubscriptions.add(sink);
                          runningClients
                                  .values()
                                  .forEach(
                                          client -> {
                                            try {
                                              if (client.isConnected()) client.subscribe(topic, 1);
                                            } catch (MqttException e) {
                                              log.error("Cannot subscribe to " + topic, e);
                                            }
                                          });
                        }
                      }
                    });
    return Flux.concat(retainedStream, liveStream);
  }

  @Override
  public void send(final String topic, final MqttMessage message) {
    publish(topic, message)
            .count()
            .subscribe(
                    count -> {
                      if (count == 0) log.warn("No target for message " + message + " to " + topic);
                    },
                    ex -> {
                      log.warn("Cannot publish message " + message + " to " + topic);
                    });
  }

  private Pattern parseTopic(final String topic) {
    return Pattern.compile(
            SPLIT_PATTERN
                    .splitAsStream(topic)
                    .map(
                            p -> {
                              if (p.equals("#")) return ".*";
                              else if (p.equals("+")) return "[^/]*";
                              else return Pattern.quote(p);
                            })
                    .collect(Collectors.joining("/")));
  }

  @Override
  public void registerTopic(
          final String topic,
          final Consumer<MqttClient.ReceivedMqttMessage> mqttMessageConsumer,
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

  @Value
  private static final class RegisteredListeners {
    private Pattern                                              matchingTopic;
    private Collection<FluxSink<MqttClient.ReceivedMqttMessage>> listeners;
  }

  @Value
  private static final class ImmutableReceivedMqttMessage
          implements ch.bergturbenthal.home.tfbridge.domain.client.MqttClient.ReceivedMqttMessage {
    private String      topic;
    private MqttMessage message;
  }
}
