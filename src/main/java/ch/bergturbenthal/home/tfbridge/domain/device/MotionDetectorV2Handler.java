package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.ha.Availability;
import ch.bergturbenthal.home.tfbridge.domain.ha.BinarySensorConfig;
import ch.bergturbenthal.home.tfbridge.domain.ha.Device;
import ch.bergturbenthal.home.tfbridge.domain.ha.LightConfig;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.util.DisposableConsumer;
import ch.bergturbenthal.home.tfbridge.domain.util.MqttMessageUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.tinkerforge.*;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Service
@Slf4j
public class MotionDetectorV2Handler implements DeviceHandler {
  private final MqttClient mqttClient;
  private final BridgeProperties bridgeProperties;
  private final ObjectWriter configWriter;

  public MotionDetectorV2Handler(
      final MqttClient mqttClient, final BridgeProperties bridgeProperties) {
    this.mqttClient = mqttClient;
    this.bridgeProperties = bridgeProperties;
    final ObjectMapper objectMapper =
        Jackson2ObjectMapperBuilder.json()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();
    configWriter = objectMapper.writerFor(BinarySensorConfig.class);
  }

  @Override
  public int deviceId() {
    return BrickletMotionDetectorV2.DEVICE_IDENTIFIER;
  }

  @Override
  public Disposable registerDevice(final String uid, final IPConnection connection)
      throws TinkerforgeException {
    final BrickletMotionDetectorV2 bricklet = new BrickletMotionDetectorV2(uid, connection);
    String topicPrefix = "BrickletMotionDetectorV2/" + uid;
    MqttMessageUtil.publishVersions(mqttClient, topicPrefix, bricklet.getIdentity());
    final AtomicInteger counter = new AtomicInteger();
    final Consumer<Disposable> sensitivityConsumer;
    final String motionStateTopic = topicPrefix + "/motion";
    bricklet.addMotionDetectedListener(
        () -> {
          // log.info("Motion detected at " + settings);
          final int number = counter.incrementAndGet();

          mqttClient.send(
              topicPrefix + "/motionCount",
              MqttMessageUtil.createMessage(String.valueOf(number), true));
          mqttClient.send(motionStateTopic, MqttMessageUtil.createMessage("on", false));
        });
    bricklet.addDetectionCycleEndedListener(
        () -> mqttClient.send(motionStateTopic, MqttMessageUtil.createMessage("off", false)));
    sensitivityConsumer = new DisposableConsumer();

    mqttClient.registerTopic(
        topicPrefix + "/sensitivity",
        message -> {
          try {
            final Integer sensitivity =
                Integer.valueOf(new String(message.getMessage().getPayload()));
            bricklet.setSensitivity(sensitivity);
          } catch (TinkerforgeException e) {
            log.warn("Cannot update sensitivity on " + uid, e);
          }
        },
        sensitivityConsumer);
    bricklet.setIndicator(0, 0, 0);
    bricklet.setStatusLEDConfig(BrickletMotionDetectorV2.STATUS_LED_CONFIG_OFF);
    final String stateTopic = topicPrefix + "/state";
    mqttClient.send(stateTopic, MqttMessageUtil.ONLINE_MESSAGE);
    Optional.ofNullable(bridgeProperties.getPirSensors()).stream()
        .flatMap(Collection::stream)
        .filter(s -> s.getBricklet().equals(uid))
        .findFirst()
        .ifPresent(
            sensor -> {
              try {
                final String config =
                    configWriter.writeValueAsString(
                        BinarySensorConfig.builder()
                            .platform("mqtt")
                            .name(sensor.getName())
                            .state_topic(motionStateTopic)
                            .payload_on("on")
                            .payload_off("off")
                            /*.availability(<
                                Availability.builder()
                                    .topic(stateTopic)
                                    .payload_available("online")
                                    .payload_not_available("offline")
                                    .build())*/
                            .device(
                                Device.builder()
                                    .identifiers(Collections.singletonList(sensor.getId()))
                                    .name(sensor.getName())
                                    .manufacturer("Tinkerforge")
                                    .model("Motion Detector V2")
                                    .build())
                            .device_class("motion")
                            .expire_after(3600*24)
                            .unique_id(sensor.getId())
                            .qos(1)
                            .build());
                mqttClient.send(
                    bridgeProperties.getDiscoveryPrefix()
                        + "/binary_sensor/"
                        + sensor.getId()
                        + "/config",
                    MqttMessageUtil.createMessage(config, true));
                bricklet.setSensitivity(100);
              } catch (JsonProcessingException | TinkerforgeException e) {
                log.error("Cannot serialize config", e);
              }
            });
    return () -> {
      mqttClient.send(stateTopic, MqttMessageUtil.OFFLINE_MESSAGE);
      sensitivityConsumer.accept(null);
      bricklet.removeMotionDetectedListener(
          () -> {
            // log.info("Motion detected at " + settings);
            final int number = counter.incrementAndGet();
            final MqttMessage message1 = new MqttMessage();
            message1.setQos(1);
            message1.setPayload(String.valueOf(number).getBytes());
            mqttClient.send(topicPrefix + "/motionCount", message1);
          });
    };
  }
}
