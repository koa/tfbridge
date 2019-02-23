package ch.bergturbenthal.home.tfbridge.domain.properties;

import lombok.Data;

import java.util.List;

@Data
public class BrickletSettings {
  private String       name;
  private List<String> dmxChannels;
}
