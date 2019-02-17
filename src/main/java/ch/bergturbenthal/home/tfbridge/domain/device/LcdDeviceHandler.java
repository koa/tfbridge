package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.util.DisposableConsumer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.ooxi.jdatauri.DataUri;
import com.tinkerforge.BrickletLCD128x64;
import com.tinkerforge.IPConnection;
import com.tinkerforge.NotConnectedException;
import com.tinkerforge.TimeoutException;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
@Slf4j
public class LcdDeviceHandler implements DeviceHandler {

  private final MqttClient   mqttClient;
  private final ObjectMapper objectMapper;

  public LcdDeviceHandler(final MqttClient mqttClient, final ObjectMapper objectMapper) {
    this.mqttClient = mqttClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public int deviceId() {
    return BrickletLCD128x64.DEVICE_IDENTIFIER;
  }

  @Override
  public Disposable registerDevice(String uid, String name, IPConnection connection) {
    final BrickletLCD128x64 bricklet = new BrickletLCD128x64(uid, connection);
    String topicPrefix = "BrickletLCD128x64/" + name;
    final AtomicInteger contrast = new AtomicInteger(14);
    final AtomicInteger backlight = new AtomicInteger(100);
    bricklet.addTouchPositionListener(
            (pressure, x, y, age) -> {
              try {
                final TouchPositionData touchPositionData = new TouchPositionData(pressure, x, y, age);
                final MqttMessage message = new MqttMessage();
                message.setRetained(false);
                message.setPayload(objectMapper.writeValueAsBytes(touchPositionData));
                mqttClient
                        .publish(topicPrefix + "/touchPosition", message)
                        .subscribe(msg -> {
                        }, ex -> log.warn("Cannot send touch message", ex));
              } catch (JsonProcessingException e) {
                log.warn("Cannot create mqtt message", e);
              }
            });
    Runnable updateDisplayConfiguration =
            () -> {
              try {
                final int contrast1 = contrast.get();
                final int backlight1 = backlight.get();
                log.info("Set config " + contrast1 + ", " + backlight1);
                bricklet.setDisplayConfiguration(contrast1, backlight1, false, true);
              } catch (TimeoutException | NotConnectedException e) {
                log.warn("Cannot update display settings", e);
              }
            };
    final Consumer<Disposable> backlightConsumer = new DisposableConsumer();
    mqttClient.registerTopic(
            topicPrefix + "/backlight",
            mqttMessage -> {
              int value = Integer.valueOf(new String(mqttMessage.getPayload()));
              log.info("Set backlight to " + value);
              backlight.set(value);
              updateDisplayConfiguration.run();
            },
            backlightConsumer);
    final Consumer<Disposable> contrastConsumer = new DisposableConsumer();
    // log.info("Set backlight to " + value);
    mqttClient.registerTopic(
            topicPrefix + "/contrast",
            mqttMessage -> {
              int value = Integer.valueOf(new String(mqttMessage.getPayload()));
              // log.info("Set backlight to " + value);
              contrast.set(value);
              updateDisplayConfiguration.run();
            },
            contrastConsumer);
    final Consumer<Disposable> imageUpdateConsumer = new DisposableConsumer();
    mqttClient.registerTopic(
            topicPrefix + "/image",
            mqttMessage -> {
              final DataUri dataUri =
                      DataUri.parse(new String(mqttMessage.getPayload()), StandardCharsets.UTF_8);
              try {
                final BufferedImage bufferedImage =
                        ImageIO.read(new ByteArrayInputStream(dataUri.getData()));
                if (bufferedImage.getWidth() < 128) {
                  log.warn("Image to narrow found: " + bufferedImage.getWidth() + " expected: 128)");
                  return;
                }
                if (bufferedImage.getHeight() < 64) {
                  log.warn("Image to short found: " + bufferedImage.getHeight() + " expected: 64)");
                  return;
                }
                boolean[] pixels = new boolean[128 * 64];
                for (int x = 0; x < 128; x++) {
                  for (int y = 0; y < 64; y++) {
                    final int rgb = bufferedImage.getRGB(x, y);
                    int gray = (rgb >> 16 & 255) + (rgb >> 8 & 255) + (rgb & 255);
                    pixels[x + y * 128] = gray < 127 * 4;
                  }
                }
                bricklet.writePixels(0, 0, 127, 63, pixels);

              } catch (IOException | TimeoutException | NotConnectedException ex) {
                log.warn("Cannot process image", ex);
              }
            },
            imageUpdateConsumer);
    ;

    try {

      bricklet.setTouchLEDConfig(BrickletLCD128x64.TOUCH_LED_CONFIG_SHOW_TOUCH);
      bricklet.setStatusLEDConfig(BrickletLCD128x64.STATUS_LED_CONFIG_OFF);
      bricklet.setTouchPositionCallbackConfiguration(200, false);
      // bricklet.setTouchGestureCallbackConfiguration(100, true);
      bricklet.clearDisplay();
      // bricklet.setDisplayConfiguration(14, 40, false, true);
    } catch (TimeoutException | NotConnectedException e) {
      log.warn("Cannot send to bricklet", e);
    }
    return () -> {
      imageUpdateConsumer.accept(null);
      contrastConsumer.accept(null);
      backlightConsumer.accept(null);
    };
  }

  @Value
  private static class TouchPositionData {
    final int  pressure;
    final int  x;
    final int  y;
    final long age;
  }
}
