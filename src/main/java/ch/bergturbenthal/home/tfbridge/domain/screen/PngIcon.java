package ch.bergturbenthal.home.tfbridge.domain.screen;

import org.springframework.core.io.Resource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.io.IOException;

public class PngIcon implements Icon {

  private final int       width;
  private final int       height;
  private final boolean[] data;

  public PngIcon(Resource resource) {
    try {
      final BufferedImage image = ImageIO.read(resource.getInputStream());
      width = image.getWidth();
      height = image.getHeight();
      data = new boolean[width * height];
      final ColorModel colorModel = image.getColorModel();
      for (int x = 0; x < image.getWidth(); x++) {
        for (int y = 0; y < image.getHeight(); y++) {
          final int rgb = image.getRGB(x, y);
          final int red = colorModel.getRed(rgb);
          final int green = colorModel.getGreen(rgb);
          final int blue = colorModel.getBlue(rgb);
          data[x + y * width] = red + green + blue > 128 * 3;
        }
      }

    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot load image from " + resource, e);
    }
  }

  @Override
  public int getWidth() {
    return width;
  }

  @Override
  public int getHeight() {
    return height;
  }

  @Override
  public void paint(final int x, final int y, final Painter painter, final boolean inverted) {
    for (int px = 0; px < width; px++) {
      for (int py = 0; py < height; py++) {
        painter.setPixel(px + x, py + y, data[px + py * width] ^ inverted);
      }
    }
  }
}
