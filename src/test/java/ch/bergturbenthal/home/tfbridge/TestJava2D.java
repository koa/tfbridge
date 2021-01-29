package ch.bergturbenthal.home.tfbridge;

import ch.bergturbenthal.home.tfbridge.domain.screen.Canvas;
import ch.bergturbenthal.home.tfbridge.domain.screen.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Slf4j
public class TestJava2D {

  @Test
  public void testRender() throws IOException, FontFormatException {
    // Charset.availableCharsets().keySet().forEach(name -> log.info("Charset: " + name));
    String text = "012345678°9abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZäöüÄÖÜ        ";
    final PngPainter pngPainter = new PngPainter();

    final PaintCanvas canvas = new PaintCanvas(pngPainter);

    final int px = 64 / 2 - 5 * PaintCanvas.CHARACTER_WIDTH / 2;
    final int py = 16 / 2 - PaintCanvas.CHARACTER_HEIGHT / 2;
    log.info("x: " + px + ", y: " + py);
    // canvas.clear(false);
    final RenderableText clock = new RenderableText(BoxStyle.EMPTY, 0.5f);
    final TemporalAccessor time = Instant.now().atZone(ZoneId.of("CET"));
    clock.setText(
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                             .withLocale(Locale.GERMAN)
                             .format(time));

    final RenderableText currentTemperatureRenderer = new RenderableText(BoxStyle.DASHED, 1.5f);
    currentTemperatureRenderer.setText("24.5°");

    final RenderableNumber brightSelection =
            new RenderableNumber(255, 0.5, Icons.BRIGHT_ICON, 1, 2);

    final RenderableNumber colorSelection = new RenderableNumber(20, 0.5, Icons.COLOR_ICON, 13, 35);

    final RenderableNumber renderableNumber = new RenderableNumber(24.0, 0.5, null, 15, 30);

    final List<Renderable> renderables =
            Arrays.asList(
                    clock, currentTemperatureRenderer, brightSelection, colorSelection, renderableNumber);
    canvas.drawScreen(renderables);

    pngPainter.store(new File("target/out.png"));
  }

  public void addPlusMinusButtons(final Canvas line1Window) {
    line1Window.drawBoxedString(
            1, 1, 18, line1Window.getHeight() - 1, "-", false, BoxStyle.ROUNDED);
    line1Window.drawBoxedString(
            line1Window.getWidth() - 18,
            1,
            line1Window.getWidth() - 1,
            line1Window.getHeight() - 1,
            "+",
            false,
            BoxStyle.ROUNDED);
  }
}
