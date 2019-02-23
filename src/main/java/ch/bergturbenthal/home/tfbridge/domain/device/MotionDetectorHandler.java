package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.properties.BrickletSettings;
import com.tinkerforge.BrickletMotionDetector;
import com.tinkerforge.IPConnection;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttWireMessage;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class MotionDetectorHandler implements DeviceHandler {
  private final MqttClient mqttClient;

  public MotionDetectorHandler(final MqttClient mqttClient) {
    this.mqttClient = mqttClient;
  }

  @Override
  public int deviceId() {
    return BrickletMotionDetector.DEVICE_IDENTIFIER;
  }

  @Override
  public Disposable registerDevice(
          final String uid, final BrickletSettings settings, final IPConnection connection) {
    final BrickletMotionDetector bricklet = new BrickletMotionDetector(uid, connection);
    String topicPrefix = "BrickletMotionDetector/" + settings.getName();
    final AtomicInteger counter = new AtomicInteger();
    bricklet.addMotionDetectedListener(
            () -> {
              final int number = counter.incrementAndGet();
              final MqttMessage message = new MqttMessage();
              message.setQos(1);
              message.setPayload(String.valueOf(number).getBytes());
              final Mono<MqttWireMessage> publish =
                      mqttClient.publish(topicPrefix + "/motion", message);
              publish.subscribe(result -> {
              }, ex -> log.warn("Cannot send motion detection"));
            });

    return () -> {
    };
  }
}
