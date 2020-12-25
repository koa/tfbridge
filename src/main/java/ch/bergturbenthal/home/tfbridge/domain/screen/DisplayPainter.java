package ch.bergturbenthal.home.tfbridge.domain.screen;

public class DisplayPainter implements Painter {
  private final Display display;

  public DisplayPainter(final Display display) {
    this.display = display;
  }

  @Override
  public int getWidth() {
    return display.getWidth();
  }

  @Override
  public int getHeight() {
    return display.getHeight();
  }

  @Override
  public void setPixel(final int x, final int y, final boolean enable) {
    display.setPixel(x, y, enable);
  }

  @Override
  public void fill(
          final int sx, final int sy, final int ex, final int ey, final boolean inverted) {
    display.fillRect(
            sx, sy, ex, ey, inverted);
  }
}
