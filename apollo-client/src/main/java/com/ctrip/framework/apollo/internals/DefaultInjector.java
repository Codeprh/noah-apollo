package com.ctrip.framework.apollo.internals;

import com.ctrip.framework.apollo.exceptions.ApolloConfigException;
import com.ctrip.framework.apollo.spi.*;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.ctrip.framework.apollo.util.ConfigUtil;
import com.ctrip.framework.apollo.util.http.HttpUtil;
import com.ctrip.framework.apollo.util.yaml.YamlParser;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Singleton;

/**
 * 考虑到 Apollo 会被引入项目中，尽量减少对 Spring 的依赖。但是呢，自身又有 DI 特性的需要，那么引入 Google Guice 是非常好的选择
 * Guice injector
 * @author Jason Song(song_s@ctrip.com)
 */
public class DefaultInjector implements Injector {

  private com.google.inject.Injector m_injector;

  public DefaultInjector() {
    try {
      //创建bean，没依赖注入
      m_injector = Guice.createInjector(new ApolloModule());
    } catch (Throwable ex) {
      ApolloConfigException exception = new ApolloConfigException("Unable to initialize Guice Injector!", ex);
      Tracer.logError(exception);
      throw exception;
    }
  }

  /**
   * 等价获取bean对象
   *
   * @param clazz
   * @param <T>
   * @return
   */
  @Override
  public <T> T getInstance(Class<T> clazz) {
    try {
      return m_injector.getInstance(clazz);
    } catch (Throwable ex) {
      Tracer.logError(ex);
      throw new ApolloConfigException(
          String.format("Unable to load instance for %s!", clazz.getName()), ex);
    }
  }

  @Override
  public <T> T getInstance(Class<T> clazz, String name) {
    //Guice does not support get instance by type and name
    return null;
  }

  /**
   * bean创建的地方，google管理对象创建的生命周期
   */
  private static class ApolloModule extends AbstractModule {

    @Override
    protected void configure() {

      bind(ConfigManager.class).to(DefaultConfigManager.class).in(Singleton.class);
      bind(ConfigFactoryManager.class).to(DefaultConfigFactoryManager.class).in(Singleton.class);

      bind(ConfigRegistry.class).to(DefaultConfigRegistry.class).in(Singleton.class);
      bind(ConfigFactory.class).to(DefaultConfigFactory.class).in(Singleton.class);

      bind(ConfigUtil.class).in(Singleton.class);
      bind(HttpUtil.class).in(Singleton.class);

      bind(ConfigServiceLocator.class).in(Singleton.class);
      bind(RemoteConfigLongPollService.class).in(Singleton.class);

      bind(YamlParser.class).in(Singleton.class);
    }
  }
}
