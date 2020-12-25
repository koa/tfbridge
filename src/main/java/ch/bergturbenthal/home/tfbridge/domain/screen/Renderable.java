package ch.bergturbenthal.home.tfbridge.domain.screen;

import java.awt.Graphics2D;

public interface Renderable {
  int WIDTH  = 64;
  int HEIGHT = 128;

  int getMinHeight();

  float getExpandRatio();

  void render(int height, Graphics2D canvas);
}
