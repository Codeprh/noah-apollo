package com.ctrip.framework.apollo.spi;

/**
 * ConfigFactoryManager 管理的是 ConfigFactory ，而 ConfigManager 管理的是 Config
 * @author Jason Song(song_s@ctrip.com)
 */
public interface ConfigFactoryManager {
  /**
   * Get the config factory for the namespace.
   *
   * @param namespace the namespace
   * @return the config factory for this namespace
   */
  public ConfigFactory getFactory(String namespace);
}
