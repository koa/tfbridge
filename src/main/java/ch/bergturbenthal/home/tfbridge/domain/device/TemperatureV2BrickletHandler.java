package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.ha.Device;
import ch.bergturbenthal.home.tfbridge.domain.ha.SensorConfig;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.service.ConfigService;
import ch.bergturbenthal.home.tfbridge.domain.util.MqttMessageUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.tinkerforge.BrickletTemperatureV2;
import com.tinkerforge.IPConnection;
import com.tinkerforge.TinkerforgeException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttWireMessage;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.function.Consumer;

@Slf4j
@Service
public class TemperatureV2BrickletHandler implements DeviceHandler {
  private final MqttClient       mqttClient;
  private final BridgeProperties properties;
  private final ObjectWriter     configWriter;
  private final ConfigService    configService;

  public TemperatureV2BrickletHandler(
          final MqttClient mqttClient,
          final BridgeProperties properties,
          final ConfigService configService) {
    this.mqttClient = mqttClient;
    this.properties = properties;
    this.configService = configService;
    final ObjectMapper objectMapper =
            Jackson2ObjectMapperBuilder.json()
                                       .serializationInclusion(JsonInclude.Include.NON_NULL)
                                       .build();
    configWriter = objectMapper.writerFor(SensorConfig.class);
  }

  @Override
  public int deviceId() {
    return BrickletTemperatureV2.DEVICE_IDENTIFIER;
  }

  @Override
  public Disposable registerDevice(
          final String uid, final IPConnection connection, final Consumer<Throwable> errorConsumer)
          throws TinkerforgeException {
    final BrickletTemperatureV2 bricklet = new BrickletTemperatureV2(uid, connection);
    String topicPrefix = "BrickletTemperature/" + uid;
    MqttMessageUtil.publishVersions(mqttClient, topicPrefix, bricklet.getIdentity());
    final String temperatureTopic = topicPrefix + "/temperature";
    final BrickletTemperatureV2.TemperatureListener temperatureListener =
            temperature -> {
              double realTemp = temperature / 100.0;
              final MqttMessage message = new MqttMessage();
              message.setQos(1);
              message.setPayload(String.valueOf(realTemp).getBytes());
              message.setRetained(true);
              final Flux<MqttWireMessage> publish = mqttClient.publish(temperatureTopic, message);
              publish.subscribe(result -> {
              }, ex -> log.warn("Cannot send motion detection"));
            };
    final String stateTopic = topicPrefix + "/state";

    final Optional<SensorConfig> sensorConfig =
            Optional.ofNullable(properties.getTemperatureSensors()).stream()
                    .flatMap(Collection::stream)
                    .filter(t -> t.getBricklet().equals(uid))
                    .findFirst()
                    .map(
                            temperatureSensor ->
                                    SensorConfig.builder()
                                                .platform("mqtt")
                                                .name(temperatureSensor.getName())
                                                .unique_id(temperatureSensor.getId())
                                                .device(
                                                        Device.builder()
                                                              .name(temperatureSensor.getName())
                                                              .identifiers(Collections.singletonList(temperatureSensor.getId()))
                                                              .build())
                                                .availability_topic(stateTopic)
                                                .expire_after(300)
                                                .state_topic(temperatureTopic)
                                                .device_class("temperature")
                                                .unit_of_measurement("°C")
                                                .build());
    sensorConfig.ifPresent(configService::publishConfig);

    bricklet.addTemperatureListener(temperatureListener);
    bricklet.setTemperatureCallbackConfiguration(
            Duration.ofMinutes(1).toMillis(), false, 'x', 0, 0);
    mqttClient.send(stateTopic, MqttMessageUtil.ONLINE_MESSAGE);
    return () -> {
      mqttClient.send(stateTopic, MqttMessageUtil.OFFLINE_MESSAGE);
      bricklet.removeTemperatureListener(temperatureListener);
      mqttClient.unpublishTopic(temperatureTopic);
      sensorConfig.ifPresent(configService::unpublishConfig);
    };
  }
}
