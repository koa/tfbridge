package ch.bergturbenthal.home.tfbridge.domain.properties;

import lombok.Data;

@Data
public class MqttEndpoint {
  private String service = "mqtt";
  private String clientId = "TinkerForgeBridge";
  private String mqttPrefix = "tinkerforge";
}
