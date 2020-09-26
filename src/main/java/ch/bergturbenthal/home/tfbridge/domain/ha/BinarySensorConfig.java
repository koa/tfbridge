package ch.bergturbenthal.home.tfbridge.domain.ha;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class BinarySensorConfig {
  Availability availability;
  String platform;
  Device device;
  String device_class;
  int expire_after;
  boolean force_update;
  String name;
  Integer off_delay;
  String payload_on;
  String payload_off;
  int qos;
  String state_topic;
  String unique_id;
}
