package ch.bergturbenthal.home.tfbridge.domain.ha;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class LightConfig implements PublishingConfig, HasDevice {
  String             platform;
  Device             device;
  List<Availability> availability;
  int                brightness_scale;
  String             brightness_command_topic;
  String             brightness_state_topic;
  String             command_topic;
  List<String>       effect_list;
  String             schema;
  String             name;
  String             state_topic;
  String             color_temp_command_topic;
  String             color_temp_state_topic;
  int                max_mireds;
  int                min_mireds;
  String             unique_id;
  boolean            retain;

  @Override
  public String id() {
    return device.getIdentifiers().stream().findAny().orElse(unique_id);
  }

  @Override
  public String componentType() {
    return "light";
  }
}
