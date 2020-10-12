package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.ha.Device;
import ch.bergturbenthal.home.tfbridge.domain.ha.TriggerConfig;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.util.MqttMessageUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.tinkerforge.BrickletIO16V2;
import com.tinkerforge.IPConnection;
import com.tinkerforge.TinkerforgeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.util.function.Tuples;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@Service
public class IO16V2DeviceHandler implements DeviceHandler {
  private final MqttClient mqttClient;
  private final BridgeProperties properties;
  private final ScheduledExecutorService executorService;
  private final ObjectWriter configWriter;

  public IO16V2DeviceHandler(
      final MqttClient mqttClient,
      final BridgeProperties properties,
      ScheduledExecutorService executorService) {
    this.mqttClient = mqttClient;
    this.properties = properties;
    this.executorService = executorService;
    final ObjectMapper objectMapper =
        Jackson2ObjectMapperBuilder.json()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();
    configWriter = objectMapper.writerFor(TriggerConfig.class);
  }

  @Override
  public int deviceId() {
    return BrickletIO16V2.DEVICE_IDENTIFIER;
  }

  @Override
  public Disposable registerDevice(final String uid, final IPConnection connection)
      throws TinkerforgeException {
    final BrickletIO16V2 bricklet = new BrickletIO16V2(uid, connection);

    final String brickletPrefix = "IO16v2/" + uid;
    MqttMessageUtil.publishVersions(mqttClient, brickletPrefix, bricklet.getIdentity());
    bricklet.setStatusLEDConfig(0);

    Consumer<Boolean>[] buttonConsumers = new Consumer[16];
    Arrays.fill(buttonConsumers, (Consumer<Boolean>) (value) -> {});
    final BrickletIO16V2.InputValueListener inputValueListener =
        (channel, changed, value) -> buttonConsumers[channel].accept(!value);
    bricklet.addInputValueListener(inputValueListener);
    properties.getOnOffButtons().stream()
        .filter(b -> b.getIo16Bricklet().equals(uid))
        .forEach(
            onOffButtonInput -> {
              try {
                String buttonPrefix = brickletPrefix + "/button/" + onOffButtonInput.getId();
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

                final com.tinkerforge.Device.Identity identity = bricklet.getIdentity();
                final Device device =
                    Device.builder()
                        .identifiers(Collections.singletonList(onOffButtonInput.getId()))
                        .model("On Off Button")
                        .manufacturer("Tinkerforge")
                        .sw_version(identity.firmwareVersion[0] + "." + identity.firmwareVersion[1])
                        .name(onOffButtonInput.getName())
                        .build();
                Flux.just(
                        Tuples.of(onButtonTopic, "turn_on"), Tuples.of(offButtonTopic, "turn_off"))
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
                              .build();
                        })
                    .map(
                        c -> {
                          try {
                            return configWriter.writeValueAsString(c);
                          } catch (JsonProcessingException e) {
                            throw new RuntimeException(e);
                          }
                        })
                    .index()
                    .subscribe(
                        tuple -> {
                          final Long index = tuple.getT1();
                          final String config = tuple.getT2();
                          mqttClient.send(
                              properties.getDiscoveryPrefix()
                                  + "/device_automation/"
                                  + MqttMessageUtil.strip(onOffButtonInput.getId() + "-" + index)
                                  + "/config",
                              MqttMessageUtil.createMessage(config, true));
                        });

              } catch (TinkerforgeException e) {
                log.warn("Cannot configure device " + uid, e);
              }
            });

    return () -> bricklet.removeInputValueListener(inputValueListener);
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
                500,
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
      // log.info("Send: " + content);
      mqttClient.send(buttonTopic, MqttMessageUtil.createMessage(content, false));
    }
  }
}
