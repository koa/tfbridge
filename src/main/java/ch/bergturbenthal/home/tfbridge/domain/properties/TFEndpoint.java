package ch.bergturbenthal.home.tfbridge.domain.properties;

import lombok.Data;

import java.net.InetAddress;

@Data
public class TFEndpoint {
  private InetAddress host;
  private int         port = 4223;
}
