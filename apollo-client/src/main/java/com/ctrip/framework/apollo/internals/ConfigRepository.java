package com.ctrip.framework.apollo.internals;

import com.ctrip.framework.apollo.enums.ConfigSourceType;
import java.util.Properties;

/**
 * 那么什么是 ConfigRepository 是什么呢？这里我们可以简单( 但不完全准确 )理解成配置的 Repository ，负责从远程的 ConfigService 读取配置
 * @author Jason Song(song_s@ctrip.com)
 */
public interface ConfigRepository {
  /**
   * Get the config from this repository.
   * @return config
   */
  public Properties getConfig();

  /**
   * Set the fallback repo for this repository.
   * @param upstreamConfigRepository the upstream repo
   */
  public void setUpstreamRepository(ConfigRepository upstreamConfigRepository);

  /**
   * Add change listener.
   * @param listener the listener to observe the changes
   */
  public void addChangeListener(RepositoryChangeListener listener);

  /**
   * Remove change listener.
   * @param listener the listener to remove
   */
  public void removeChangeListener(RepositoryChangeListener listener);

  /**
   * Return the config's source type, i.e. where is the config loaded from
   *
   * @return the config's source type
   */
  public ConfigSourceType getSourceType();
}
