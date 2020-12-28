package ch.bergturbenthal.home.tfbridge.domain.properties;

import lombok.Value;
import org.springframework.boot.context.properties.ConstructorBinding;

@ConstructorBinding
@Value
public class SingleButtonInput {
  String name;
  String id;
  String io16Bricklet;
  int    address;
}
