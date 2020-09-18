package ch.bergturbenthal.home.tfbridge.domain.ha;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class TriggerConfig {
  String automation_type;
  String payload;
  String topic;
  String type;
  String subtype;
  Device device;
  String discovery_id;
  int qos;
  String platform;
}
