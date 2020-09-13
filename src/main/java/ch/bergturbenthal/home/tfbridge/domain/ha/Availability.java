package ch.bergturbenthal.home.tfbridge.domain.ha;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Availability {
  String topic;
  String payload_available;
  String payload_not_available;
}
