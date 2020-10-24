package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.util.DisposableConsumer;
import ch.bergturbenthal.home.tfbridge.domain.util.MqttMessageUtil;
import com.tinkerforge.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.function.Consumer;

@Component
@Slf4j
public class AnalogOutBrickletHandlerV2 implements DeviceHandler {
  private final MqttClient mqttClient;

  public AnalogOutBrickletHandlerV2(final MqttClient mqttClient) {
    this.mqttClient = mqttClient;
  }

  @Override
  public int deviceId() {
    return BrickletAnalogOutV2.DEVICE_IDENTIFIER;
  }

  @Override
  public Disposable registerDevice(final String uid,
                                   final IPConnection connection,
                                   final Consumer<Throwable> errorConsumer)
      throws TinkerforgeException {
    final BrickletAnalogOutV2 brickletAnalogOutV2 = new BrickletAnalogOutV2(uid, connection);
    String channelPrefix = "AnalogOutV2/" + uid;

    final Device.Identity identity = brickletAnalogOutV2.getIdentity();
    MqttMessageUtil.publishVersions(mqttClient, channelPrefix, identity);

    final Consumer<Disposable> disposableConsumer = new DisposableConsumer();
    mqttClient.registerTopic(
        channelPrefix + "/value",
        mqttMessage -> {
          final double currentValue =
              Double.parseDouble(new String(mqttMessage.getMessage().getPayload()));
          final double fencedValue = Math.min(1, Math.max(0, currentValue));
          int mvValue = (int) Math.round(fencedValue * 10000);
          try {
            brickletAnalogOutV2.setOutputVoltage(mvValue);
          } catch (TinkerforgeException e) {
            log.warn("Cannot update value on analog out " + uid, e);
            errorConsumer.accept(e);
          }
        },
        disposableConsumer);
    final String stateTopic = channelPrefix + "/state";
    mqttClient.send(stateTopic, MqttMessageUtil.ONLINE_MESSAGE);
    return () -> {
      mqttClient.send(stateTopic, MqttMessageUtil.OFFLINE_MESSAGE);
      disposableConsumer.accept(null);
    };
  }
}
