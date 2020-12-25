package ch.bergturbenthal.home.tfbridge.domain.screen;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class RenderableText extends AbstractChangeNotifier implements Renderable {
  private final AtomicReference<String> currentText = new AtomicReference<>("");
  private final BoxStyle                style;
  private final float                   expandRatio;

  public RenderableText(final BoxStyle style, float expandRatio) {
    this.style = style;
    this.expandRatio = expandRatio;
  }

  public void setText(String text) {
    final String textBefore = currentText.getAndSet(text);
    if (!Objects.equals(textBefore, text)) {
      notifyChanges();
    }
  }

  @Override
  public int getMinHeight() {
    if (style == BoxStyle.EMPTY) return PaintCanvas.CHARACTER_HEIGHT;
    else return PaintCanvas.CHARACTER_HEIGHT + 2;
  }

  @Override
  public float getExpandRatio() {
    return expandRatio;
  }

  @Override
  public void render(final Canvas canvas) {
    canvas.drawCenteredString(currentText.get(), false, style);
  }
}
