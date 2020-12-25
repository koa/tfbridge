package ch.bergturbenthal.home.tfbridge.domain.screen;

public interface Painter {
  int getWidth();

  int getHeight();

  void setPixel(int x, int y, boolean enable);

  void fill(int sx, int sy, int ex, int ey, boolean inverted);
}
