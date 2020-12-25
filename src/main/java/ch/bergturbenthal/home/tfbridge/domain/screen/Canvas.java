package ch.bergturbenthal.home.tfbridge.domain.screen;

public interface Canvas {
  void drawBoxedString(
          int sx, int sy, int ex, int ey, String str, boolean color, BoxStyle boxStyle);

  void drawBoxedIcon(int sx, int sy, int ex, int ey, Icon icon, boolean color, BoxStyle boxStyle);

  Canvas createWindow(int sx, int sy, int ex, int ey);

  int getWidth();

  int getHeight();

  default void drawCenteredString(String str, boolean color, BoxStyle boxStyle) {
    drawBoxedString(0, 0, getWidth(), getHeight(), str, color, boxStyle);
  }

  default void drawCenteredIcon(Icon icon, boolean color, BoxStyle boxStyle) {
    drawBoxedIcon(0, 0, getWidth(), getHeight(), icon, color, boxStyle);
  }
}
