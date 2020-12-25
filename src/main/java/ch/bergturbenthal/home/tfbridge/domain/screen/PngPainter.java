package ch.bergturbenthal.home.tfbridge.domain.screen;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class PngPainter implements Painter {
  private final BufferedImage bufferedImage =
          new BufferedImage(Renderable.WIDTH, Renderable.HEIGHT, BufferedImage.TYPE_BYTE_BINARY);

  @Override
  public int getWidth() {
    return 64;
  }

  @Override
  public int getHeight() {
    return 128;
  }

  @Override
  public void setPixel(final int x, final int y, final boolean enable) {
    if (x >= getWidth()) throw new IllegalArgumentException("x: " + x + " >= " + getWidth());
    if (y >= getHeight()) throw new IllegalArgumentException("y: " + y + " >= " + getHeight());
    bufferedImage.setRGB(x, y, enable ? 0xffffff : 0);
  }

  @Override
  public void fill(final int sx, final int sy, final int ex, final int ey, final boolean inverted) {
    for (int x = sx; x < ex; x++) {
      for (int y = sy; y < ey; y++) {
        setPixel(x, y, !inverted);
      }
    }
  }

  public void store(File targetFile) {
    try {
      ImageIO.write(bufferedImage, "png", targetFile);
    } catch (IOException e) {
      throw new RuntimeException("Cannot write " + targetFile, e);
    }
  }
}
