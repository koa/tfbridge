package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.properties.BrickletSettings;
import ch.bergturbenthal.home.tfbridge.domain.util.DisposableConsumer;
import com.tinkerforge.BrickletAnalogOutV2;
import com.tinkerforge.IPConnection;
import com.tinkerforge.NotConnectedException;
import com.tinkerforge.TimeoutException;
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
  public Disposable registerDevice(
          final String uid, final BrickletSettings settings, final IPConnection connection) {
    final BrickletAnalogOutV2 brickletAnalogOutV2 = new BrickletAnalogOutV2(uid, connection);
    final String name = settings.getName();
    String channelPrefix = "AnalogOutV2/" + name;
    final Consumer<Disposable> disposableConsumer = new DisposableConsumer();
    mqttClient.registerTopic(
            channelPrefix,
            mqttMessage -> {
              final int currentValue = Integer.parseInt(new String(mqttMessage.getPayload()));
              final int fencedValue = Math.min(127, Math.max(0, currentValue));
              int mvValue = fencedValue * 10000 / 128;
              try {
                brickletAnalogOutV2.setOutputVoltage(mvValue);
              } catch (TimeoutException | NotConnectedException e) {
                log.warn("Cannot update value on analog out " + name, e);
              }
            },
            disposableConsumer);
    return () -> disposableConsumer.accept(null);
  }
}
