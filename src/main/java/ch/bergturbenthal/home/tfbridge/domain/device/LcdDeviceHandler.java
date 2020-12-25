package ch.bergturbenthal.home.tfbridge.domain.device;

import ch.bergturbenthal.home.tfbridge.domain.client.MqttClient;
import ch.bergturbenthal.home.tfbridge.domain.ha.ClimateConfig;
import ch.bergturbenthal.home.tfbridge.domain.ha.SensorConfig;
import ch.bergturbenthal.home.tfbridge.domain.properties.BridgeProperties;
import ch.bergturbenthal.home.tfbridge.domain.properties.TouchDisplay;
import ch.bergturbenthal.home.tfbridge.domain.screen.*;
import ch.bergturbenthal.home.tfbridge.domain.service.ConfigService;
import ch.bergturbenthal.home.tfbridge.domain.util.DisposableConsumer;
import ch.bergturbenthal.home.tfbridge.domain.util.MqttMessageUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinkerforge.BrickletLCD128x64;
import com.tinkerforge.IPConnection;
import com.tinkerforge.TinkerforgeException;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import reactor.core.Disposable;

import java.text.DecimalFormat;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@Slf4j
public class LcdDeviceHandler implements DeviceHandler {

  private final BridgeProperties         properties;
  private final MqttClient               mqttClient;
  private final ObjectMapper             objectMapper;
  private final ScheduledExecutorService scheduledExecutorService;
  private final ConfigService            configService;

  public LcdDeviceHandler(
          final BridgeProperties properties,
          final MqttClient mqttClient,
          final ObjectMapper objectMapper,
          final ScheduledExecutorService scheduledExecutorService,
          final ConfigService configService) {
    this.properties = properties;
    this.mqttClient = mqttClient;
    this.objectMapper = objectMapper;
    this.scheduledExecutorService = scheduledExecutorService;
    this.configService = configService;
  }

  @Override
  public int deviceId() {
    return BrickletLCD128x64.DEVICE_IDENTIFIER;
  }

  @Override
  public Disposable registerDevice(
          String uid, IPConnection connection, final Consumer<Throwable> errorConsumer)
          throws TinkerforgeException {
    final BrickletLCD128x64 bricklet = new BrickletLCD128x64(uid, connection);
    String topicPrefix = "BrickletLCD128x64/" + uid;
    MqttMessageUtil.publishVersions(mqttClient, topicPrefix, bricklet.getIdentity());
    final AtomicInteger contrast = new AtomicInteger(14);
    final AtomicInteger backlight = new AtomicInteger(100);
    final AtomicBoolean enableBacklight = new AtomicBoolean(false);
    final AtomicReference<TouchConsumer> currentTouchConsumer =
            new AtomicReference<>((x, y, pressure, age) -> {
            });

    Runnable updateDisplayConfiguration =
            () -> {
              try {
                final int contrast1 = contrast.get();
                final int backlight1 = enableBacklight.get() ? backlight.get() : 0;
                // log.info("Set config " + contrast1 + ", " + backlight1);
                bricklet.setDisplayConfiguration(contrast1, backlight1, false, true);
              } catch (TinkerforgeException e) {
                log.warn("Cannot update display settings", e);
                errorConsumer.accept(e);
              }
            };
    final List<TouchDisplay> touchDisplays = properties.getTouchDisplays();
    final RenderableText currentTempBox = new RenderableText(BoxStyle.DASHED, 1.5f);
    DecimalFormat temperatureFormat = new DecimalFormat("00.0°");

    DisposableConsumer sensorRegistration = new DisposableConsumer();
    AtomicReference<Runnable> refreshHeaters = new AtomicReference<>(() -> {
    });

    final List<HeaterEntry> heaterEntryList =
            Optional.ofNullable(touchDisplays)
                    .flatMap(
                            displays ->
                                    displays.stream().filter(disp -> disp.getBricklet().equals(uid)).findAny())
                    .map(
                            display -> {
                              final String temperatureId = display.getTemperatureId();
                              AtomicReference<SensorConfig> attachedTempSensorConfig = new AtomicReference<>();
                              configService.registerForConfiguration(
                                      SensorConfig.class,
                                      temperatureId,
                                      new ConfigService.ConfigurationListener<>() {
                                        @Override
                                        public void notifyConfigAdded(final SensorConfig configuration) {
                                          attachedTempSensorConfig.set(configuration);
                                          mqttClient.registerTopic(
                                                  configuration.getState_topic(),
                                                  message -> {
                                                    try {
                                                      final double currentValue =
                                                              Double.parseDouble(
                                                                      new String(message.getMessage().getPayload()));
                                                      currentTempBox.setText(temperatureFormat.format(currentValue));
                                                    } catch (RuntimeException ex) {
                                                      log.warn("Cannot decode current temperature", ex);
                                                    }
                                                  },
                                                  sensorRegistration);
                                          refreshHeaters.get().run();
                                        }

                                        @Override
                                        public void notifyConfigRemoved(final SensorConfig configuration) {
                                          final SensorConfig currentConfig = attachedTempSensorConfig.get();
                                          if (ObjectUtils.nullSafeEquals(currentConfig, configuration))
                                            sensorRegistration.accept(null);
                                          refreshHeaters.get().run();
                                        }
                                      });
                              return Optional.ofNullable(display.getHeaters()).stream()
                                             .flatMap(Collection::stream)
                                             .map(
                                                     heaterConfig -> {
                                                       HeaterEntry heaterEntry = new HeaterEntry();
                                                       AtomicReference<ClimateConfig> currentConfig = new AtomicReference<>();
                                                       final Consumer<Disposable> disposableConsumer =
                                                               new DisposableConsumer();
                                                       configService.registerForConfiguration(
                                                               ClimateConfig.class,
                                                               heaterConfig,
                                                               new ConfigService.ConfigurationListener<>() {
                                                                 @Override
                                                                 public void notifyConfigAdded(final ClimateConfig configuration) {
                                                                   currentConfig.set(configuration);
                                                                   final String topic =
                                                                           configuration.getTemperature_command_topic();
                                                                   heaterEntry.getValueTopic()
                                                                              .set(Optional.ofNullable(topic));
                                                                   mqttClient.registerTopic(
                                                                           topic,
                                                                           receivedMqttMessage -> {
                                                                             try {
                                                                               final double currentValue =
                                                                                       Double.parseDouble(
                                                                                               new String(
                                                                                                       receivedMqttMessage
                                                                                                               .getMessage()
                                                                                                               .getPayload()));
                                                                               heaterEntry
                                                                                       .getCurrentValue()
                                                                                       .set(Optional.of(currentValue));
                                                                               refreshHeaters.get().run();
                                                                             } catch (RuntimeException ex) {
                                                                               log.error("Cannot parse message", ex);
                                                                               heaterEntry.getCurrentValue()
                                                                                          .set(Optional.empty());
                                                                             }
                                                                           },
                                                                           disposableConsumer);
                                                                 }

                                                                 @Override
                                                                 public void notifyConfigRemoved(
                                                                         final ClimateConfig configuration) {
                                                                   if (Objects.equals(currentConfig.get(),
                                                                                      configuration)) {
                                                                     heaterEntry.getValueTopic().set(Optional.empty());
                                                                     heaterEntry.getCurrentValue()
                                                                                .set(Optional.empty());
                                                                     disposableConsumer.accept(null);
                                                                   }
                                                                 }
                                                               });
                                                       return heaterEntry;
                                                     })
                                             .collect(Collectors.toList());
                            })
                    .orElse(Collections.emptyList());

    Display brickletDisplay =
            new Display() {
              final boolean[] pixels = new boolean[128 * 64];

              @Override
              public void setBackgroundEnabled(final boolean enabled) {
                enableBacklight.set(enabled);
                updateDisplayConfiguration.run();
              }

              @Override
              public void setPixel(final int x, final int y, final boolean white) {
                pixels[127 - y + x * 128] = !white;
              }

              @Override
              public void drawBuffer() {
                try {
                  bricklet.writePixels(0, 0, 127, 63, pixels);
                } catch (TinkerforgeException e) {
                  log.error("Cannot update image on " + uid, e);
                }
              }

              @Override
              public int getWidth() {
                return 64;
              }

              @Override
              public int getHeight() {
                return 128;
              }

              @Override
              public void fillRect(
                      final int sx, final int sy, final int ex, final int ey, final boolean inverted) {
                for (int row = sx; row < ex; row++) {
                  Arrays.fill(pixels, row * 128 + 128 - ey, row * 128 + 128 - sy, !inverted);
                }
              }
            };

    final AtomicReference<ScheduledFuture<?>> backlightOffTimer = new AtomicReference<>();
    final BrickletLCD128x64.TouchPositionListener touchPositionListener =
            (pressure, x, y, age) -> {
              final ScheduledFuture<?> runningTimer = backlightOffTimer.get();
              if (runningTimer == null || runningTimer.isDone()) {
                brickletDisplay.setBackgroundEnabled(true);
                backlightOffTimer.set(
                        scheduledExecutorService.schedule(
                                () -> {
                                  brickletDisplay.setBackgroundEnabled(false);
                                },
                                2,
                                TimeUnit.MINUTES));
              } else {
                currentTouchConsumer.get().notifyTouch(y, 127 - x, pressure, age);
              }

              try {
                final TouchPositionData touchPositionData = new TouchPositionData(pressure, x, y, age);
                final MqttMessage message = new MqttMessage();
                message.setRetained(false);
                message.setPayload(objectMapper.writeValueAsBytes(touchPositionData));
                message.setQos(1);
                mqttClient
                        .publish(topicPrefix + "/touchPosition", message)
                        .subscribe(msg -> {
                        }, ex -> log.warn("Cannot send touch message", ex));
              } catch (JsonProcessingException e) {
                log.warn("Cannot create mqtt message", e);
              }
            };
    bricklet.addTouchPositionListener(touchPositionListener);
    final Consumer<Disposable> backlightConsumer = new DisposableConsumer();
    mqttClient.registerTopic(
        topicPrefix + "/backlight",
        mqttMessage -> {
          int value = Integer.parseInt(new String(mqttMessage.getMessage().getPayload()));
          log.info("Set backlight to " + value);
          backlight.set(value);
          updateDisplayConfiguration.run();
        },
        backlightConsumer);
    /*
    final Consumer<Disposable> backlightEnableConsumer = new DisposableConsumer();
    mqttClient.registerTopic(
        topicPrefix + "/enableBacklight",
        mqttMessage -> {
          boolean value = Boolean.parseBoolean(new String(mqttMessage.getMessage().getPayload()));
          log.info("Set backlight to " + value);
          enableBacklight.set(value);
          updateDisplayConfiguration.run();
        },
        backlightEnableConsumer);

     */
    final Consumer<Disposable> contrastConsumer = new DisposableConsumer();
    // log.info("Set backlight to " + value);
    mqttClient.registerTopic(
            topicPrefix + "/contrast",
            mqttMessage -> {
              int value = Integer.parseInt(new String(mqttMessage.getMessage().getPayload()));
              // log.info("Set backlight to " + value);
              contrast.set(value);
              updateDisplayConfiguration.run();
            },
            contrastConsumer);

    /*
    final Consumer<Disposable> imageUpdateConsumer = new DisposableConsumer();
    mqttClient.registerTopic(
        topicPrefix + "/image",
        mqttMessage -> {
          try {
            final BufferedImage bufferedImage =
                ImageIO.read(
                    new ByteArrayInputStream(
                        Base64.getDecoder().decode(mqttMessage.getMessage().getPayload())));
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

          } catch (Exception ex) {
            log.warn("Cannot process image", ex);
          }
        },
        imageUpdateConsumer);

     */
    bricklet.setTouchLEDConfig(BrickletLCD128x64.TOUCH_LED_CONFIG_SHOW_TOUCH);
    bricklet.setStatusLEDConfig(BrickletLCD128x64.STATUS_LED_CONFIG_OFF);
    bricklet.setTouchPositionCallbackConfiguration(200, true);
    // bricklet.setTouchGestureCallbackConfiguration(100, true);
    bricklet.clearDisplay();
    // bricklet.setDisplayConfiguration(14, 40, false, true);
    final String stateTopic = topicPrefix + "/state";
    mqttClient.send(stateTopic, MqttMessageUtil.ONLINE_MESSAGE);
    updateDisplayConfiguration.run();

    final PaintCanvas paintCanvas = new PaintCanvas(new DisplayPainter(brickletDisplay));
    final List<Renderable> renderables = new ArrayList<>();
    final RenderableText clock = new RenderableText(BoxStyle.EMPTY, 0.5f);
    scheduledExecutorService.scheduleAtFixedRate(
            () -> {
              final TemporalAccessor time = Instant.now().atZone(properties.getZoneId());
              final String text =
                      DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                                       .withLocale(properties.getLocale())
                                       .format(time);
              clock.setText(text);
            },
            0,
            10,
            TimeUnit.SECONDS);

    renderables.add(clock);
    renderables.add(currentTempBox);
    if (!heaterEntryList.isEmpty()) {
      RenderableNumber targetTemperature = new RenderableNumber(99.9, null, 15, 25);
      targetTemperature.setValueChangeListener(
              newValue ->
                      heaterEntryList.stream()
                                     .map(HeaterEntry::getValueTopic)
                                     .map(AtomicReference::get)
                                     .filter(Optional::isPresent)
                                     .map(Optional::get)
                                     .forEach(
                                             topic ->
                                                     mqttClient.send(
                                                             topic,
                                                             MqttMessageUtil.createMessage(Double.toString(newValue),
                                                                                           true))));
      refreshHeaters.set(
              () ->
                      heaterEntryList.stream()
                                     .map(HeaterEntry::getCurrentValue)
                                     .map(AtomicReference::get)
                                     .filter(Optional::isPresent)
                                     .mapToDouble(Optional::get)
                                     .average()
                                     .ifPresent(targetTemperature::setValue));

      renderables.add(targetTemperature);
    }

    renderables.stream()
               .filter(r -> r instanceof ChangeNotifier)
               .map(r -> (ChangeNotifier) r)
               .forEach(
                       notifier -> {
                         notifier.addChangeListener(
                                 () -> {
                                   currentTouchConsumer.set(paintCanvas.drawScreen(renderables));
                                   brickletDisplay.drawBuffer();
                                 });
                       });

    currentTouchConsumer.set(paintCanvas.drawScreen(renderables));
    brickletDisplay.drawBuffer();
    brickletDisplay.setBackgroundEnabled(false);

    return () -> {
      mqttClient.send(stateTopic, MqttMessageUtil.OFFLINE_MESSAGE);
      // imageUpdateConsumer.accept(null);
      contrastConsumer.accept(null);
      backlightConsumer.accept(null);
      sensorRegistration.accept(null);
      // backlightEnableConsumer.accept(null);
      bricklet.removeTouchPositionListener(touchPositionListener);
    };
  }

  @Value
  private static class TouchPositionData {
    int  pressure;
    int  x;
    int  y;
    long age;
  }

  @Value
  private static class HeaterEntry {
    AtomicReference<Optional<Double>> currentValue = new AtomicReference<>(Optional.empty());
    AtomicReference<Optional<String>> valueTopic   = new AtomicReference<>(Optional.empty());
  }
}
