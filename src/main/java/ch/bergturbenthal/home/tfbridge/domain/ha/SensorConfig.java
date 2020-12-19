package ch.bergturbenthal.home.tfbridge.domain.ha;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SensorConfig implements PublishingConfig {
  String  platform;
  Device  device;
  String  device_class;
  String  availability_topic;
  Integer expire_after;
  boolean force_update;
  String  name;
  int     qos;
  String  state_topic;
  String  unique_id;
  String  unit_of_measurement;

  @Override
  public String id() {
    return unique_id;
  }

  @Override
  public String componentType() {
    return "sensor";
  }
}
