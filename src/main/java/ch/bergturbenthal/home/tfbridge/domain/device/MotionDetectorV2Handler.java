package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.properties.BrickletSettings;
import ch.bergturbenthal.home.tfbridge.domain.util.DisposableConsumer;
import com.tinkerforge.BrickletMotionDetectorV2;
import com.tinkerforge.IPConnection;
import com.tinkerforge.NotConnectedException;
import com.tinkerforge.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttWireMessage;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
@Slf4j
public class MotionDetectorV2Handler implements DeviceHandler {
  private final MqttClient mqttClient;

  public MotionDetectorV2Handler(final MqttClient mqttClient) {
    this.mqttClient = mqttClient;
  }

  @Override
  public int deviceId() {
    return BrickletMotionDetectorV2.DEVICE_IDENTIFIER;
  }

  @Override
  public Disposable registerDevice(
          final String uid, final BrickletSettings settings, final IPConnection connection) {
    final String name = settings.getName();
    final BrickletMotionDetectorV2 bricklet = new BrickletMotionDetectorV2(uid, connection);
    String topicPrefix = "BrickletMotionDetectorV2/" + name;
    final AtomicInteger counter = new AtomicInteger();
    final Consumer<Disposable> sensitivityConsumer;
    try {
      bricklet.addMotionDetectedListener(
              () -> {
                log.info("Motion detected at " + settings);
                final int number = counter.incrementAndGet();
                final MqttMessage message = new MqttMessage();
                message.setQos(1);
                message.setPayload(String.valueOf(number).getBytes());
                final Mono<MqttWireMessage> publish =
                        mqttClient.publish(topicPrefix + "/motion", message);
                publish.subscribe(result -> {
                  log.info("Message sent: " + result);
                }, ex -> log.warn("Cannot send motion detection"));
              });
      sensitivityConsumer = new DisposableConsumer();

      mqttClient.registerTopic(
              topicPrefix + "/sensitivity",
              message -> {
                try {
                  final Integer sensitivity = Integer.valueOf(new String(message.getPayload()));
                  bricklet.setSensitivity(sensitivity);
                } catch (TimeoutException | NotConnectedException e) {
                  log.warn("Cannot update sensitivity on " + name, e);
                }
              },
              sensitivityConsumer);
      bricklet.setIndicator(0, 0, 0);
      bricklet.setStatusLEDConfig(BrickletMotionDetectorV2.STATUS_LED_CONFIG_OFF);
      return () -> {
        sensitivityConsumer.accept(null);
      };
    } catch (TimeoutException | NotConnectedException e) {
      log.warn("Cannot communicate to bricklet " + name, e);
      return () -> {
      };
    }
  }
}
