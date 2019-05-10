package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.util.MqttMessageUtil;
import com.tinkerforge.BrickletMotionDetector;
import com.tinkerforge.IPConnection;
import com.tinkerforge.NotConnectedException;
import com.tinkerforge.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttWireMessage;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

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
  public Disposable registerDevice(final String uid, final IPConnection connection) throws
                                                                                    TimeoutException,
                                                                                    NotConnectedException {
    final BrickletMotionDetector bricklet = new BrickletMotionDetector(uid, connection);
    String topicPrefix = "BrickletMotionDetector/" + uid;
    MqttMessageUtil.publishVersions(mqttClient, topicPrefix, bricklet.getIdentity());
    final AtomicInteger counter = new AtomicInteger();
    final BrickletMotionDetector.MotionDetectedListener motionDetectionListener =
            () -> {
              final int number = counter.incrementAndGet();
              final MqttMessage message = new MqttMessage();
              message.setQos(1);
              message.setPayload(String.valueOf(number).getBytes());
              final Flux<MqttWireMessage> publish =
                      mqttClient.publish(topicPrefix + "/motion", message);
              publish.subscribe(result -> {
              }, ex -> log.warn("Cannot send motion detection"));
            };
    bricklet.addMotionDetectedListener(motionDetectionListener);
    final String stateTopic = topicPrefix + "/state";
    mqttClient.send(stateTopic, MqttMessageUtil.ONLINE_MESSAGE);
    return () -> {
      mqttClient.send(stateTopic, MqttMessageUtil.OFFLINE_MESSAGE);
      bricklet.removeMotionDetectedListener(motionDetectionListener);
    };
  }
}
