package ch.bergturbenthal.home.tfbridge.domain.screen;

public interface Icon {
  int getWidth();

  int getHeight();

  public void paint(int x, int y, Painter painter, boolean inverted);
}
