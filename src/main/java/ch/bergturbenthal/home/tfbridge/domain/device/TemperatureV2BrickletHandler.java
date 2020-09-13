package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.util.MqttMessageUtil;
import com.tinkerforge.BrickletTemperature;
import com.tinkerforge.BrickletTemperatureV2;
import com.tinkerforge.IPConnection;
import com.tinkerforge.TinkerforgeException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttWireMessage;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;

@Slf4j
@Service
public class TemperatureV2BrickletHandler implements DeviceHandler {
  private final MqttClient mqttClient;

  public TemperatureV2BrickletHandler(final MqttClient mqttClient) {
    this.mqttClient = mqttClient;
  }

  @Override
  public int deviceId() {
    return BrickletTemperatureV2.DEVICE_IDENTIFIER;
  }

  @Override
  public Disposable registerDevice(final String uid, final IPConnection connection)
      throws TinkerforgeException {
    final BrickletTemperatureV2 bricklet = new BrickletTemperatureV2(uid, connection);
    String topicPrefix = "BrickletTemperature/" + uid;
    MqttMessageUtil.publishVersions(mqttClient, topicPrefix, bricklet.getIdentity());
    final BrickletTemperatureV2.TemperatureListener temperatureListener =
        temperature -> {
          double realTemp = temperature / 100.0;
          final MqttMessage message = new MqttMessage();
          message.setQos(1);
          message.setPayload(String.valueOf(realTemp).getBytes());
          message.setRetained(true);
          final Flux<MqttWireMessage> publish =
              mqttClient.publish(topicPrefix + "/temperature", message);
          publish.subscribe(result -> {}, ex -> log.warn("Cannot send motion detection"));
        };
    bricklet.addTemperatureListener(temperatureListener);
    bricklet.setTemperatureCallbackConfiguration(
        Duration.ofSeconds(1).toMillis(), false, 'x', 0, 0);
    final String stateTopic = topicPrefix + "/state";
    mqttClient.send(stateTopic, MqttMessageUtil.ONLINE_MESSAGE);
    return () -> {
      mqttClient.send(stateTopic, MqttMessageUtil.OFFLINE_MESSAGE);
      bricklet.removeTemperatureListener(temperatureListener);
    };
  }
}
