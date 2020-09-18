package ch.bergturbenthal.home.tfbridge.domain.ha;

import lombok.Value;
import lombok.Builder;

import java.util.List;

@Value
@Builder
public class LightConfig {
  String platform;
  Device device;
  List<Availability> availability;
  int brightness_scale;
  String brightness_command_topic;
  String brightness_state_topic;
  String command_topic;
  List<String> effect_list;
  String schema;
  String name;
  String state_topic;
  String color_temp_command_topic;
  String color_temp_state_topic;
  int max_mireds;
  int min_mireds;
  String unique_id;
  boolean retain;
}
