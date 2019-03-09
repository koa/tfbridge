package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.properties.BrickletSettings;
import com.tinkerforge.BrickletTemperature;
import com.tinkerforge.IPConnection;
import com.tinkerforge.NotConnectedException;
import com.tinkerforge.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttWireMessage;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Service
@Slf4j
public class TemperatureBrickletHandler implements DeviceHandler {
  private final MqttClient mqttClient;

  public TemperatureBrickletHandler(final MqttClient mqttClient) {
    this.mqttClient = mqttClient;
  }

  @Override
  public int deviceId() {
    return BrickletTemperature.DEVICE_IDENTIFIER;
  }

  @Override
  public Disposable registerDevice(
          final String uid, final BrickletSettings settings, final IPConnection connection) {
    final BrickletTemperature bricklet = new BrickletTemperature(uid, connection);
    String topicPrefix = "BrickletTemperature/" + settings.getName();
    bricklet.addTemperatureListener(
            temperature -> {
              double realTemp = temperature / 100.0;
              final MqttMessage message = new MqttMessage();
              message.setQos(1);
              message.setPayload(String.valueOf(realTemp).getBytes());
              message.setRetained(true);
              final Mono<MqttWireMessage> publish =
                      mqttClient.publish(topicPrefix + "/temperature", message);
              publish.subscribe(result -> {
              }, ex -> log.warn("Cannot send motion detection"));
            });

    try {
      bricklet.setTemperatureCallbackPeriod(Duration.ofSeconds(1).toMillis());
    } catch (TimeoutException | NotConnectedException e) {
      log.warn("Cannot init temperature bricklet " + settings.getName(), e);
    }
    return () -> {
    };
  }
}
