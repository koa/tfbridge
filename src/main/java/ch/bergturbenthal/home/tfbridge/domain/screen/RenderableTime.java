package ch.bergturbenthal.home.tfbridge.domain.screen;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RenderableTime extends AbstractChangeNotifier implements Renderable {
  private final ZoneId zoneId = ZoneId.of("Europe/Zurich");
  private final Locale locale = Locale.GERMAN;

  @Override
  public int getMinHeight() {
    return PaintCanvas.CHARACTER_HEIGHT + 2;
  }

  public void updateTime() {
    synchronized (changeListeners) {
      changeListeners.forEach(ChangeListener::notifyChange);
    }
  }

  @Override
  public float getExpandRatio() {
    return 0.4f;
  }

  @Override
  public void render(final Canvas canvas) {

    final TemporalAccessor time = Instant.now().atZone(zoneId);
    final String string =
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time);
    canvas.drawCenteredString(string, false, BoxStyle.EMPTY);
  }

}
