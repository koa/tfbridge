package ch.bergturbenthal.home.tfbridge.domain.screen;

import org.springframework.scheduling.annotation.Scheduled;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class RenderableTime implements Renderable, ChangeNotifier {
  private final List<ChangeListener> changeListeners = new ArrayList<>();

  @Override
  public int getMinHeight() {
    return 0;
  }

  @Scheduled(fixedDelay = 60 * 1000)
  void updateTime() {
    synchronized (changeListeners) {
      changeListeners.forEach(ChangeListener::notifyChange);
    }
  }

  @Override
  public float getExpandRatio() {
    return 0.1f;
  }

  @Override
  public void render(final int height, final Graphics2D canvas) {}

  @Override
  public void addChangeListener(final ChangeListener changeListener) {
    synchronized (changeListeners) {
      changeListeners.add(changeListener);
    }
  }
}
