package ch.bergturbenthal.home.tfbridge.domain.screen;

public interface Display {
  void setBackgroundEnabled(boolean enabled);

  void setPixel(int x, int y, boolean white);

  void drawBuffer();

  int getWidth();

  int getHeight();

  void fillRect(int sx, int sy, int ex, int ey, boolean inverted);
}
