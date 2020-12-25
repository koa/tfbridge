package ch.bergturbenthal.home.tfbridge.domain.screen;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class RenderableNumber extends AbstractChangeNotifier implements Renderable, TouchConsumer {
  final         double                            step                = 0.5;
  final         AtomicReference<Double>           currentValue        = new AtomicReference<>(99.9);
  private final NumberFormat                      format              = new DecimalFormat("00.0");
  private final Icon                              icon;
  private final AtomicReference<Consumer<Double>> valueChangeListener =
          new AtomicReference<>(v -> {
          });
  private final AtomicInteger                     dividerPos          = new AtomicInteger(0);
  private final double                            minValue;
  private final double                            maxValue;

  public RenderableNumber(double defaultValue, final Icon icon, double minValue, double maxValue) {
    this.minValue = minValue;
    this.maxValue = maxValue;
    currentValue.set(defaultValue);
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
    else canvas.drawCenteredIcon(icon, false, BoxStyle.EMPTY);
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
