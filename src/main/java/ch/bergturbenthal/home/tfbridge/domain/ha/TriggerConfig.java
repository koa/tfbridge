package ch.bergturbenthal.home.tfbridge.domain.ha;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class TriggerConfig implements PublishingConfig {
  String automation_type;
  String payload;
  String topic;
  String type;
  String subtype;
  Device device;
  @JsonIgnore
  String discovery_id;
  int    qos;
  String platform;

  @Override
  public String id() {
    return discovery_id;
  }

  @Override
  public String componentType() {
    return "device_automation";
  }
}
