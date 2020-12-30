package ch.bergturbenthal.home.tfbridge.domain.service;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.ha.Device;
import ch.bergturbenthal.home.tfbridge.domain.ha.HasDevice;
import ch.bergturbenthal.home.tfbridge.domain.ha.PublishingConfig;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.util.MqttMessageUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class DefaultConfigPublisher implements ConfigService {
  private final MqttClient                                                   mqttClient;
  private final BridgeProperties                                             properties;
  private final Function<Class<? extends PublishingConfig>, ObjectWriter>    writers;
  private final Map<String, PublishingConfig>                                currentConfigs    =
          Collections.synchronizedMap(new HashMap<>());
  private final Map<String, List<ListenerEntry<? extends PublishingConfig>>> functionListeners =
          Collections.synchronizedMap(new HashMap<>());
  private final Map<String, List<ListenerEntry<? extends PublishingConfig>>> deviceListeners   =
          Collections.synchronizedMap(new HashMap<>());

  public DefaultConfigPublisher(final MqttClient mqttClient, final BridgeProperties properties) {
    this.mqttClient = mqttClient;
    this.properties = properties;
    final ObjectMapper objectMapper =
            Jackson2ObjectMapperBuilder.json()
                                       .serializationInclusion(JsonInclude.Include.NON_NULL)
                                       .build();
    final Map<Class<? extends PublishingConfig>, ObjectWriter> writerCache =
            Collections.synchronizedMap(new HashMap<>());
    writers = type -> writerCache.computeIfAbsent(type, objectMapper::writerFor);
  }

  private <P extends PublishingConfig>
  Stream<ConfigurationListener<PublishingConfig>> listenersOfDevice(final P config) {
    if (config instanceof HasDevice)
      return Optional.ofNullable(((HasDevice) config).getDevice())
                     .map(Device::getIdentifiers)
                     .stream()
                     .flatMap(Collection::stream)
                     .filter(deviceListeners::containsKey)
                     .map(deviceListeners::get)
                     .flatMap(Collection::stream)
                     .filter(entry -> entry.getExpectedType().isAssignableFrom(config.getClass()))
                     .map(ListenerEntry::getListener)
                     .map(l -> (ConfigurationListener<PublishingConfig>) l);
    return Stream.empty();
  }

  @Override
  public <P extends PublishingConfig> void publishConfig(final P config) {
    try {
      final String id = config.id();
      currentConfigs.put(id, config);
      final String configStr = writers.apply(config.getClass()).writeValueAsString(config);
      mqttClient.send(pathOfTopic(config), MqttMessageUtil.createMessage(configStr, true));
      final List<ListenerEntry<? extends PublishingConfig>> listenerEntries =
              functionListeners.get(id);
      if (listenerEntries != null) {
        listenerEntries.forEach(
                (ListenerEntry<? extends PublishingConfig> listenerEntry) -> {
                  if (listenerEntry.getExpectedType().isAssignableFrom(config.getClass())) {
                    ((ConfigurationListener<PublishingConfig>) listenerEntry.getListener())
                            .notifyConfigAdded(config);
                  }
                });
      }
      listenersOfDevice(config).forEach(l -> l.notifyConfigAdded(config));

    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Cannot encode " + config, e);
    }
  }

  private <P extends PublishingConfig> String pathOfTopic(final P config) {
    return properties.getDiscoveryPrefix()
            + "/"
            + config.componentType()
            + "/"
            + MqttMessageUtil.strip(config.id())
            + "/config";
  }

  @Override
  public void unpublishConfig(String id) {
    final PublishingConfig config = currentConfigs.remove(id);
    if (config != null) {
      mqttClient.unpublishTopic(pathOfTopic(config));
      final List<ListenerEntry<? extends PublishingConfig>> listenerEntries =
              functionListeners.get(id);
      if (listenerEntries != null) {
        listenerEntries.forEach(
                (ListenerEntry<? extends PublishingConfig> listenerEntry) -> {
                  if (listenerEntry.getExpectedType().isAssignableFrom(config.getClass())) {
                    ((ConfigurationListener<PublishingConfig>) listenerEntry.getListener())
                            .notifyConfigRemoved(config);
                  }
                });
      }
      listenersOfDevice(config).forEach(l -> l.notifyConfigRemoved(config));
    }
  }

  @Override
  public <P extends PublishingConfig> void registerForConfiguration(
          final Class<P> configType, final String id, final ConfigurationListener<P> listener) {
    final List<ListenerEntry<? extends PublishingConfig>> listeners =
            this.functionListeners.computeIfAbsent(
                    id, k -> Collections.synchronizedList(new ArrayList<>()));
    listeners.add(new ListenerEntry<>(configType, listener));
    final PublishingConfig existingConfig = currentConfigs.get(id);
    if (existingConfig != null) {
      if (configType.isAssignableFrom(existingConfig.getClass()))
        listener.notifyConfigAdded((P) existingConfig);
    }
  }

  @Override
  public <P extends PublishingConfig & HasDevice> void registerForDeviceAndConfiguration(
          final Class<P> configType, final String deviceId, final ConfigurationListener<P> listener) {
    final List<ListenerEntry<? extends PublishingConfig>> listeners =
            this.deviceListeners.computeIfAbsent(
                    deviceId, k -> Collections.synchronizedList(new ArrayList<>()));
    listeners.add(new ListenerEntry<>(configType, listener));
    do {
      try {
        final List<PublishingConfig> runningConfigs =
                currentConfigs.values().stream()
                              .filter(
                                      c ->
                                              c instanceof HasDevice
                                              ? Optional.ofNullable(((HasDevice) c).getDevice())
                                                        .map(Device::getIdentifiers)
                                                        .map(i -> i.contains(deviceId))
                                                        .orElse(false)
                                              : Boolean.valueOf(false))
                              .collect(Collectors.toList());
        runningConfigs.forEach(c -> listener.notifyConfigAdded((P) c));
        break;
      } catch (ConcurrentModificationException ex) {
        // log.warn("Concurrrent update -> retry", ex);
      }
    } while (true);
  }

  @Value
  private static class ListenerEntry<P extends PublishingConfig> {
    Class<P>                 expectedType;
    ConfigurationListener<P> listener;
  }
}
