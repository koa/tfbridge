package ch.bergturbenthal.home.tfbridge.domain.screen;

import java.util.ArrayList;
import java.util.List;

public class AbstractChangeNotifier implements ChangeNotifier {
  protected final List<ChangeListener> changeListeners = new ArrayList<>();

  @Override
  public void addChangeListener(final ChangeListener changeListener) {
    synchronized (changeListeners) {
      changeListeners.add(changeListener);
    }
  }

  protected void notifyChanges() {
    synchronized (changeListeners) {
      changeListeners.forEach(ChangeListener::notifyChange);
    }
  }
}
