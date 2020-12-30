package ch.bergturbenthal.home.tfbridge.domain.client.impl;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.properties.MqttEndpoint;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttWireMessage;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.util.retry.Retry;

import javax.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
// @RefreshScope
public class PahoMqttClient implements MqttClient {
  private static final Pattern                                 SPLIT_PATTERN    = Pattern.compile(Pattern.quote("/"));
  private final        Map<String, RegisteredListeners>        registeredSinks  = new ConcurrentHashMap<>();
  private final        Map<InetSocketAddress, MqttAsyncClient> runningClients   = new ConcurrentHashMap<>();
  private final        Map<String, RetainedMessage>            retainedMessages = new ConcurrentHashMap<>();
  private final        BridgeProperties                        properties;
  private final        ScheduledExecutorService                executorService;
  private final        DiscoveryClient                         discoveryClient;

  public PahoMqttClient(
          final BridgeProperties properties,
          DiscoveryClient discoveryClient,
          ScheduledExecutorService executorService)
          throws MqttException {
    this.properties = properties;
    this.discoveryClient = discoveryClient;
    this.executorService = executorService;

    discover();
  }

  @Scheduled(fixedDelay = 60 * 1000, initialDelay = 10 * 1000)
  public void discover() throws MqttException {
    final String clientId = MqttAsyncClient.generateClientId();
    // log.info("Discover");
    final MqttEndpoint mqtt = properties.getMqtt();
    final String service = mqtt.getService();
    // log.info("Lookup for " + service);
    final List<ServiceInstance> discoveryClientInstances = discoveryClient.getInstances(service);
    for (ServiceInstance serviceInstance : discoveryClientInstances) {
      final String hostAddress = serviceInstance.getHost();
      final int port = serviceInstance.getPort();
      final InetSocketAddress inetSocketAddress = new InetSocketAddress(hostAddress, port);
      // skip already running clients
      if (runningClients.containsKey(inetSocketAddress)) continue;
      String brokerAddress = "tcp://" + hostAddress + ":" + port;
      final MqttDefaultFilePersistence persistence =
              new MqttDefaultFilePersistence(System.getProperty("java.io.tmpdir"));
      final MqttAsyncClient client = new MqttAsyncClient(brokerAddress, clientId, persistence);
      // log.info("Found Service: " + serviceInstance);

      client.setCallback(
              new MqttCallback() {
                @Override
                public void connectionLost(final Throwable throwable) {
                  log.warn("Connection to " + hostAddress + ":" + port + " lost", throwable);
                  runningClients.remove(inetSocketAddress, client);
                }

                @Override
                public void messageArrived(final String s, final MqttMessage mqttMessage) {
                  if (mqttMessage.isRetained())
                    retainedMessages.put(s, new RetainedMessage(mqttMessage));
                  if (log.isDebugEnabled()) {
                    log.debug(" -> " + s + ": " + new String(mqttMessage.getPayload()));
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
      final MqttConnectOptions options = new MqttConnectOptions();

      options.setMaxInflight(100);
      client.connect(
              options,
              null,
              new IMqttActionListener() {
                @Override
                public void onSuccess(final IMqttToken asyncActionToken) {
                  // log.info("Connected: " + client.isConnected());

                  sendRetainedMessages(new ConcurrentLinkedDeque<>(retainedMessages.keySet()));
                  final String[] topics = registeredSinks.keySet().toArray(new String[0]);
                  subscribeTopics(topics, client);
                }

                private void sendRetainedMessages(final Queue<String> pendingRetainedMessages) {

                  while (true) {
                    if (!client.isConnected()) {
                      log.info("Client to " + hostAddress + " is disconnected, cancel");
                      return;
                    }
                    final String foundTopic = pendingRetainedMessages.poll();
                    if (foundTopic == null) {
                      log.info("All retained messages sent");
                      return;
                    }
                    final RetainedMessage retainedMessage = retainedMessages.get(foundTopic);
                    if (retainedMessage == null) continue;
                    final MqttMessage mqttMessage = retainedMessage.getMessage();
                    // log.info("Deliver retained message on topic " + foundTopic);
                    try {
                      client
                              .publish(foundTopic, mqttMessage)
                              .setActionCallback(
                                      new IMqttActionListener() {
                                        @Override
                                        public void onSuccess(final IMqttToken asyncActionToken) {
                                          if (asyncActionToken instanceof IMqttDeliveryToken)
                                            retainedMessage
                                                    .getDeliveryTokens()
                                                    .put(inetSocketAddress, (IMqttDeliveryToken) asyncActionToken);
                                          sendRetainedMessages(pendingRetainedMessages);
                                        }

                                        @Override
                                        public void onFailure(
                                                final IMqttToken asyncActionToken, final Throwable exception) {
                                          pendingRetainedMessages.add(foundTopic);
                                          log.warn(
                                                  "Cannot deliver retained message to " + hostAddress, exception);

                                          sendRetainedMessages(pendingRetainedMessages);
                                        }
                                      });
                    } catch (MqttException e) {
                      pendingRetainedMessages.add(foundTopic);
                      log.warn("Cannot deliver retained message to " + hostAddress, e);
                      executorService.schedule(
                              () -> sendRetainedMessages(pendingRetainedMessages), 10, TimeUnit.SECONDS);
                    }
                    break;
                  }
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
      /// log.info(" <- " + topic + ": " + new String(message.getPayload()));
    }
    final RetainedMessage retainedMessage = new RetainedMessage(message);
    if (message.isRetained()) {
      retainedMessages.put(topic, retainedMessage);
    } else retainedMessages.remove(topic);

    return Flux.fromStream(runningClients.entrySet().stream())
               .flatMap(
                       clientEntry ->
                               Mono.create(
                                       (MonoSink<MqttWireMessage> sink) -> {
                                         final MqttAsyncClient client = clientEntry.getValue();
                                         final InetSocketAddress socketAddress = clientEntry.getKey();
                                         try {
                                           final IMqttDeliveryToken token =
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
                                                                     final IMqttToken iMqttToken,
                                                                     final Throwable throwable) {
                                                               sink.error(throwable);
                                                             }
                                                           });
                                           if (message.isRetained())
                                             retainedMessage.getDeliveryTokens().put(socketAddress, token);
                                         } catch (MqttException e) {
                                           sink.error(e);
                                         }
                                       })
                                   .retryWhen(Retry.backoff(50, Duration.ofSeconds(3))))
               .onErrorContinue((ex, value) -> log.warn("Cannot send message to mqtt", ex));
  }

  @PreDestroy
  public void removeAllRetainedMessages() {
    final Queue<String> topicsToRemove = new LinkedList<>(retainedMessages.keySet());
    while (true) {
      final String topic = topicsToRemove.poll();
      if (topic == null) break;
      unpublishTopic(topic);
    }
  }

  @Override
  public void unpublishTopic(final String topic) {
    if (topic == null) return;
    final RetainedMessage retainedMessage = retainedMessages.remove(topic);
    if (retainedMessage == null) return;
    retainedMessage
            .getDeliveryTokens()
            .forEach(
                    (address, token) -> {
                      final MqttAsyncClient runningClient = runningClients.get(address);
                      try {
                        if (runningClient != null && token.getMessage() != null) {
                          runningClient.removeMessage(token);
                        }
                      } catch (MqttException e) {
                        log.warn("Cannot remove retained message " + topic + " from " + address, e);
                      }
                    });
  }

  @Override
  public Flux<MqttClient.ReceivedMqttMessage> listenTopic(String topic) {
    final Pattern topicPattern = parseTopic(topic);
    final Flux<ReceivedMqttMessage> retainedStream =
            Flux.fromStream(
                    retainedMessages.entrySet().stream()
                                    .filter(e -> topicPattern.matcher(e.getKey()).matches())
                                    .map(e -> new ImmutableReceivedMqttMessage(e.getKey(), e.getValue().getMessage())));
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
              if (count == 0 && !message.isRetained())
                log.warn("No target for message " + message + " to " + topic);
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

  @Value
  private static class RetainedMessage {
    MqttMessage                                message;
    Map<InetSocketAddress, IMqttDeliveryToken> deliveryTokens =
            Collections.synchronizedMap(new HashMap<>());
  }
}
