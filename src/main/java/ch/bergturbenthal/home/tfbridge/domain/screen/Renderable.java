package ch.bergturbenthal.home.tfbridge.domain.screen;

public interface Renderable {
  int WIDTH  = 64;
  int HEIGHT = 128;

  int getMinHeight();

  float getExpandRatio();

  void render(Canvas canvas);
}
