package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.util.MqttMessageUtil;
import com.tinkerforge.BrickMaster;
import com.tinkerforge.IPConnection;
import com.tinkerforge.TinkerforgeException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

@Service
@Slf4j
public class MasterBrickHandler implements DeviceHandler {
  private final MqttClient mqttClient;
  private final ScheduledExecutorService scheduledExecutorService;

  public MasterBrickHandler(
      final MqttClient mqttClient, final ScheduledExecutorService scheduledExecutorService) {
    this.mqttClient = mqttClient;
    this.scheduledExecutorService = scheduledExecutorService;
  }

  @Override
  public int deviceId() {
    return BrickMaster.DEVICE_IDENTIFIER;
  }

  @Override
  public Disposable registerDevice(
      final String uid, final IPConnection connection, final Consumer<Throwable> errorConsumer)
      throws TinkerforgeException {
    final BrickMaster brickMaster = new BrickMaster(uid, connection);
    final String topicPrefix = "Master/" + uid;
    MqttMessageUtil.publishVersions(mqttClient, topicPrefix, brickMaster.getIdentity());

    final String currentTopic = topicPrefix + "/current";
    final BrickMaster.StackCurrentListener stackCurrentListener =
            current -> mqttClient.send(currentTopic, createValueMessage(current));
    brickMaster.addStackCurrentListener(stackCurrentListener);
    final String voltageTopic = topicPrefix + "/voltage";
    final BrickMaster.StackVoltageListener stackVoltageListener =
            voltage -> mqttClient.send(voltageTopic, createValueMessage(voltage));
    brickMaster.addStackVoltageListener(stackVoltageListener);
    final String usbVoltageTopic = topicPrefix + "/usbVoltage";
    final BrickMaster.USBVoltageListener usbVoltageListener =
            voltage -> mqttClient.send(usbVoltageTopic, createValueMessage(voltage));
    brickMaster.addUSBVoltageListener(usbVoltageListener);
    brickMaster.setStackCurrentCallbackPeriod(60000);
    brickMaster.setStackVoltageCallbackPeriod(60000);
    brickMaster.setUSBVoltageCallbackPeriod(60000);
    // AtomicInteger lastTemperatureValue = new AtomicInteger(Integer.MIN_VALUE);
    /*
        final ScheduledFuture<?> scheduledFuture =
            scheduledExecutorService.scheduleWithFixedDelay(
                () -> {
                  try {
                    final int chipTemperature = brickMaster.getChipTemperature();
                    final int lastValue = lastTemperatureValue.getAndSet(chipTemperature);
                    if (lastValue != chipTemperature) {
                      mqttClient.send(
                          topicPrefix + "/chipTemperature",
                          MqttMessageUtil.createMessage(
                              Double.toString(chipTemperature / 100.0), true));
                    }
                  } catch (TimeoutException e) {
                    log.warn("Cannot take chip temperature from " + uid, e);
                    scheduledExecutorService.submit(
                        () -> {
                          try {
                            connection.enumerate();
                          } catch (NotConnectedException e1) {
                            log.warn("Cannot enumerate connection again", e1);
                          }
                        });
                  } catch (TinkerforgeException e) {
                    log.warn("Cannot take chip temperature from " + uid, e);
                    errorConsumer.accept(e);
                  }
                },
                10,
                10,
                TimeUnit.SECONDS);
    */
    final String stateTopic = topicPrefix + "/state";
    mqttClient.send(stateTopic, MqttMessageUtil.ONLINE_MESSAGE);
    return () -> {
      // scheduledFuture.cancel(false);
      mqttClient.send(stateTopic, MqttMessageUtil.OFFLINE_MESSAGE);
      brickMaster.removeStackCurrentListener(stackCurrentListener);
      brickMaster.removeStackVoltageListener(stackVoltageListener);
      brickMaster.removeUSBVoltageListener(usbVoltageListener);
      mqttClient.unpublishTopic(currentTopic);
      mqttClient.unpublishTopic(voltageTopic);
      mqttClient.unpublishTopic(usbVoltageTopic);
    };
  }

  public MqttMessage createValueMessage(final int value) {
    return MqttMessageUtil.createMessage(Double.toString(value / 1000.0), true);
  }
}
