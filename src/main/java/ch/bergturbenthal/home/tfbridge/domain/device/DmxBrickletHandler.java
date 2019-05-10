package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.util.DisposableConsumer;
import ch.bergturbenthal.home.tfbridge.domain.util.MqttMessageUtil;
import com.tinkerforge.BrickletDMX;
import com.tinkerforge.IPConnection;
import com.tinkerforge.TinkerforgeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class DmxBrickletHandler implements DeviceHandler {
  private final MqttClient mqttClient;

  public DmxBrickletHandler(final MqttClient mqttClient) {
    this.mqttClient = mqttClient;
  }

  @Override
  public int deviceId() {
    return BrickletDMX.DEVICE_IDENTIFIER;
  }

  @Override
  public Disposable registerDevice(final String uid, final IPConnection connection)
          throws TinkerforgeException {
    final BrickletDMX bricklet = new BrickletDMX(uid, connection);

    final String brickletPrefix = "DMXBricklet/" + uid;
    MqttMessageUtil.publishVersions(mqttClient, brickletPrefix, bricklet.getIdentity());

    String channelPrefix = brickletPrefix + "/channel/";

    final DisposableConsumer channelRegistrationConsumer = new DisposableConsumer();
    AtomicReference<List<Integer>> currentChannelValues =
            new AtomicReference<>(Collections.emptyList());
    mqttClient.registerTopic(
            channelPrefix + "+",
            mqttMessage -> {
              try {
                final String topic = mqttMessage.getTopic();
                final String indexStr = topic.substring(channelPrefix.length());
                final int channelIndex = Integer.parseInt(indexStr);
                if (channelIndex < 0) return;
                if (channelIndex > 511) return;
                final double doubleValue =
                        Double.parseDouble(new String(mqttMessage.getMessage().getPayload()));
                final double fencedValue = Math.max(0, Math.min(1, doubleValue));
                final int newValue = (int) Math.round(fencedValue * 255);
                while (true) {
                  final List<Integer> oldValues = currentChannelValues.get();
                  if (oldValues.size() > channelIndex && oldValues.get(channelIndex) == newValue)
                    return;
                  final List<Integer> newValues = new ArrayList<>(oldValues);
                  while (newValues.size() <= channelIndex) newValues.add(0);
                  newValues.set(channelIndex, newValue);
                  if (currentChannelValues.compareAndSet(oldValues, newValues)) break;
                }
                final List<Integer> valuesToWrite = currentChannelValues.get();
                int[] writeValues = new int[valuesToWrite.size()];
                for (int j = 0; j < valuesToWrite.size(); j++) {
                  writeValues[j] = valuesToWrite.get(j);
                }
                //log.info("Update " + uid + ": " + valuesToWrite);
                bricklet.writeFrame(writeValues);
              } catch (Exception e) {
                log.warn("Cannot write frame to dmx bus on " + uid, e);
              }
            },
            channelRegistrationConsumer);
    bricklet.setDMXMode(BrickletDMX.DMX_MODE_MASTER);
    bricklet.setCommunicationLEDConfig(BrickletDMX.COMMUNICATION_LED_CONFIG_SHOW_COMMUNICATION);
    bricklet.setFrameDuration(0);
    final String stateTopic = brickletPrefix + "/state";
    mqttClient.send(stateTopic, MqttMessageUtil.ONLINE_MESSAGE);
    return () -> {
      mqttClient.send(stateTopic, MqttMessageUtil.OFFLINE_MESSAGE);
      channelRegistrationConsumer.accept(null);
    };
  }
}
