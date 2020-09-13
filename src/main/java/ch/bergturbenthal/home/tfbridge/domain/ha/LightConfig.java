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
  boolean brightness;
  int brightness_scale;
  boolean color_temp;
  String command_topic;
  boolean effect;
  List<String> effect_list;
  boolean optimistic;
  String schema;
  String name;
  String state_topic;
  boolean white_value;
  int max_mireds;
  int min_mireds;
  String unique_id;
}
