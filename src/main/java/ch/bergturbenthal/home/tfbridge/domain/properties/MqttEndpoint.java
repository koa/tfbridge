package ch.bergturbenthal.home.tfbridge.domain.properties;

import lombok.Data;

import java.net.InetAddress;

@Data
public class MqttEndpoint {
  private String service;
  private String clientId = "TinkerForgeBridge";
}
