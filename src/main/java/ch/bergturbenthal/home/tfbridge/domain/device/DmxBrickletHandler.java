package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.properties.BrickletSettings;
import ch.bergturbenthal.home.tfbridge.domain.util.DisposableConsumer;
import com.tinkerforge.BrickletDMX;
import com.tinkerforge.IPConnection;
import com.tinkerforge.NotConnectedException;
import com.tinkerforge.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
  public Disposable registerDevice(
          final String uid, final BrickletSettings settings, final IPConnection connection) {
    final BrickletDMX bricklet = new BrickletDMX(uid, connection);
    final String[] dmxChannels =
            settings.getDmxChannels() == null
            ? new String[0]
            : settings.getDmxChannels().toArray(new String[0]);
    try {
      List<Consumer<Disposable>> disposableConsumers = new ArrayList<>(dmxChannels.length);
      AtomicInteger[] currentValues = new AtomicInteger[dmxChannels.length];
      for (int i = 0; i < dmxChannels.length; i++) {
        final AtomicInteger currentValue = new AtomicInteger(0);
        currentValues[i] = currentValue;
        final String dmxChannelName = dmxChannels[i];
        String channelPrefix = "DMXChannel/" + dmxChannelName;
        final Consumer<Disposable> disposableConsumer = new DisposableConsumer();
        mqttClient.registerTopic(
                channelPrefix,
                mqttMessage -> {
                  currentValue.set(Integer.parseInt(new String(mqttMessage.getPayload())));
                  try {
                    int[] writeValues = new int[currentValues.length];
                    for (int j = 0; j < currentValues.length; j++) {
                      writeValues[j] = currentValues[j].get();
                    }
                    bricklet.writeFrame(writeValues);
                  } catch (TimeoutException | NotConnectedException e) {
                    log.warn("Cannot write frame for dmx bus", e);
                  }
                },
                disposableConsumer);
        disposableConsumers.add(disposableConsumer);
      }
      bricklet.setDMXMode(BrickletDMX.DMX_MODE_MASTER);
      bricklet.setCommunicationLEDConfig(BrickletDMX.COMMUNICATION_LED_CONFIG_SHOW_COMMUNICATION);
      return () -> {
        disposableConsumers.forEach(d -> d.accept(null));
      };

    } catch (TimeoutException | NotConnectedException e) {
      log.warn("Cannot communicate to bricklet " + settings.getName(), e);
    }

    return () -> {
    };
  }
}
