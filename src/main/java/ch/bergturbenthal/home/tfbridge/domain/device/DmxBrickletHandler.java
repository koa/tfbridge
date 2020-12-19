package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.ha.Device;
import ch.bergturbenthal.home.tfbridge.domain.ha.LightConfig;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.properties.SimpleDmxLight;
import ch.bergturbenthal.home.tfbridge.domain.properties.WarmColdDmxLight;
import ch.bergturbenthal.home.tfbridge.domain.service.ConfigService;
import ch.bergturbenthal.home.tfbridge.domain.util.DisposableConsumer;
import ch.bergturbenthal.home.tfbridge.domain.util.MqttMessageUtil;
import com.tinkerforge.BrickletDMX;
import com.tinkerforge.IPConnection;
import com.tinkerforge.TinkerforgeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class DmxBrickletHandler implements DeviceHandler {
  private final MqttClient       mqttClient;
  private final BridgeProperties properties;
  private final ConfigService    configService;

  public DmxBrickletHandler(
          final MqttClient mqttClient, BridgeProperties properties, final ConfigService configService) {
    this.mqttClient = mqttClient;
    this.properties = properties;
    this.configService = configService;
  }

  @Override
  public int deviceId() {
    return BrickletDMX.DEVICE_IDENTIFIER;
  }

  @Override
  public Disposable registerDevice(
      final String uid, final IPConnection connection, final Consumer<Throwable> errorConsumer)
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
            errorConsumer.accept(e);
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
    bricklet.setFrameCallbackConfig(false, false, false, false);
    bricklet.setFrameDuration(0);
    final Map<String, DisposableConsumer> lightConfigurationConsumers = new HashMap<>();

    final List<SimpleDmxLight> simpleDmxLights = properties.getSimpleDmxLights();
    final Stream<LightConfig> lightConfigStream =
            simpleDmxLights == null
            ? Stream.empty()
            : simpleDmxLights.stream()
                             .filter(l -> l.getDmxBricklet().equals(uid))
                             .map(
                                     light -> {
                                       final String lightPrefix = brickletPrefix + "/light/" + light.getId();
                                       final String stateTopic = lightPrefix + "/state";
                                       final String brightnessTopic = lightPrefix + "/brightness";
                                       final String brightnessStateTopic = lightPrefix + "/brightnessState";

                                       final LightConfig config =
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
                                                          .retain(true)
                                                          .build();
                                       AtomicInteger currentBrightness = new AtomicInteger(0);
                                       AtomicBoolean currentState = new AtomicBoolean(false);
                                       Runnable updateValue =
                                               () -> {
                                                 final int targetBrightness = currentBrightness.get();
                                                 final boolean targetStateOn = currentState.get();
                                                 updateConsumer.accept(
                                                         currentChannelValues.updateAndGet(
                                                                 oldValues -> {
                                                                   final int brightness = targetStateOn ? targetBrightness : 0;
                                                                   final int address = light.getAddress();
                                                                   final ArrayList<Integer> newValues =
                                                                           new ArrayList<>(oldValues);
                                                                   while (newValues.size() <= address) {
                                                                     newValues.add(0);
                                                                   }
                                                                   newValues.set(address, brightness);
                                                                   return Collections.unmodifiableList(newValues);
                                                                 }));
                                                 mqttClient.send(
                                                         brightnessStateTopic,
                                                         MqttMessageUtil.createMessage(
                                                                 String.valueOf(targetBrightness), false));
                                               };
                                       registerLight(
                                               lightConfigurationConsumers,
                                               stateTopic,
                                               brightnessTopic,
                                               currentBrightness,
                                               currentState,
                                               updateValue,
                                               light.getId());
                                       return config;
                                     });

    final List<WarmColdDmxLight> warmColdDmxLights = properties.getWarmColdDmxLights();
    final Stream<LightConfig> adjustableLightStream =
            warmColdDmxLights == null
            ? Stream.empty()
            : warmColdDmxLights.stream()
                               .filter(l -> l.getDmxBricklet().equals(uid))
                               .map(
                                       light -> {
                                         final String lightPrefix = brickletPrefix + "/light/" + light.getId();

                                         final String stateTopic = lightPrefix + "/state";
                                         final String brightnessTopic = lightPrefix + "/brightness";
                                         final String whiteValueTopic = lightPrefix + "/whiteValue";
                                         final String brightnessStateTopic = lightPrefix + "/brightnessState";
                                         final String whiteValueStateTopic = lightPrefix + "/whiteValueState";
                                         final int warmMireds = kelvin2Mireds(light.getWarmTemperature());
                                         final int coldMireds = kelvin2Mireds(light.getColdTemperature());
                                         final LightConfig config =
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
                                                            .build();
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
                                                                     int warmPart = targetWhiteValue - coldMireds;
                                                                     int coldPart = warmMireds - targetWhiteValue;
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
                                                                     int maxAddress = Math.max(coldAddress,
                                                                                               warmAddress);
                                                                     final ArrayList<Integer> newValues =
                                                                             new ArrayList<>(oldValues);
                                                                     while (newValues.size() <= maxAddress) {
                                                                       newValues.add(0);
                                                                     }
                                                                     newValues.set(coldAddress, coldValue);
                                                                     newValues.set(warmAddress, warmValue);
                                                                     return Collections.unmodifiableList(newValues);
                                                                   }));
                                                   mqttClient.send(
                                                           brightnessStateTopic,
                                                           MqttMessageUtil.createMessage(
                                                                   String.valueOf(targetBrightness), false));
                                                   mqttClient.send(
                                                           whiteValueStateTopic,
                                                           MqttMessageUtil.createMessage(
                                                                   String.valueOf(targetWhiteValue), false));
                                                 };
                                         registerLight(
                                                 lightConfigurationConsumers,
                                                 stateTopic,
                                                 brightnessTopic,
                                                 currentBrightness,
                                                 currentState,
                                                 updateValue,
                                                 light.getId());
                                         mqttClient.registerTopic(
                                                 whiteValueTopic,
                                                 message -> {
                                                   final byte[] payload = message.getMessage().getPayload();
                                                   final String state = new String(payload);
                                                   whiteValue.set(Integer.parseInt(state));
                                                   updateValue.run();
                                                 },
                                                 lightConfigurationConsumers.computeIfAbsent(
                                                         light.getId() + "-white-value",
                                                         key -> new DisposableConsumer()));
                                         return config;
                                       });
    final List<LightConfig> configs =
            Stream.concat(lightConfigStream, adjustableLightStream).collect(Collectors.toList());
    configs.forEach(configService::publishConfig);

    final String stateTopic = brickletPrefix + "/state";
    mqttClient.send(stateTopic, MqttMessageUtil.ONLINE_MESSAGE);
    return () -> {
      mqttClient.send(stateTopic, MqttMessageUtil.OFFLINE_MESSAGE);
      channelRegistrationConsumer.accept(null);
      lightConfigurationConsumers.values().forEach(c -> c.accept(null));
      configs.forEach(
              config -> {
                mqttClient.unpublishTopic(config.getBrightness_state_topic());
                mqttClient.unpublishTopic(config.getState_topic());
                final String color_temp_state_topic = config.getColor_temp_state_topic();
                if (color_temp_state_topic != null) mqttClient.unpublishTopic(color_temp_state_topic);
              });
      configs.forEach(configService::unpublishConfig);
    };
  }

  public void registerLight(
          final Map<String, DisposableConsumer> lightConfigurationConsumers,
          final String stateTopic,
          final String brightnessTopic,
          final AtomicInteger currentBrightness,
          final AtomicBoolean currentState,
          final Runnable updater,
          final String id) {
    mqttClient.registerTopic(
            stateTopic,
            message -> {
              final byte[] payload = message.getMessage().getPayload();
              final String state = new String(payload);
              if (log.isDebugEnabled()) log.debug("Taken state: " + state);
              currentState.set(state.equals("ON"));
              updater.run();
            },
            lightConfigurationConsumers.computeIfAbsent(
                    id + "-state", key -> new DisposableConsumer()));
    mqttClient.registerTopic(
            brightnessTopic,
            message -> {
              final byte[] payload = message.getMessage().getPayload();
              final String state = new String(payload);
              if (log.isDebugEnabled()) log.debug("Taken brightness: " + state);
              currentBrightness.set(Integer.parseInt(state));
              updater.run();
            },
            lightConfigurationConsumers.computeIfAbsent(
                    id + "-brightness", key -> new DisposableConsumer()));
  }

  private int kelvin2Mireds(final int coldTemperature) {
    return 1000000 / coldTemperature;
  }

  private int mireds2Kelvin(final int coldTemperature) {
    return 1000000 / coldTemperature;
  }
}
