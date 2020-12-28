package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.ha.*;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.service.ConfigService;
import ch.bergturbenthal.home.tfbridge.domain.util.DisposableConsumer;
import ch.bergturbenthal.home.tfbridge.domain.util.MqttMessageUtil;
import com.tinkerforge.BrickletIndustrialQuadRelayV2;
import com.tinkerforge.IPConnection;
import com.tinkerforge.TinkerforgeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@Slf4j
public class QuadRelayBrickletHandler implements DeviceHandler {
  private final MqttClient       mqttClient;
  private final BridgeProperties properties;

  private final ConfigService configService;

  public QuadRelayBrickletHandler(
          final MqttClient mqttClient,
          final BridgeProperties properties,
          final ConfigService configService) {
    this.mqttClient = mqttClient;
    this.properties = properties;
    this.configService = configService;
  }

  @Override
  public int deviceId() {
    return BrickletIndustrialQuadRelayV2.DEVICE_IDENTIFIER;
  }

  @Override
  public Disposable registerDevice(
          final String uid, final IPConnection connection, final Consumer<Throwable> errorConsumer)
          throws TinkerforgeException {
    final BrickletIndustrialQuadRelayV2 bricklet =
            new BrickletIndustrialQuadRelayV2(uid, connection);

    String brickletPrefix = "BrickletQuadRelayV2/" + uid;
    MqttMessageUtil.publishVersions(mqttClient, brickletPrefix, bricklet.getIdentity());
    final String stateTopic = brickletPrefix + "/state";

    boolean[] states = new boolean[4];
    BiConsumer<Integer, Boolean> stateConsumer =
            new BiConsumer<>() {
              @Override
              public synchronized void accept(final Integer index, final Boolean state) {
                if (states[index] == state) return;
                states[index] = state;
                try {
                  bricklet.setValue(states);
                } catch (TinkerforgeException e) {
                  log.error("Cannot update states of " + uid, e);
                }
              }
            };

    final List<DisposableConsumer> bellRegistrations =
            Optional.ofNullable(properties.getBells()).stream()
                    .flatMap(Collection::stream)
                    .filter(b -> b.getBricklet().equals(uid))
                    .map(
                            bell -> {
                              final int outputIndex = bell.getAddress();
                              DisposableConsumer disposableConsumer = new DisposableConsumer();
                              final String keyId = bell.getKeyId() + "_button_short_release";
                              // log.info("Lookup for " + keyId);
                              configService.registerForConfiguration(
                                      TriggerConfig.class,
                                      keyId,
                                      new ConfigService.ConfigurationListener<>() {
                                        @Override
                                        public void notifyConfigAdded(final TriggerConfig configuration) {
                                          log.info(
                                                  "Register on " + bell.getName() + ": " + configuration.getTopic());
                                          mqttClient.registerTopic(
                                                  configuration.getTopic(),
                                                  receivedMqttMessage -> {
                                                    final String message =
                                                            new String(receivedMqttMessage.getMessage().getPayload());
                                                    // log.info("Message on " + bell.getName() + ": " + message);
                                                    if (message.equals(configuration.getPayload())) {
                                                      try {
                                                        bricklet.setMonoflop(outputIndex, true, 1000);
                                                      } catch (TinkerforgeException e) {
                                                        log.error(
                                                                "Cannot set monoflop " + outputIndex + " of " + uid, e);
                                                      }
                                                    }
                                                  },
                                                  disposableConsumer);
                                        }

                                        @Override
                                        public void notifyConfigRemoved(final TriggerConfig configuration) {
                                          disposableConsumer.accept(null);
                                        }
                                      });
                              return disposableConsumer;
                            })
                    .collect(Collectors.toList());

    final List<PublishingConfig> configs =
            Optional.ofNullable(properties.getHeaters()).stream()
                    .flatMap(Collection::stream)
                    .filter(h -> h.getBricklet().equals(uid))
                    .map(
                            heater -> {
                              final int outputIndex = heater.getAddress();
                              String heaterPrefix = brickletPrefix + "/heater/" + heater.getId();
                              String targetTemperatureTopic = heaterPrefix + "/target";
                              String targetTemperatureStateTopic = heaterPrefix + "/targetState";
                              String currentTemperatureTopic = heaterPrefix + "/current";
                              String currentStateTopic = heaterPrefix + "/state";

                              DisposableConsumer disposableConsumer = new DisposableConsumer();
                              final String sensorId = heater.getSensorId();
                              // log.info("Found heater: " + heater.getId() + " lookup for " + sensorId);
                              AtomicReference<Double> currentTemperature = new AtomicReference<>(20d);
                              AtomicReference<Double> targetTemperature = new AtomicReference<>(21d);
                              final Runnable updater =
                                      () -> {
                                        final Double targetTemp = targetTemperature.get();
                                        boolean on = targetTemp > currentTemperature.get();
                                        stateConsumer.accept(outputIndex, on);
                                        mqttClient.send(
                                                currentStateTopic,
                                                MqttMessageUtil.createMessage(on ? "heat" : "off", true));
                                        mqttClient.send(
                                                targetTemperatureStateTopic,
                                                MqttMessageUtil.createMessage(Double.toString(targetTemp), true));
                                      };

                              configService.registerForConfiguration(
                                      SensorConfig.class,
                                      sensorId,
                                      new ConfigService.ConfigurationListener<>() {
                                        @Override
                                        public void notifyConfigAdded(final SensorConfig configuration) {
                          /*log.info(
                             "Found configuration for sensor of "
                                 + heater.getId()
                                 + ": "
                                 + sensorId);
                          */
                                          mqttClient.registerTopic(
                                                  configuration.getState_topic(),
                                                  receivedMqttMessage -> {
                                                    final Double newTemperature =
                                                            Double.valueOf(
                                                                    new String(receivedMqttMessage.getMessage()
                                                                                                  .getPayload()));
                                                    final Double oldTemperature =
                                                            currentTemperature.getAndSet(newTemperature);
                                                    updater.run();
                                                    if (newTemperature.equals(oldTemperature)) {
                                                      mqttClient.send(
                                                              currentTemperatureTopic,
                                                              MqttMessageUtil.createMessage(
                                                                      Double.toString(newTemperature), true));
                                                    }
                                                  },
                                                  disposableConsumer);
                                        }

                                        @Override
                                        public void notifyConfigRemoved(final SensorConfig configuration) {}
                                      });
                              mqttClient.registerTopic(
                                      targetTemperatureTopic,
                                      message -> {
                                        final byte[] payload = message.getMessage().getPayload();
                                        final String state = new String(payload);
                                        if (log.isDebugEnabled()) log.debug("Taken temperature: " + state);
                                        targetTemperature.set(Double.parseDouble(state));
                                        updater.run();
                                      },
                                      new DisposableConsumer());
                              updater.run();
                              return ClimateConfig.builder()
                                                  .platform("mqtt")
                                                  .qos(1)
                                                  .retain(true)
                                                  .temperature_unit("C")
                                                  .availability(
                                                          Collections.singletonList(
                                                                  Availability.builder().topic(stateTopic).build()))
                                                  .device(
                                                          Device.builder()
                                                                .name(heater.getName())
                                                                .identifiers(Collections.singletonList(heater.getId()))
                                                                .build())
                                                  .name(heater.getName())
                                                  .unique_id(heater.getId())
                                                  .temperature_command_topic(targetTemperatureTopic)
                                                  .current_temperature_topic(currentTemperatureTopic)
                                                  .temperature_state_topic(targetTemperatureStateTopic)
                                                  .mode_state_topic(currentStateTopic)
                                                  .modes(Arrays.asList("heat", "off"))
                                                  .min_temp(12)
                                                  .max_temp(26)
                                                  .temp_step(0.5)
                                                  .build();
                            })
                    .collect(Collectors.toList());
    configs.forEach(configService::publishConfig);
    bricklet.setValue(states);
    mqttClient.send(stateTopic, MqttMessageUtil.ONLINE_MESSAGE);

    return () -> {
      mqttClient.send(stateTopic, MqttMessageUtil.OFFLINE_MESSAGE);
      bellRegistrations.forEach(c -> c.accept(null));
      configs.forEach(configService::unpublishConfig);
    };
  }
}
