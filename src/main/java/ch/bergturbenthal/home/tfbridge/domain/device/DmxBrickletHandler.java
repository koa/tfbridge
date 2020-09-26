package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.ha.Device;
import ch.bergturbenthal.home.tfbridge.domain.ha.LightConfig;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.util.DisposableConsumer;
import ch.bergturbenthal.home.tfbridge.domain.util.MqttMessageUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.tinkerforge.BrickletDMX;
import com.tinkerforge.IPConnection;
import com.tinkerforge.TinkerforgeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
@Slf4j
public class DmxBrickletHandler implements DeviceHandler {
  private final MqttClient mqttClient;
  private final BridgeProperties properties;
  private final ObjectWriter configWriter;

  public DmxBrickletHandler(final MqttClient mqttClient, BridgeProperties properties) {
    this.mqttClient = mqttClient;
    this.properties = properties;
    final ObjectMapper objectMapper =
        Jackson2ObjectMapperBuilder.json()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();
    configWriter = objectMapper.writerFor(LightConfig.class);
  }

  @Override
  public int deviceId() {
    return BrickletDMX.DEVICE_IDENTIFIER;
  }

  @Override
  public Disposable registerDevice(final String uid, final IPConnection connection)
      throws TinkerforgeException {
    final BrickletDMX bricklet = new BrickletDMX(uid, connection);

    final String brickletPrefix = "DMXBricklet/" + uid;
    MqttMessageUtil.publishVersions(mqttClient, brickletPrefix, bricklet.getIdentity());

    String channelPrefix = brickletPrefix + "/channel/";

    final DisposableConsumer channelRegistrationConsumer = new DisposableConsumer();
    AtomicReference<List<Integer>> currentChannelValues =
        new AtomicReference<>(Collections.emptyList());
    Consumer<List<Integer>> updateConsumer =
        valuesToWrite -> {
          int[] writeValues = new int[valuesToWrite.size()];
          for (int j = 0; j < valuesToWrite.size(); j++) {
            writeValues[j] = valuesToWrite.get(j);
          }
          // log.info("Update " + uid + ": " + valuesToWrite);
          try {
            bricklet.writeFrame(writeValues);
          } catch (TinkerforgeException e) {
            log.warn("Cannot write frame to dmx bus on " + uid, e);
          }
        };
    mqttClient.registerTopic(
        channelPrefix + "+",
        mqttMessage -> {
          try {
            final String topic = mqttMessage.getTopic();
            final String indexStr = topic.substring(channelPrefix.length());
            final int channelIndex = Integer.parseInt(indexStr);
            if (channelIndex < 0) return;
            if (channelIndex > 511) return;
            final double doubleValue =
                Double.parseDouble(new String(mqttMessage.getMessage().getPayload()));
            final double fencedValue = Math.max(0, Math.min(1, doubleValue));
            final int newValue = (int) Math.round(fencedValue * 255);
            while (true) {
              final List<Integer> oldValues = currentChannelValues.get();
              if (oldValues.size() > channelIndex && oldValues.get(channelIndex) == newValue)
                return;
              final List<Integer> newValues = new ArrayList<>(oldValues);
              while (newValues.size() <= channelIndex) newValues.add(0);
              newValues.set(channelIndex, newValue);
              if (currentChannelValues.compareAndSet(oldValues, newValues)) break;
            }
            final List<Integer> valuesToWrite = currentChannelValues.get();
            updateConsumer.accept(valuesToWrite);
          } catch (Exception e) {
            log.warn("Cannot write frame to dmx bus on " + uid, e);
          }
        },
        channelRegistrationConsumer);
    bricklet.setDMXMode(BrickletDMX.DMX_MODE_MASTER);
    bricklet.setCommunicationLEDConfig(BrickletDMX.COMMUNICATION_LED_CONFIG_SHOW_COMMUNICATION);
    bricklet.setFrameDuration(0);
    final Map<String, DisposableConsumer> lightConfigurationConsumers = new HashMap<>();
    properties.getDmxLights().stream()
        .filter(l -> l.getDmxBricklet().equals(uid))
        .forEach(
            light -> {
              try {
                final String lightPrefix = brickletPrefix + "/light/" + light.getId();

                final String stateTopic = lightPrefix + "/state";
                final String brightnessTopic = lightPrefix + "/brightness";
                final String whiteValueTopic = lightPrefix + "/whiteValue";
                final String brightnessStateTopic = lightPrefix + "/brightnessState";
                final String whiteValueStateTopic = lightPrefix + "/whiteValueState";
                final int warmMireds = kelvin2Mireds(light.getWarmTemperature());
                final int coldMireds = kelvin2Mireds(light.getColdTemperature());
                final String config =
                    configWriter.writeValueAsString(
                        LightConfig.builder()
                            .platform("mqtt")
                            .schema("basic")
                            .device(
                                Device.builder()
                                    .name(light.getName())
                                    .identifiers(Collections.singletonList(light.getId()))
                                    .build())
                            .unique_id(uid + "/" + light.getId())
                            .command_topic(stateTopic)
                            .name(light.getName())
                            .brightness_scale(255)
                            .brightness_command_topic(brightnessTopic)
                            .brightness_state_topic(brightnessStateTopic)
                            .max_mireds(warmMireds)
                            .min_mireds(coldMireds)
                            .color_temp_command_topic(whiteValueTopic)
                            .color_temp_state_topic(whiteValueStateTopic)
                            .retain(true)
                            .build());
                mqttClient.send(
                    properties.getDiscoveryPrefix() + "/light/" + light.getId() + "/config",
                    MqttMessageUtil.createMessage(config, true));
                AtomicInteger currentBrightness = new AtomicInteger(0);
                AtomicBoolean currentState = new AtomicBoolean(false);
                AtomicInteger whiteValue = new AtomicInteger(warmMireds + coldMireds / 2);
                Runnable updateValue =
                    () -> {
                      final int targetBrightness = currentBrightness.get();
                      final int targetWhiteValue = whiteValue.get();
                      final boolean targetStateOn = currentState.get();
                      updateConsumer.accept(
                          currentChannelValues.updateAndGet(
                              oldValues -> {
                                final int brightness = targetStateOn ? targetBrightness : 0;
                                final int currentWhiteValue = targetWhiteValue;
                                int warmPart = currentWhiteValue - coldMireds;
                                int coldPart = warmMireds - currentWhiteValue;
                                final int coldValue;
                                final int warmValue;
                                if (coldPart > warmPart) {
                                  coldValue = brightness;
                                  warmValue = brightness * warmPart / coldPart;
                                } else {
                                  warmValue = brightness;
                                  coldValue = brightness * coldPart / warmPart;
                                }
                                final int coldAddress = light.getColdAddress();
                                final int warmAddress = light.getWarmAddress();
                                int maxAddress = Math.max(coldAddress, warmAddress);
                                final ArrayList<Integer> newValues = new ArrayList<>(oldValues);
                                while (newValues.size() <= maxAddress) {
                                  newValues.add(0);
                                }
                                newValues.set(coldAddress, coldValue);
                                newValues.set(warmAddress, warmValue);
                                return Collections.unmodifiableList(newValues);
                              }));
                      mqttClient.send(
                          brightnessStateTopic,
                          MqttMessageUtil.createMessage(String.valueOf(targetBrightness), false));
                      mqttClient.send(
                          whiteValueStateTopic,
                          MqttMessageUtil.createMessage(String.valueOf(targetWhiteValue), false));
                    };
                mqttClient.registerTopic(
                    stateTopic,
                    message -> {
                      final byte[] payload = message.getMessage().getPayload();
                      final String state = new String(payload);
                      log.info("Taken state: " + state);
                      currentState.set(state.equals("ON"));
                      updateValue.run();
                    },
                    lightConfigurationConsumers.computeIfAbsent(
                        light.getId() + "-state", key -> new DisposableConsumer()));
                mqttClient.registerTopic(
                    brightnessTopic,
                    message -> {
                      final byte[] payload = message.getMessage().getPayload();
                      final String state = new String(payload);
                      log.info("Taken brightness: " + state);
                      currentBrightness.set(Integer.parseInt(state));
                      updateValue.run();
                    },
                    lightConfigurationConsumers.computeIfAbsent(
                        light.getId() + "-brightness", key -> new DisposableConsumer()));
                mqttClient.registerTopic(
                    whiteValueTopic,
                    message -> {
                      final byte[] payload = message.getMessage().getPayload();
                      final String state = new String(payload);
                      whiteValue.set(Integer.parseInt(state));
                      updateValue.run();
                    },
                    lightConfigurationConsumers.computeIfAbsent(
                        light.getId() + "-white-value", key -> new DisposableConsumer()));
              } catch (JsonProcessingException e) {
                log.error("Cannot process json", e);
              }
            });

    final String stateTopic = brickletPrefix + "/state";
    mqttClient.send(stateTopic, MqttMessageUtil.ONLINE_MESSAGE);
    return () -> {
      mqttClient.send(stateTopic, MqttMessageUtil.OFFLINE_MESSAGE);
      channelRegistrationConsumer.accept(null);
      lightConfigurationConsumers.values().forEach(c -> c.accept(null));
    };
  }

  private int kelvin2Mireds(final int coldTemperature) {
    return 1000000 / coldTemperature;
  }

  private int mireds2Kelvin(final int coldTemperature) {
    return 1000000 / coldTemperature;
  }
}
