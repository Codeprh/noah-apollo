package com.ctrip.framework.apollo.internals;

import com.ctrip.framework.apollo.build.ApolloInjector;
import com.ctrip.framework.apollo.core.ConfigConsts;
import com.ctrip.framework.apollo.core.utils.ClassLoaderUtil;
import com.ctrip.framework.apollo.enums.ConfigSourceType;
import com.ctrip.framework.apollo.exceptions.ApolloConfigException;
import com.ctrip.framework.apollo.tracer.Tracer;
import com.ctrip.framework.apollo.tracer.spi.Transaction;
import com.ctrip.framework.apollo.util.ConfigUtil;
import com.ctrip.framework.apollo.util.ExceptionUtil;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 本地文件配置 Repository 实现类
 *
 * @author Jason Song(song_s@ctrip.com)
 */
public class LocalFileConfigRepository extends AbstractConfigRepository
        implements RepositoryChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(LocalFileConfigRepository.class);

    /**
     * 配置文件目录
     */
    private static final String CONFIG_DIR = "/config-cache";
    /**
     * namespaces名称
     */
    private final String m_namespace;
    /**
     * 本地缓存配置文件目录
     */
    private File m_baseDir;
    private final ConfigUtil m_configUtil;
    /**
     * 上游的 ConfigRepository 对象。一般情况下，使用 RemoteConfigRepository 对象，读取远程 Config Service 的配置
     */
    private volatile ConfigRepository m_upstream;
    private volatile Properties m_fileProperties;

    private volatile ConfigSourceType m_sourceType = ConfigSourceType.LOCAL;

    /**
     * Constructor.
     *
     * @param namespace the namespace
     */
    public LocalFileConfigRepository(String namespace) {
        this(namespace, null);
    }

    public LocalFileConfigRepository(String namespace, ConfigRepository upstream) {
        m_namespace = namespace;
        m_configUtil = ApolloInjector.getInstance(ConfigUtil.class);
        this.setLocalCacheDir(findLocalCacheDir(), false);
        this.setUpstreamRepository(upstream);
        this.trySync();
    }

    void setLocalCacheDir(File baseDir, boolean syncImmediately) {
        m_baseDir = baseDir;
        this.checkLocalConfigCacheDir(m_baseDir);
        if (syncImmediately) {
            this.trySync();
        }
    }

    /**
     * 本地缓存目录
     * @return
     */
    private File findLocalCacheDir() {
        try {
            String defaultCacheDir = m_configUtil.getDefaultLocalCacheDir();
            Path path = Paths.get(defaultCacheDir);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
            if (Files.exists(path) && Files.isWritable(path)) {
                return new File(defaultCacheDir, CONFIG_DIR);
            }
        } catch (Throwable ex) {
            //ignore
        }

        return new File(ClassLoaderUtil.getClassPath(), CONFIG_DIR);
    }

    @Override
    public Properties getConfig() {
        if (m_fileProperties == null) {
            sync();
        }
        Properties result = new Properties();
        result.putAll(m_fileProperties);
        return result;
    }

    @Override
    public void setUpstreamRepository(ConfigRepository upstreamConfigRepository) {
        if (upstreamConfigRepository == null) {
            return;
        }
        //clear previous listener
        if (m_upstream != null) {
            m_upstream.removeChangeListener(this);
        }
        m_upstream = upstreamConfigRepository;
        trySyncFromUpstream();
        upstreamConfigRepository.addChangeListener(this);
    }

    @Override
    public ConfigSourceType getSourceType() {
        return m_sourceType;
    }

    @Override
    public void onRepositoryChange(String namespace, Properties newProperties) {
        if (newProperties.equals(m_fileProperties)) {
            return;
        }
        Properties newFileProperties = new Properties();
        newFileProperties.putAll(newProperties);
        updateFileProperties(newFileProperties, m_upstream.getSourceType());
        this.fireRepositoryChange(namespace, newProperties);
    }

    @Override
    protected void sync() {
        //sync with upstream immediately
        boolean syncFromUpstreamResultSuccess = trySyncFromUpstream();

        if (syncFromUpstreamResultSuccess) {
            return;
        }

        Transaction transaction = Tracer.newTransaction("Apollo.ConfigService", "syncLocalConfig");
        Throwable exception = null;
        try {
            transaction.addData("Basedir", m_baseDir.getAbsolutePath());
            m_fileProperties = this.loadFromLocalCacheFile(m_baseDir, m_namespace);
            m_sourceType = ConfigSourceType.LOCAL;
            transaction.setStatus(Transaction.SUCCESS);
        } catch (Throwable ex) {
            Tracer.logEvent("ApolloConfigException", ExceptionUtil.getDetailMessage(ex));
            transaction.setStatus(ex);
            exception = ex;
            //ignore
        } finally {
            transaction.complete();
        }

        if (m_fileProperties == null) {
            m_sourceType = ConfigSourceType.NONE;
            throw new ApolloConfigException(
                    "Load config from local config failed!", exception);
        }
    }

    private boolean trySyncFromUpstream() {
        if (m_upstream == null) {
            return false;
        }
        try {
            updateFileProperties(m_upstream.getConfig(), m_upstream.getSourceType());
            return true;
        } catch (Throwable ex) {
            Tracer.logError(ex);
            logger
                    .warn("Sync config from upstream repository {} failed, reason: {}", m_upstream.getClass(),
                            ExceptionUtil.getDetailMessage(ex));
        }
        return false;
    }

    private synchronized void updateFileProperties(Properties newProperties, ConfigSourceType sourceType) {
        this.m_sourceType = sourceType;
        if (newProperties.equals(m_fileProperties)) {
            return;
        }
        this.m_fileProperties = newProperties;
        persistLocalCacheFile(m_baseDir, m_namespace);
    }

    private Properties loadFromLocalCacheFile(File baseDir, String namespace) throws IOException {
        Preconditions.checkNotNull(baseDir, "Basedir cannot be null");

        File file = assembleLocalCacheFile(baseDir, namespace);
        Properties properties = null;

        if (file.isFile() && file.canRead()) {
            InputStream in = null;

            try {
                in = new FileInputStream(file);

                properties = new Properties();
                properties.load(in);
                logger.debug("Loading local config file {} successfully!", file.getAbsolutePath());
            } catch (IOException ex) {
                Tracer.logError(ex);
                throw new ApolloConfigException(String
                        .format("Loading config from local cache file %s failed", file.getAbsolutePath()), ex);
            } finally {
                try {
                    if (in != null) {
                        in.close();
                    }
                } catch (IOException ex) {
                    // ignore
                }
            }
        } else {
            throw new ApolloConfigException(
                    String.format("Cannot read from local cache file %s", file.getAbsolutePath()));
        }

        return properties;
    }

    void persistLocalCacheFile(File baseDir, String namespace) {
        if (baseDir == null) {
            return;
        }
        File file = assembleLocalCacheFile(baseDir, namespace);

        OutputStream out = null;

        Transaction transaction = Tracer.newTransaction("Apollo.ConfigService", "persistLocalConfigFile");
        transaction.addData("LocalConfigFile", file.getAbsolutePath());
        try {
            out = new FileOutputStream(file);
            m_fileProperties.store(out, "Persisted by DefaultConfig");
            transaction.setStatus(Transaction.SUCCESS);
        } catch (IOException ex) {
            ApolloConfigException exception =
                    new ApolloConfigException(
                            String.format("Persist local cache file %s failed", file.getAbsolutePath()), ex);
            Tracer.logError(exception);
            transaction.setStatus(exception);
            logger.warn("Persist local cache file {} failed, reason: {}.", file.getAbsolutePath(),
                    ExceptionUtil.getDetailMessage(ex));
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ex) {
                    //ignore
                }
            }
            transaction.complete();
        }
    }

    private void checkLocalConfigCacheDir(File baseDir) {
        if (baseDir.exists()) {
            return;
        }
        Transaction transaction = Tracer.newTransaction("Apollo.ConfigService", "createLocalConfigDir");
        transaction.addData("BaseDir", baseDir.getAbsolutePath());
        try {
            Files.createDirectory(baseDir.toPath());
            transaction.setStatus(Transaction.SUCCESS);
        } catch (IOException ex) {
            ApolloConfigException exception =
                    new ApolloConfigException(
                            String.format("Create local config directory %s failed", baseDir.getAbsolutePath()),
                            ex);
            Tracer.logError(exception);
            transaction.setStatus(exception);
            logger.warn(
                    "Unable to create local config cache directory {}, reason: {}. Will not able to cache config file.",
                    baseDir.getAbsolutePath(), ExceptionUtil.getDetailMessage(ex));
        } finally {
            transaction.complete();
        }
    }

    File assembleLocalCacheFile(File baseDir, String namespace) {
        String fileName =
                String.format("%s.properties", Joiner.on(ConfigConsts.CLUSTER_NAMESPACE_SEPARATOR)
                        .join(m_configUtil.getAppId(), m_configUtil.getCluster(), namespace));
        return new File(baseDir, fileName);
    }
}
