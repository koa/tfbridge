package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.ha.Device;
import ch.bergturbenthal.home.tfbridge.domain.ha.LightConfig;
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

import java.util.Arrays;
import java.util.Collections;
import java.util.function.Consumer;

@Slf4j
@Service
public class IO16V2DeviceHandler implements DeviceHandler {
  private final MqttClient mqttClient;
  private final BridgeProperties properties;
  private final ObjectWriter configWriter;

  public IO16V2DeviceHandler(final MqttClient mqttClient, final BridgeProperties properties) {
    this.mqttClient = mqttClient;
    this.properties = properties;
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
    bricklet.addInputValueListener(
        (channel, changed, value) -> buttonConsumers[channel].accept(value));
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
                buttonConsumers[onAddress] =
                    pressed -> {
                      mqttClient.send(
                          onButtonTopic,
                          MqttMessageUtil.createMessage(String.valueOf(!pressed), false));
                      log.info("On Buttton pressed: " + pressed);
                    };
                buttonConsumers[offAddress] =
                    pressed -> {
                      mqttClient.send(
                          offButtonTopic,
                          MqttMessageUtil.createMessage(String.valueOf(!pressed), false));
                      log.info("Off Buttton pressed: " + pressed);
                    };

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
                        TriggerConfig.builder()
                            .automation_type("trigger")
                            .topic(offButtonTopic)
                            .device(device)
                            .payload("true")
                            .type("button_short_press")
                            .subtype("turn_off")
                            .qos(1)
                            .platform("mqtt")
                            .build(),
                        TriggerConfig.builder()
                            .automation_type("trigger")
                            .topic(offButtonTopic)
                            .device(device)
                            .payload("false")
                            .type("button_short_release")
                            .subtype("turn_off")
                            .qos(1)
                            .platform("mqtt")
                            .build(),
                        TriggerConfig.builder()
                            .automation_type("trigger")
                            .topic(onButtonTopic)
                            .device(device)
                            .payload("true")
                            .type("button_short_press")
                            .subtype("turn_on")
                            .qos(1)
                            .platform("mqtt")
                            .build(),
                        TriggerConfig.builder()
                            .automation_type("trigger")
                            .topic(onButtonTopic)
                            .device(device)
                            .payload("false")
                            .type("button_short_release")
                            .subtype("turn_on")
                            .qos(1)
                            .platform("mqtt")
                            .build())
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
                                  + strip(onOffButtonInput.getId() + "-" + index)
                                  + "/config",
                              MqttMessageUtil.createMessage(config, true));
                        });

              } catch (TinkerforgeException e) {
                log.warn("Cannot configure device " + uid, e);
              }
            });

    return () -> {};
  }

  private String strip(final String s) {
    // return s;
    return s.replaceAll("[^A-Za-z0-9]", "_");
  }
}
