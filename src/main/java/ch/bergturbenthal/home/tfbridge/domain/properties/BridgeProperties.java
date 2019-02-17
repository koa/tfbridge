package ch.bergturbenthal.home.tfbridge.domain.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties("bridge")
@Data
public class BridgeProperties {
  private List<TFEndpoint>    tfEndpoint;
  private MqttEndpoint        mqtt;
  private Map<String, String> bricklets;
}
