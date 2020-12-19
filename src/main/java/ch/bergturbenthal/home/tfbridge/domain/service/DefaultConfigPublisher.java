package ch.bergturbenthal.home.tfbridge.domain.service;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.ha.PublishingConfig;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.util.MqttMessageUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class DefaultConfigPublisher implements ConfigService {
  private final MqttClient                                                mqttClient;
  private final BridgeProperties                                          properties;
  private final Function<Class<? extends PublishingConfig>, ObjectWriter> writers;
  private final Map<String, PublishingConfig>                             currentConfigs =
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
    }
  }
}
