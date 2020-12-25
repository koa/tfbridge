package ch.bergturbenthal.home.tfbridge.domain.screen;

public interface TouchConsumer {
  void notifyTouch(int x, int y, int pressure, long age);
}
