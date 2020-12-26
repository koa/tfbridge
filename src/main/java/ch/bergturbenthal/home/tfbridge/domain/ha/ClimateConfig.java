package ch.bergturbenthal.home.tfbridge.domain.ha;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class ClimateConfig implements PublishingConfig {
  String             platform;
  List<Availability> availability;
  String             current_temperature_topic;
  Device             device;
  double             max_temp;
  double             min_temp;
  List<String>       modes;
  String             mode_state_topic;
  String             name;
  int                qos;
  boolean            retain;
  String             temperature_command_topic;
  String             temperature_state_topic;
  String             temperature_unit;
  Double             temp_step;
  String             unique_id;

  @Override
  public String id() {
    return unique_id;
  }

  @Override
  public String componentType() {
    return "climate";
  }
}
