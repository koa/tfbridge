package ch.bergturbenthal.home.tfbridge.domain.screen;

import org.springframework.core.io.ClassPathResource;

public interface Icons {
  Icon COLOR_ICON  = new PngIcon(new ClassPathResource("icons/color.png"));
  Icon BRIGHT_ICON = new PngIcon(new ClassPathResource("icons/brightness.png"));
}
