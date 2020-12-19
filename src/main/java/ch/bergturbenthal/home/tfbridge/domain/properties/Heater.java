package ch.bergturbenthal.home.tfbridge.domain.properties;

import lombok.Value;
import org.springframework.boot.context.properties.ConstructorBinding;

@ConstructorBinding
@Value
public class Heater {
  String id;
  String sensorId;
  String name;
  String bricklet;
  int    address;
}
