package ch.bergturbenthal.home.tfbridge.domain.service;

import ch.bergturbenthal.home.tfbridge.domain.ha.PublishingConfig;

public interface ConfigService {
  <P extends PublishingConfig> void publishConfig(final P config);

  void unpublishConfig(String id);

  default void unpublishConfig(PublishingConfig config) {
    unpublishConfig(config.id());
  }
}
