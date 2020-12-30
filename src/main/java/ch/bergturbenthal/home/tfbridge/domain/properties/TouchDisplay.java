package ch.bergturbenthal.home.tfbridge.domain.properties;

import lombok.Value;
import org.springframework.boot.context.properties.ConstructorBinding;

import java.util.List;

@ConstructorBinding
@Value
public class TouchDisplay {
  String       bricklet;
  String       temperatureId;
  List<String> heaters;
  List<String> lightBrightness;
  List<String> lightWhiteBalance;
}
