package ch.bergturbenthal.home.tfbridge.domain.properties;

import lombok.Value;
import org.springframework.boot.context.properties.ConstructorBinding;

@ConstructorBinding
@Value
public class TemperatureSensor {
  String name;
  String id;
  String bricklet;
}
