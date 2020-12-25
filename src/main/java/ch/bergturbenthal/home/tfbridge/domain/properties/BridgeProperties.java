package ch.bergturbenthal.home.tfbridge.domain.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

@Component
@ConfigurationProperties("bridge")
@Data
public class BridgeProperties {
  private TFEndpoint              tfEndpoint      = new TFEndpoint();
  private MqttEndpoint            mqtt            = new MqttEndpoint();
  private String                  discoveryPrefix = "homeassistant";
  private ZoneId                  zoneId          = ZoneId.of("Europe/Zurich");
  private Locale                  locale          = Locale.GERMAN;
  private List<WarmColdDmxLight>  warmColdDmxLights;
  private List<SimpleDmxLight>    simpleDmxLights;
  private List<OnOffButtonInput>  onOffButtons;
  private List<PirSensor>         pirSensors;
  private List<TemperatureSensor> temperatureSensors;
  private List<Heater>            heaters;
  private List<TouchDisplay>      touchDisplays;
}
