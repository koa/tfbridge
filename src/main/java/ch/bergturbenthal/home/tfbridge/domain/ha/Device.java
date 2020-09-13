package ch.bergturbenthal.home.tfbridge.domain.ha;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class Device {
  List<String> identifiers;
}
