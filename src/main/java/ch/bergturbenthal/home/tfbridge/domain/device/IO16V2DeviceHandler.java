package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.ha.Device;
import ch.bergturbenthal.home.tfbridge.domain.ha.TriggerConfig;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.service.ConfigService;
import ch.bergturbenthal.home.tfbridge.domain.util.MqttMessageUtil;
import com.tinkerforge.BrickletIO16V2;
import com.tinkerforge.IPConnection;
import com.tinkerforge.TinkerforgeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.util.function.Tuples;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class IO16V2DeviceHandler implements DeviceHandler {
  private final MqttClient               mqttClient;
  private final BridgeProperties         properties;
  private final ScheduledExecutorService executorService;
  private final ConfigService            configService;

  public IO16V2DeviceHandler(
          final MqttClient mqttClient,
          final BridgeProperties properties,
          ScheduledExecutorService executorService,
          final ConfigService configService) {
    this.mqttClient = mqttClient;
    this.properties = properties;
    this.executorService = executorService;
    this.configService = configService;
  }

  @Override
  public int deviceId() {
    return BrickletIO16V2.DEVICE_IDENTIFIER;
  }

  @Override
  public Disposable registerDevice(
      final String uid, final IPConnection connection, final Consumer<Throwable> errorConsumer)
      throws TinkerforgeException {
    final BrickletIO16V2 bricklet = new BrickletIO16V2(uid, connection);

    final String brickletPrefix = "IO16v2/" + uid;
    MqttMessageUtil.publishVersions(mqttClient, brickletPrefix, bricklet.getIdentity());
    bricklet.setStatusLEDConfig(0);

    Consumer<Boolean>[] buttonConsumers = new Consumer[16];
    Arrays.fill(buttonConsumers, (Consumer<Boolean>) (value) -> {
    });
    final BrickletIO16V2.InputValueListener inputValueListener =
            (channel, changed, value) -> buttonConsumers[channel].accept(!value);
    bricklet.addInputValueListener(inputValueListener);
    final com.tinkerforge.Device.Identity identity = bricklet.getIdentity();

    final List<TriggerConfig> configs =
            Stream.concat(
                    Optional.ofNullable(properties.getOnOffButtons()).stream()
                            .flatMap(Collection::stream)
                            .filter(b -> b.getIo16Bricklet().equals(uid))
                            .flatMap(
                                    onOffButtonInput -> {
                                      try {
                                        String buttonPrefix =
                                                brickletPrefix + "/button/" + onOffButtonInput.getId();
                                        String onButtonTopic = buttonPrefix + "/on";
                                        String offButtonTopic = buttonPrefix + "/off";

                                        final int onAddress = onOffButtonInput.getOnAddress();
                                        final int offAddress = onOffButtonInput.getOffAddress();
                                        bricklet.setConfiguration(onAddress, 'i', true);
                                        bricklet.setInputValueCallbackConfiguration(onAddress, 5, true);
                                        bricklet.setConfiguration(offAddress, 'i', true);
                                        bricklet.setInputValueCallbackConfiguration(offAddress, 5, true);
                                        buttonConsumers[onAddress] = new ButtonHandler(onButtonTopic);
                                        buttonConsumers[offAddress] = new ButtonHandler(offButtonTopic);

                                        final Device device =
                                                Device.builder()
                                                      .identifiers(
                                                              Collections.singletonList(onOffButtonInput.getId()))
                                                      .model("On Off Button")
                                                      .manufacturer("Tinkerforge")
                                                      .sw_version(
                                                              identity.firmwareVersion[0]
                                                                      + "."
                                                                      + identity.firmwareVersion[1])
                                                      .name(onOffButtonInput.getName())
                                                      .build();
                                        return Flux.just(
                                                Tuples.of(onButtonTopic, "turn_on"),
                                                Tuples.of(offButtonTopic, "turn_off"))
                                                   .flatMap(
                                                           topic ->
                                                                   Flux.just(
                                                                           "button_short_press",
                                                                           "button_short_release",
                                                                           "button_long_press",
                                                                           "button_long_release")
                                                                       .map(event -> Tuples.of(topic, event)))
                                                   .map(
                                                           t -> {
                                                             final String topic = t.getT1().getT1();
                                                             final String subType = t.getT1().getT2();
                                                             final String event = t.getT2();
                                                             return TriggerConfig.builder()
                                                                                 .automation_type("trigger")
                                                                                 .topic(topic)
                                                                                 .device(device)
                                                                                 .payload(event)
                                                                                 .type(event)
                                                                                 .subtype(subType)
                                                                                 .qos(1)
                                                                                 .platform("mqtt")
                                                                                 .discovery_id(
                                                                                         MqttMessageUtil.strip(
                                                                                                 onOffButtonInput.getId()
                                                                                                         + "-"
                                                                                                         + subType
                                                                                                         + "-"
                                                                                                         + event))
                                                                                 .build();
                                                           })
                                                   .collectList()
                                                   .block()
                                                   .stream();

                                      } catch (TinkerforgeException e) {
                                        log.warn("Cannot configure device " + uid, e);
                                        errorConsumer.accept(e);
                                        return Stream.empty();
                                      }
                                    }),
                    Optional.ofNullable(properties.getSingleButtons()).stream()
                            .flatMap(Collection::stream)
                            .filter(b1 -> b1.getIo16Bricklet().equals(uid))
                            .flatMap(
                                    singleButtonInput -> {
                                      try {
                                        String buttonPrefix1 =
                                                brickletPrefix + "/button/" + singleButtonInput.getId();
                                        String buttonTopic = buttonPrefix1 + "/action";
                                        final int address = singleButtonInput.getAddress();
                                        bricklet.setConfiguration(address, 'i', true);
                                        bricklet.setInputValueCallbackConfiguration(address, 5, true);
                                        buttonConsumers[address] = new ButtonHandler(buttonTopic);
                                        final Device device1 =
                                                Device.builder()
                                                      .identifiers(
                                                              Collections.singletonList(singleButtonInput.getId()))
                                                      .model("Single Button")
                                                      .manufacturer("Tinkerforge")
                                                      .sw_version(
                                                              identity.firmwareVersion[0]
                                                                      + "."
                                                                      + identity.firmwareVersion[1])
                                                      .name(singleButtonInput.getName())
                                                      .build();
                                        return Stream.of(
                                                "button_short_press",
                                                "button_short_release",
                                                "button_long_press",
                                                "button_long_release")
                                                     .map(
                                                             event1 -> {
                                                               final String subType1 = "button_1";
                                                               return TriggerConfig.builder()
                                                                                   .automation_type("trigger")
                                                                                   .topic(buttonTopic)
                                                                                   .device(device1)
                                                                                   .payload(event1)
                                                                                   .type(event1)
                                                                                   .subtype(subType1)
                                                                                   .qos(1)
                                                                                   .platform("mqtt")
                                                                                   .discovery_id(
                                                                                           MqttMessageUtil.strip(
                                                                                                   singleButtonInput.getId() + "-" + event1))
                                                                                   .build();
                                                             });

                                      } catch (TinkerforgeException e1) {
                                        log.warn("Cannot configure device " + uid, e1);
                                        errorConsumer.accept(e1);
                                        return Stream.empty();
                                      }
                                    }))
                  .collect(Collectors.toList());

    configs.forEach(configService::publishConfig);
    return () -> {
      bricklet.removeInputValueListener(inputValueListener);
      configs.forEach(configService::unpublishConfig);
    };
  }

  private class ButtonHandler implements Consumer<Boolean> {
    private final String buttonTopic;
    private final AtomicReference<ScheduledFuture<?>> currentRunningSchedule =
        new AtomicReference<>(null);
    private final AtomicBoolean isLongPressing = new AtomicBoolean(false);

    public ButtonHandler(final String buttonTopic) {
      this.buttonTopic = buttonTopic;
    }

    @Override
    public void accept(final Boolean pressed) {
      // log.info("pressed: " + pressed);
      if (pressed) {
        sendMessage("button_short_press");
        isLongPressing.set(false);
        final ScheduledFuture<?> schedule =
            executorService.schedule(
                    () -> {
                      sendMessage("button_long_press");
                      isLongPressing.set(true);
                    },
                    250,
                    TimeUnit.MILLISECONDS);
        final ScheduledFuture<?> runningFuture = currentRunningSchedule.getAndSet(schedule);
        if (runningFuture != null && !runningFuture.isDone()) runningFuture.cancel(true);
      } else {
        final ScheduledFuture<?> future = currentRunningSchedule.get();
        if (future != null && !future.isDone()) future.cancel(false);
        sendMessage(isLongPressing.get() ? "button_long_release" : "button_short_release");
      }
    }

    private void sendMessage(final String content) {
      // log.info("Send: " + buttonTopic + ";" + content);
      mqttClient.send(buttonTopic, MqttMessageUtil.createMessage(content, false));
    }
  }
}
