package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.ha.BinarySensorConfig;
import ch.bergturbenthal.home.tfbridge.domain.ha.Device;
import ch.bergturbenthal.home.tfbridge.domain.ha.LightConfig;
import ch.bergturbenthal.home.tfbridge.domain.ha.TriggerConfig;
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

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class DmxBrickletHandler implements DeviceHandler {
  private final MqttClient               mqttClient;
  private final BridgeProperties         properties;
  private final ConfigService            configService;
  private final ScheduledExecutorService executorService;

  public DmxBrickletHandler(
          final MqttClient mqttClient,
          BridgeProperties properties,
          final ConfigService configService,
          final ScheduledExecutorService executorService) {
    this.mqttClient = mqttClient;
    this.properties = properties;
    this.configService = configService;
    this.executorService = executorService;
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
                                       final String commandTopic = lightPrefix + "/command";
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
                                                          .command_topic(commandTopic)
                                                          .state_topic(stateTopic)
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
                                                                 String.valueOf(targetBrightness), true));
                                                 mqttClient.send(
                                                         stateTopic,
                                                         MqttMessageUtil.createMessage(targetStateOn ? "ON" : "OFF",
                                                                                       true));
                                               };
                                       final SinglePendingSchedulerConsumer singlePendingSchedulerConsumer =
                                               new SinglePendingSchedulerConsumer();
                                       registerLight(
                                               lightConfigurationConsumers,
                                               commandTopic,
                                               brightnessTopic,
                                               currentBrightness,
                                               currentState,
                                               () ->
                                                       singlePendingSchedulerConsumer.accept(
                                                               executorService.schedule(
                                                                       updateValue, 100, TimeUnit.MILLISECONDS)),
                                               light.getId(),
                                               light.getTriggers(),
                                               light.getMotionDetectors());
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

                                         final String commandTopic = lightPrefix + "/command";
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
                                                            .command_topic(commandTopic)
                                                            .state_topic(stateTopic)
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
                                         final SinglePendingSchedulerConsumer singlePendingSchedulerConsumer =
                                                 new SinglePendingSchedulerConsumer();
                                         Runnable updateValue =
                                                 () ->
                                                         singlePendingSchedulerConsumer.accept(
                                                                 executorService.schedule(
                                                                         () -> {
                                                                           final int targetBrightness = currentBrightness
                                                                                   .get();
                                                                           final int targetWhiteValue = whiteValue.get();
                                                                           final boolean targetStateOn = currentState.get();
                                                                           updateConsumer.accept(
                                                                                   currentChannelValues.updateAndGet(
                                                                                           oldValues -> {
                                                                                             final int brightness =
                                                                                                     targetStateOn ? targetBrightness : 0;
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
                                                                                             final int coldAddress = light
                                                                                                     .getColdAddress();
                                                                                             final int warmAddress = light
                                                                                                     .getWarmAddress();
                                                                                             int maxAddress =
                                                                                                     Math.max(
                                                                                                             coldAddress,
                                                                                                             warmAddress);
                                                                                             final ArrayList<Integer> newValues =
                                                                                                     new ArrayList<>(
                                                                                                             oldValues);
                                                                                             while (newValues.size() <= maxAddress) {
                                                                                               newValues.add(0);
                                                                                             }
                                                                                             newValues.set(coldAddress,
                                                                                                           coldValue);
                                                                                             newValues.set(warmAddress,
                                                                                                           warmValue);
                                                                                             return Collections.unmodifiableList(
                                                                                                     newValues);
                                                                                           }));
                                                                           mqttClient.send(
                                                                                   brightnessStateTopic,
                                                                                   MqttMessageUtil.createMessage(
                                                                                           String.valueOf(
                                                                                                   targetBrightness),
                                                                                           true));
                                                                           mqttClient.send(
                                                                                   whiteValueStateTopic,
                                                                                   MqttMessageUtil.createMessage(
                                                                                           String.valueOf(
                                                                                                   targetWhiteValue),
                                                                                           true));
                                                                           mqttClient.send(
                                                                                   stateTopic,
                                                                                   MqttMessageUtil.createMessage(
                                                                                           targetStateOn ? "ON" : "OFF",
                                                                                           true));
                                                                         },
                                                                         100,
                                                                         TimeUnit.MILLISECONDS));
                                         registerLight(
                                                 lightConfigurationConsumers,
                                                 commandTopic,
                                                 brightnessTopic,
                                                 currentBrightness,
                                                 currentState,
                                                 updateValue,
                                                 light.getId(),
                                                 light.getTriggers(),
                                                 light.getMotionDetectors());
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
          final String id,
          final List<String> triggers,
          final List<String> motionDetectors) {

    final boolean hasNoMotionDetector =
            Optional.ofNullable(motionDetectors).map(List::isEmpty).orElse(true);
    final boolean hasNoTriggers = Optional.ofNullable(triggers).map(List::isEmpty).orElse(true);

    Function<String, Consumer<Disposable>> disposableConsumers =
            key -> lightConfigurationConsumers.computeIfAbsent(key, k -> new DisposableConsumer());

    final Duration switchOffTimer;
    if (hasNoMotionDetector) {
      switchOffTimer = Duration.ofHours(4);
    } else {
      switchOffTimer = Duration.ofHours(1);
    }
    Consumer<ScheduledFuture<?>> handleSwitchOffSchedule = new SinglePendingSchedulerConsumer();

    Runnable switchOff =
            () -> {
              mqttClient.send(stateTopic, MqttMessageUtil.createMessage("OFF", true));
              handleSwitchOffSchedule.accept(null);
            };
    Runnable switchOn =
            () -> {
              if (currentBrightness.get() < 10)
                mqttClient.send(brightnessTopic, MqttMessageUtil.createMessage("255", true));
              mqttClient.send(stateTopic, MqttMessageUtil.createMessage("ON", true));
              handleSwitchOffSchedule.accept(
                      executorService.schedule(switchOff, switchOffTimer.getSeconds(), TimeUnit.SECONDS));
            };
    Runnable toggle =
            () -> {
              final boolean valueBefore = currentState.get();
              mqttClient.send(
                      stateTopic, MqttMessageUtil.createMessage(valueBefore ? "OFF" : "ON", true));
              if (!valueBefore && currentBrightness.get() < 10)
                mqttClient.send(brightnessTopic, MqttMessageUtil.createMessage("255", true));
              handleSwitchOffSchedule.accept(
                      executorService.schedule(switchOff, switchOffTimer.getSeconds(), TimeUnit.SECONDS));
            };
    Runnable brighter =
            () -> {
              final int brightnessBefore = currentBrightness.get();
              final boolean stateBefore = currentState.get();
              if (!stateBefore) {
                mqttClient.send(brightnessTopic, MqttMessageUtil.createMessage("10", true));
                mqttClient.send(stateTopic, MqttMessageUtil.createMessage("ON", true));
              } else {
                final int v = Math.min(Math.max(brightnessBefore, 0) + 25, 255);
                mqttClient.send(
                        brightnessTopic, MqttMessageUtil.createMessage(Integer.toString(v), true));
              }
              handleSwitchOffSchedule.accept(
                      executorService.schedule(switchOff, switchOffTimer.getSeconds(), TimeUnit.SECONDS));
            };
    Runnable darker =
            () -> {
              final int brightnessBefore = currentBrightness.get();
              final int v = Math.max(Math.min(255, brightnessBefore) - 25, 0);
              mqttClient.send(
                      brightnessTopic, MqttMessageUtil.createMessage(Integer.toString(v), true));
              handleSwitchOffSchedule.accept(
                      executorService.schedule(switchOff, switchOffTimer.getSeconds(), TimeUnit.SECONDS));
            };
    Function<TriggerConfig, Consumer<Disposable>> triggerConsumers =
            key -> disposableConsumers.apply(id + "-trigger-" + key.getDiscovery_id());
    Function<BinarySensorConfig, Consumer<Disposable>> pirConsumers =
            key -> disposableConsumers.apply(id + "-trigger-" + key.getUnique_id());
    Consumer<ScheduledFuture<?>> handlePendingSchedule = new SinglePendingSchedulerConsumer();
    Runnable startBrighter =
            () ->
                    startRunning(
                            brighter,
                            () -> currentBrightness.get() < 255 || !currentState.get(),
                            handlePendingSchedule);
    Runnable startDarker =
            () ->
                    startRunning(
                            darker,
                            () -> currentBrightness.get() > 0 && currentState.get(),
                            handlePendingSchedule);
    Runnable stopTimer = () -> handlePendingSchedule.accept(null);

    Optional.ofNullable(triggers).stream()
            .flatMap(Collection::stream)
            .forEach(
                    deviceId -> {
                      configService.registerForDeviceAndConfiguration(
                              TriggerConfig.class,
                              deviceId,
                              new ConfigService.ConfigurationListener<>() {
                                @Override
                                public void notifyConfigAdded(final TriggerConfig configuration) {
                                  final String type = configuration.getType();
                                  final String subtype = configuration.getSubtype();
                                  final String payload = configuration.getPayload();
                                  final Optional<Runnable> payloadRunnable;
                                  switch (type) {
                                    case "button_short_press":
                                      payloadRunnable = Optional.empty();
                                      break;
                                    case "button_short_release":
                                      switch (subtype) {
                                        case "turn_on":
                                          payloadRunnable = Optional.of(switchOn);
                                          break;
                                        case "turn_off":
                                          payloadRunnable = Optional.of(switchOff);
                                          break;
                                        default:
                                          payloadRunnable = Optional.of(toggle);
                                          break;
                                      }
                                      break;
                                    case "button_long_press":
                                      switch (subtype) {
                                        case "turn_on":
                                          payloadRunnable = Optional.of(startBrighter);
                                          break;
                                        case "turn_off":
                                          payloadRunnable = Optional.of(startDarker);
                                          break;
                                        default:
                                          payloadRunnable = Optional.empty();
                                          break;
                                      }
                                      break;
                                    case "button_long_release":
                                      payloadRunnable = Optional.of(stopTimer);
                                      break;
                                    default:
                                      payloadRunnable = Optional.empty();
                                      break;
                                  }
                                  payloadRunnable.ifPresent(
                                          r ->
                                                  mqttClient.registerTopic(
                                                          configuration.getTopic(),
                                                          message -> {
                                                            if (payload.equals(
                                                                    new String(message.getMessage().getPayload()))) {
                                                              r.run();
                                                            }
                                                          },
                                                          triggerConsumers.apply(configuration)));
                                }

                                @Override
                                public void notifyConfigRemoved(final TriggerConfig configuration) {
                                  triggerConsumers.apply(configuration).accept(null);
                                }
                              });
                    });

    Optional.ofNullable(motionDetectors).stream()
            .flatMap(Collection::stream)
            .forEach(
                    deviceId -> {
                      configService.registerForDeviceAndConfiguration(
                              BinarySensorConfig.class,
                              deviceId,
                              new ConfigService.ConfigurationListener<>() {
                                @Override
                                public void notifyConfigAdded(final BinarySensorConfig configuration) {
                                  final String payloadOn = configuration.getPayload_on();
                                  final String payloadOff = configuration.getPayload_off();
                                  final String topic = configuration.getState_topic();
                                  mqttClient.registerTopic(
                                          topic,
                                          receivedMqttMessage -> {
                                            final String payload =
                                                    new String(receivedMqttMessage.getMessage().getPayload());
                                            if (hasNoTriggers) {
                                              if (payloadOn.equals(payload)) {
                                                switchOn.run();
                                              } else if (payloadOff.equals(payload)) {
                                                handleSwitchOffSchedule.accept(
                                                        executorService.schedule(switchOff, 2, TimeUnit.MINUTES));
                                              }
                                            } else {
                                              if (payloadOn.equals(payload)) {
                                                handleSwitchOffSchedule.accept(
                                                        executorService.schedule(
                                                                switchOff,
                                                                switchOffTimer.getSeconds(),
                                                                TimeUnit.SECONDS));
                                              } else if (payloadOff.equals(payload)) {
                                                handleSwitchOffSchedule.accept(
                                                        executorService.schedule(switchOff, 1, TimeUnit.HOURS));
                                              }
                                            }
                                          },
                                          pirConsumers.apply(configuration));
                                }

                                @Override
                                public void notifyConfigRemoved(final BinarySensorConfig configuration) {
                                  pirConsumers.apply(configuration).accept(null);
                                }
                              });
                    });

    mqttClient.registerTopic(
            stateTopic,
            message -> {
              final byte[] payload = message.getMessage().getPayload();
              final String state = new String(payload);
              if (log.isDebugEnabled()) log.debug("Taken state: " + state);
              currentState.set(state.equals("ON"));
              updater.run();
            },
            disposableConsumers.apply(id + "-state"));
    mqttClient.registerTopic(
            brightnessTopic,
            message -> {
              final byte[] payload = message.getMessage().getPayload();
              final String state = new String(payload);
              if (log.isDebugEnabled()) log.debug("Taken brightness: " + state);
              currentBrightness.set(Integer.parseInt(state));
              updater.run();
            },
            disposableConsumers.apply(id + "-brightness"));
  }

  private void startRunning(
          final Runnable action,
          final Supplier<Boolean> canContinue,
          final Consumer<ScheduledFuture<?>> handlePendingSchedule) {
    if (canContinue.get()) {
      action.run();
      handlePendingSchedule.accept(
              executorService.schedule(
                      () -> startRunning(action, canContinue, handlePendingSchedule),
                      500,
                      TimeUnit.MILLISECONDS));
    }
  }

  private int kelvin2Mireds(final int temperature) {
    return 1000000 / temperature;
  }

  private int mireds2Kelvin(final int temperature) {
    return 1000000 / temperature;
  }
}
