package ch.bergturbenthal.home.tfbridge.domain.service;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.ha.PublishingConfig;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.util.MqttMessageUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import lombok.Value;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;

@Service
public class DefaultConfigPublisher implements ConfigService {
  private final MqttClient                                                   mqttClient;
  private final BridgeProperties                                             properties;
  private final Function<Class<? extends PublishingConfig>, ObjectWriter>    writers;
  private final Map<String, PublishingConfig>                                currentConfigs =
          Collections.synchronizedMap(new HashMap<>());
  private final Map<String, List<ListenerEntry<? extends PublishingConfig>>> listeners      =
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

  @Override
  public <P extends PublishingConfig> void publishConfig(final P config) {
    try {
      final String id = config.id();
      currentConfigs.put(id, config);
      final String configStr = writers.apply(config.getClass()).writeValueAsString(config);
      mqttClient.send(pathOfTopic(config), MqttMessageUtil.createMessage(configStr, true));
      final List<ListenerEntry<? extends PublishingConfig>> listenerEntries = listeners.get(id);
      if (listenerEntries != null) {
        listenerEntries.forEach(
                (ListenerEntry<? extends PublishingConfig> listenerEntry) -> {
                  if (listenerEntry.getExpectedType().isAssignableFrom(config.getClass())) {
                    ((ConfigurationListener<PublishingConfig>) listenerEntry.getListener())
                            .notifyConfigAdded(config);
                  }
                });
      }
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
      final List<ListenerEntry<? extends PublishingConfig>> listenerEntries = listeners.get(id);
      if (listenerEntries != null) {
        listenerEntries.forEach(
                (ListenerEntry<? extends PublishingConfig> listenerEntry) -> {
                  if (listenerEntry.getExpectedType().isAssignableFrom(config.getClass())) {
                    ((ConfigurationListener<PublishingConfig>) listenerEntry.getListener())
                            .notifyConfigRemoved(config);
                  }
                });
      }
    }
  }

  @Override
  public <P extends PublishingConfig> void registerForConfiguration(
          final Class<P> configType, final String id, final ConfigurationListener<P> listener) {
    final List<ListenerEntry<? extends PublishingConfig>> listeners =
            this.listeners.computeIfAbsent(id, k -> Collections.synchronizedList(new ArrayList<>()));
    listeners.add(new ListenerEntry<>(configType, listener));
    final PublishingConfig existingConfig = currentConfigs.get(id);
    if (existingConfig != null) {
      if (configType.isAssignableFrom(existingConfig.getClass()))
        listener.notifyConfigAdded((P) existingConfig);
    }
  }

  @Value
  private static class ListenerEntry<P extends PublishingConfig> {
    Class<P>                 expectedType;
    ConfigurationListener<P> listener;
  }
}
