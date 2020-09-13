package ch.bergturbenthal.home.tfbridge.domain.ha;

import lombok.Value;

@Value
public class LightCommand {
  State state;
  int brightness;

  public enum State {
    ON,
    OFF
  }
}
