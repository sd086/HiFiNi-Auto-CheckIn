package cloud.ohiyou.utils;

import cloud.ohiyou.constant.HifiniConstants;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * OkHttp 客户端工具类
 * 提供全局唯一的 OkHttpClient 实例
 *
 * @author ohiyou
 */
public final class OkHttpUtils {

    private static final Logger logger = LoggerFactory.getLogger(OkHttpUtils.class);

    private static volatile OkHttpClient client;

    private static volatile OkHttpClient strictClient;

    private static final Object LOCK = new Object();

    private static final Object STRICT_LOCK = new Object();

    /**
     * 禁止实例化
     */
    private OkHttpUtils() {
    }

    /**
     * 获取 OkHttpClient 单例实例
     * 使用双重检查锁定确保线程安全
     *
     * @return OkHttpClient 实例
     */
    public static OkHttpClient getClient() {
        if (client == null) {
            synchronized (LOCK) {
                if (client == null) {
                    client = createClient();
                }
            }
        }
        return client;
    }

    /**
     * 获取"每次新建连接"的客户端，专供对连接质量敏感的站点（如 hifihi）。
     *
     * <p>背景：GitHub Actions 等云机房网络在访问部分站点时，连接池里的空闲连接
     * 会被中间的防火墙/负载均衡单方面掐断，OkHttp 复用这种"僵尸连接"时
     * 就会抛 {@code unexpected end of stream}。这里关闭连接复用，
     * 保证每次请求都走一条全新的 TCP + TLS 连接，从根上避免该问题。
     *
     * @return 独立配置的 OkHttpClient 实例
     */
    public static OkHttpClient getNoReuseClient() {
        if (strictClient == null) {
            synchronized (STRICT_LOCK) {
                if (strictClient == null) {
                    ExecutorService executor = new ThreadPoolExecutor(
                            0,
                            Integer.MAX_VALUE,
                            60L,
                            TimeUnit.SECONDS,
                            new SynchronousQueue<>(),
                            r -> {
                                Thread thread = new Thread(r, "OkHttp-NoReuse-Daemon");
                                thread.setDaemon(true);
                                return thread;
                            }
                    );

                    strictClient = new OkHttpClient.Builder()
                            .dispatcher(new Dispatcher(executor))
                            .connectTimeout(HifiniConstants.DEFAULT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .readTimeout(HifiniConstants.DEFAULT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .writeTimeout(HifiniConstants.DEFAULT_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
                            .followRedirects(true)
                            .followSslRedirects(true)
                            // 关键：连接用完立即失效，下次请求重新握手
                            .connectionPool(new okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
                            // 关掉失败重连时的连接复用，强制换新连接
                            .retryOnConnectionFailure(true)
                            .build();
                }
            }
        }
        return strictClient;
    }

    /**
     * 创建 OkHttpClient 实例
     */
    private static OkHttpClient createClient() {
        // 自定义守护线程池，解决 exec 命令中线程无法释放问题
        ExecutorService executor = new ThreadPoolExecutor(
                0,
                Integer.MAX_VALUE,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread thread = new Thread(r, "OkHttp-Daemon");
                    thread.setDaemon(true);
                    return thread;
                }
        );

        return new OkHttpClient.Builder()
                .dispatcher(new Dispatcher(executor))
                .connectTimeout(HifiniConstants.DEFAULT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(HifiniConstants.DEFAULT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(HifiniConstants.DEFAULT_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // 默认就是 HTTP/1.1，这里显式指定，避免个别环境下 h2 协商导致的 PROTOCOL_ERROR
                .protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
                // 跟随重定向（登录态失效时站点可能返回 302）
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    /**
     * 关闭 OkHttpClient 资源
     * 应在应用退出时调用
     */
    public static void shutdown() {
        close(client, "OkHttpClient");
        close(strictClient, "OkHttpClient(NoReuse)");
    }

    private static void close(OkHttpClient target, String name) {
        if (target != null) {
            try {
                target.dispatcher().executorService().shutdownNow();
                target.connectionPool().evictAll();
                if (target.cache() != null) {
                    target.cache().close();
                }
                logger.info("{} 资源已释放", name);
            } catch (Exception e) {
                logger.error("关闭 {} 时发生异常: {}", name, e.getMessage(), e);
            }
        }
    }
}
