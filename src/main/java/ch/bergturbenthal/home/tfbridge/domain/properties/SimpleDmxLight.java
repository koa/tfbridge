package ch.bergturbenthal.home.tfbridge.domain.properties;

import lombok.Value;
import org.springframework.boot.context.properties.ConstructorBinding;

@ConstructorBinding
@Value
public class SimpleDmxLight {
  String id;
  String name;
  String dmxBricklet;
  int    address;
}
