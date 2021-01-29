package ch.bergturbenthal.home.tfbridge.domain.screen;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class RenderableNumber extends AbstractChangeNotifier implements Renderable, TouchConsumer {
  private final AtomicReference<Double>           currentValue        = new AtomicReference<>(99.9);
  private final NumberFormat                      format              = new DecimalFormat("00.0");
  private final Icon                              icon;
  private final AtomicReference<Consumer<Double>> valueChangeListener =
          new AtomicReference<>(v -> {
          });
  private final AtomicInteger                     dividerPos          = new AtomicInteger(0);
  private final double                            minValue;
  private final double                            maxValue;
  private       double                            step;

  public RenderableNumber(
          double defaultValue, final double step, final Icon icon, double minValue, double maxValue) {
    this.step = step;
    this.minValue = minValue;
    this.maxValue = maxValue;
    currentValue.set(Math.min(Math.max(minValue, defaultValue), maxValue));
    this.icon = icon;
  }

  public void setValueChangeListener(Consumer<Double> consumer) {
    valueChangeListener.set(consumer);
    // consumer.accept(currentValue.get());
  }

  public void setValue(double value) {
    currentValue.set(value);
    notifyChanges();
  }

  @Override
  public int getMinHeight() {
    if (icon != null) {
      return Math.max(icon.getHeight() + 3, 15);
    }
    return 15;
  }

  @Override
  public float getExpandRatio() {
    return 1f;
  }

  @Override
  public void render(final Canvas canvas) {
    final int height = canvas.getHeight();
    final int width = canvas.getWidth();
    int yOffset = height / 2 - 15 / 2;
    canvas.drawBoxedString(1, yOffset + 1, 18, yOffset + 14, "-", false, BoxStyle.ROUNDED);
    canvas.drawBoxedString(
            width - 18, yOffset + 1, width - 1, yOffset + 14, "+", false, BoxStyle.ROUNDED);
    final String valueString = format.format(this.currentValue.get());
    if (icon == null) canvas.drawCenteredString(valueString, false, BoxStyle.EMPTY);
    else {
      final int iconHeight = icon.getHeight() + 3;
      int iconStart = (height - iconHeight) / 2;
      canvas.drawBoxedIcon(
              0,
              iconStart,
              canvas.getWidth(),
              iconStart + icon.getHeight() + 1,
              icon,
              false,
              BoxStyle.EMPTY);
      int barLength = width - 18 - 18;
      int stepCount = barLength / 3 - 1;
      this.step = (maxValue - minValue) / stepCount;
      int visibleDotCount = (int) ((currentValue.get() - minValue) / this.step);

      final int barStartHeight = iconStart + icon.getHeight() + 2;
      canvas.setPixel(19, barStartHeight, false);
      canvas.setPixel(19, barStartHeight + 1, false);
      for (int i = 0; i < visibleDotCount; i++) {
        canvas.setPixel(19 + 2 + i * 3, barStartHeight, false);
        canvas.setPixel(19 + 2 + i * 3, barStartHeight + 1, false);
        canvas.setPixel(19 + 2 + i * 3 + 1, barStartHeight, false);
        canvas.setPixel(19 + 2 + i * 3 + 1, barStartHeight + 1, false);
      }
      canvas.setPixel(19 + 2 + stepCount * 3, barStartHeight, false);
      canvas.setPixel(19 + 2 + stepCount * 3, barStartHeight + 1, false);
    }
    dividerPos.set(width / 2);
  }

  @Override
  public void notifyTouch(final int x, final int y, final int pressure, final long age) {
    if (x > dividerPos.get()) {
      incrementValue();
    } else {
      decrementValue();
    }
  }

  private void decrementValue() {
    final Double newValue = currentValue.updateAndGet(v -> Math.max(minValue, v - step));
    valueChangeListener.get().accept(newValue);
    notifyChanges();
  }

  private void incrementValue() {
    final Double newValue = currentValue.updateAndGet(v -> Math.min(maxValue, v + step));
    valueChangeListener.get().accept(newValue);
    notifyChanges();
  }
}
