package ch.bergturbenthal.home.tfbridge.domain.ha;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SensorConfig {
  String platform;
  Device device;
  String device_class;
  String availability_topic;
  Integer expire_after;
  boolean force_update;
  String name;
  int qos;
  String state_topic;
  String unique_id;
  String unit_of_measurement;
}
