package cloud.ohiyou.utils;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * hifihi.com 反爬网关（/_guard/auto.js）求解器。
 *
 * <p>站点在 nginx 前置了一层 JS 网关：当请求缺少 {@code guardret} Cookie 时，
 * 服务器只返回 39 字节的 {@code <script src="/_guard/auto.js"></script>}，
 * 并下发 {@code Set-Cookie: guard=<值>}。浏览器执行脚本后写入 {@code guardret}
 * Cookie 再刷新页面即可正常访问；纯 HTTP 客户端若不实现这套握手，
 * 拿到的永远是这段空壳页面（表现为解析不到用户名，被误判成 Cookie 失效）。
 *
 * <p>auto.js 的核心算法（已逐字对照官方脚本还原）：
 * <pre>
 *   key      = guard.substring(0, 8)
 *   numPlain = parseInt(guard.substring(12), 10)   // 遇到非数字即停
 *   guardArg = numPlain * 2 + 18 - 2               // 即 numPlain * 2 + 16
 *   guardret = base64(rc4(String.valueOf(guardArg), key))
 * </pre>
 *
 * <p>实现与 JS 版本保持位级一致：
 * <ul>
 *   <li>RC4 以 {@code charCodeAt} 逐字符异或，Java 侧同样按 char 处理；</li>
 *   <li>JS 的 {@code base64Encode} 按字符码打包（每 3 个 char 组成 24 位取 6 位一组），
 *       等价于对 ISO-8859-1 字节序列做标准 Base64；</li>
 *   <li>{@code parseInt} 允许前导空白与正负号，遇到非法字符截断。</li>
 * </ul>
 *
 * @author ohiyou
 */
public final class GuardSolver {

    /** 网关空壳页面的特征片段，出现即代表需要走 guard 握手 */
    public static final String GUARD_PAGE_MARKER = "/_guard/auto.js";

    private static final String COOKIE_GUARD = "guard";

    private static final String COOKIE_GUARD_RET = "guardret";

    private GuardSolver() {
    }

    /**
     * 判断响应体是否为网关拦截页（空壳页面）
     *
     * @param body 响应体
     * @return true 表示被网关拦截，需要求解
     */
    public static boolean isGuardPage(String body) {
        return body != null && body.contains(GUARD_PAGE_MARKER);
    }

    /**
     * 根据 guard 值计算 guardret
     *
     * @param guard 服务器下发的 guard Cookie 值，如 {@code 6bb94a54SjSf90}
     * @return guardret 值；格式不合法时返回 null
     */
    public static String solve(String guard) {
        if (guard == null || guard.length() < 13) {
            return null;
        }

        // key = guard.substr(0, 8)
        String key = guard.substring(0, 8);

        // num_plain = parseInt(guard.substr(12), 10)
        Integer numPlain = parseJsInt(guard.substring(12));
        if (numPlain == null) {
            return null;
        }

        // guard_arg = num_plain * 2 + 18 - 2
        long guardArg = (long) numPlain * 2L + 18L - 2L;

        return base64Encode(rc4(String.valueOf(guardArg), key));
    }

    /**
     * 模拟 JS {@code parseInt(str, 10)}：跳过前导空白，允许正负号，遇到非数字截断
     *
     * @param text 待解析字符串
     * @return 解析结果；无有效数字时返回 null
     */
    static Integer parseJsInt(String text) {
        if (text == null) {
            return null;
        }
        int i = 0;
        int len = text.length();
        while (i < len && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        boolean negative = false;
        if (i < len && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
            negative = text.charAt(i) == '-';
            i++;
        }
        int start = i;
        while (i < len) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            i++;
        }
        if (i == start) {
            return null;
        }
        try {
            int value = Integer.parseInt(text.substring(start, i));
            return negative ? -value : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * RC4（流密码，加解密同一函数），按 char 逐字符处理以对齐 JS 的 charCodeAt
     *
     * @param text 明文
     * @param key  密钥
     * @return 密文（char 序列）
     */
    static String rc4(String text, String key) {
        if (key == null || key.isEmpty()) {
            return text;
        }

        int[] s = new int[256];
        for (int i = 0; i < 256; i++) {
            s[i] = i;
        }

        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + key.charAt(i % key.length())) % 256;
            int temp = s[i];
            s[i] = s[j];
            s[j] = temp;
        }

        StringBuilder result = new StringBuilder(text.length());
        int a = 0;
        int b = 0;
        for (int k = 0; k < text.length(); k++) {
            a = (a + 1) % 256;
            b = (b + s[a]) % 256;
            int temp = s[a];
            s[a] = s[b];
            s[b] = temp;
            result.append((char) (text.charAt(k) ^ s[(s[a] + s[b]) % 256]));
        }
        return result.toString();
    }

    /**
     * Base64 编码：等价于 JS 脚本中按字符码打包的实现。
     * JS 每 3 个 char 组成 24 位再按 6 位查表，正对应于 ISO-8859-1 字节的标准 Base64。
     *
     * @param text 待编码字符串（每个 char 视为一个字节）
     * @return Base64 字符串
     */
    static String base64Encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * 完整握手结果
     */
    public static final class Result {
        private final int code;
        private final String body;
        private final String cookie;

        Result(int code, String body, String cookie) {
            this.code = code;
            this.body = body;
            this.cookie = cookie;
        }

        public int getCode() {
            return code;
        }

        public String getBody() {
            return body;
        }

        /**
         * 通过网关后的 Cookie（含 guardret），可直接复用于后续请求
         */
        public String getCookie() {
            return cookie;
        }
    }

    /** 最多握手轮次：服务器每次可能下发新的 guard，正常 1 次即可通过 */
    private static final int MAX_GUARD_ROUNDS = 3;

    /** 网络层失败（连接被切断）时的重试次数 */
    public static final int MAX_NETWORK_RETRIES_PUBLIC = 2;

    /** 内部使用的重试次数（与公开常量保持一致，便于日志引用） */
    private static final int MAX_NETWORK_RETRIES = MAX_NETWORK_RETRIES_PUBLIC;

    /**
     * 重试的基准退避毫秒数，按 1x / 2x 递增。
     *
     * <p>刻意保持较短：hifihi.com 从境外机房访问时是"TCP 直接连不上"，
     * 长退避没有意义，只会让工作流白等好几分钟。真正需要长等待的是临时抖动，
     * 而临时抖动通常一次重试就能恢复。
     */
    private static final long RETRY_BASE_DELAY_MS = 800L;

    private static final Logger logger = LoggerFactory.getLogger(GuardSolver.class);

    /**
     * 发起 GET 并自动通过网关，网络层异常自动重试。
     *
     * <p>云机房网络（如 GitHub Actions）访问 hifihi 时常出现
     * {@code unexpected end of stream} / {@code Connection reset}，
     * 这是中间链路把连接单方面掐断导致的，换一条新连接往往就能成功。
     * 因此这里对这类异常做指数退避重试，同时每轮都重新握手（guard 是一次性的）。
     *
     * @param client  OkHttp 客户端
     * @param url     目标地址
     * @param cookie  业务 Cookie（bbs_sid / bbs_token 等）
     * @param headers 额外请求头
     * @return 握手结果（code + body + 可复用 Cookie）
     * @throws IOException 网络异常（重试后仍失败）
     */
    public static Result get(OkHttpClient client, HttpUrl url, String cookie,
                             Map<String, String> headers) throws IOException {
        IOException lastError = null;

        for (int attempt = 0; attempt <= MAX_NETWORK_RETRIES; attempt++) {
            if (attempt > 0) {
                long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
                logger.warn("HiFiHi 网络层请求失败（{}），{}ms 后第 {} 次重试",
                        lastError == null ? "未知" : lastError.getMessage(), delay, attempt);
                sleepQuietly(delay);
            }

            try {
                return handshake(client, url, cookie, headers);
            } catch (IOException e) {
                if (!isRetryable(e)) {
                    throw e;
                }
                lastError = e;
            }
        }
        throw lastError == null ? new IOException("请求失败") : lastError;
    }

    /**
     * 判断异常是否值得重试：连接被对端切断（而不是证书错误、域名解析失败等）
     */
    public static boolean isRetryable(Throwable e) {
        // 域名解析失败重试也没用（除非 DNS 抖动，但那属于极少见情况），直接放弃
        Throwable t = e;
        int depth = 0;
        while (t != null && depth++ < 6) {
            if (t instanceof java.net.UnknownHostException) {
                return false;
            }
            t = t.getCause();
        }
        return isHostUnreachable(e) || isTimeout(e);
    }

    /**
     * 判断是否为超时类异常
     */
    private static boolean isTimeout(Throwable e) {
        Throwable t = e;
        int depth = 0;
        while (t != null && depth++ < 6) {
            if (t instanceof java.net.SocketTimeoutException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase().contains("timed out")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 判断异常是否属于「TCP 层面根本连不上目标站点」。
     *
     * <p>这类情况才可归入"运行环境限制"（HOST_UNREACHABLE）：
     * 连接超时、连接被重置、域名解析失败、TLS 握手失败。
     * 一旦服务器返回了 HTTP 状态码，就不属于这里 —— 那是站点明确拒绝了请求。
     *
     * @param e 异常
     * @return true 表示连不上
     */
    public static boolean isHostUnreachable(Throwable e) {
        Throwable t = e;
        int depth = 0;
        while (t != null && depth++ < 6) {
            if (t instanceof java.net.ConnectException
                    || t instanceof java.net.UnknownHostException
                    || t instanceof java.net.NoRouteToHostException
                    || t instanceof javax.net.ssl.SSLException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("connect timed out")
                        || lower.contains("connection timed out")
                        || lower.contains("connection refused")
                        || lower.contains("no route to host")
                        || lower.contains("unexpected end of stream")
                        || lower.contains("connection reset")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * 把网络异常翻译成可操作的中文说明。
     *
     * <p>hifihi.com 的服务器位于中国大陆（111.92.243.x，中国移动福建），
     * 从境外机房（如 GitHub Actions 的 Azure westus2）访问时 TCP 会被直接丢弃，
     * 表现为 {@code connect timed out} 或 {@code unexpected end of stream}。
     * 这类问题换 Cookie、重试都没用，必须改变出口网络位置。
     *
     * @param e 原始异常
     * @return 面向使用者的说明
     */
    public static String describeNetworkError(Throwable e) {
        String raw = rootMessage(e);
        String lower = raw == null ? "" : raw.toLowerCase();

        if (lower.contains("connect timed out") || lower.contains("connection timed out")) {
            return raw + "（连接超时：目标站点疑似屏蔽了当前出口 IP。"
                    + "hifihi.com 服务器位于中国大陆，GitHub Actions 等境外机房通常无法直连，"
                    + "建议改用境内网络 / 自建设备运行，或改用云函数固定 IP）";
        }
        if (lower.contains("unexpected end of stream")) {
            return raw + "（连接被对端中途切断：常见于跨境链路或站点防火墙主动丢包，"
                    + "重试通常无效，需更换出口网络）";
        }
        if (lower.contains("connection reset")) {
            return raw + "（连接被重置：目标站点主动拒绝，可能是 IP 被风控或跨境阻断）";
        }
        return raw == null ? "未知网络异常" : raw;
    }

    /**
     * 取最内层异常的消息，便于定位真实原因
     */
    public static String rootMessage(Throwable e) {
        Throwable t = e;
        Throwable last = e;
        int depth = 0;
        while (t != null && depth++ < 6) {
            last = t;
            t = t.getCause();
        }
        String msg = last == null ? null : last.getMessage();
        return msg == null || msg.isEmpty() ? (last == null ? null : last.getClass().getSimpleName()) : msg;
    }

    /**
     * 带重试地执行任意请求（POST 签到等非 GET 场景用）。
     *
     * <p>与 {@link #get} 共用同一套重试策略：遇到连接被切断类异常时指数退避重试。
     * 注意 POST 请求每次重试都会重新调用 {@code requestFactory}，确保请求体可重发。
     *
     * @param client         客户端
     * @param requestFactory 请求构造器（每次重试都会重新调用）
     * @return 响应结果
     * @throws IOException 重试后仍失败
     */
    public static Result executeWithRetry(OkHttpClient client, RequestFactory requestFactory)
            throws IOException {
        IOException lastError = null;

        for (int attempt = 0; attempt <= MAX_NETWORK_RETRIES; attempt++) {
            if (attempt > 0) {
                long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
                logger.warn("HiFiHi 请求失败（{}），{}ms 后第 {} 次重试",
                        lastError == null ? "未知" : lastError.getMessage(), delay, attempt);
                sleepQuietly(delay);
            }
            try (Response response = client.newCall(requestFactory.create()).execute()) {
                String body = ResponseUtils.readResponse(response);
                return new Result(response.code(), body, null);
            } catch (IOException e) {
                if (!isRetryable(e)) {
                    throw e;
                }
                lastError = e;
            }
        }
        throw lastError == null ? new IOException("请求失败") : lastError;
    }

    /**
     * 请求构造器，供 {@link #executeWithRetry} 在每次重试时重新生成请求
     */
    public interface RequestFactory {
        /**
         * @return 全新的请求对象
         */
        Request create();
    }

    /**
     * 单次握手流程（可能包含多轮 guard 计算）
     */
    private static Result handshake(OkHttpClient client, HttpUrl url, String cookie,
                                    Map<String, String> headers) throws IOException {
        String currentCookie = cookie;
        int code = 0;
        String body = "";

        for (int round = 0; round < MAX_GUARD_ROUNDS; round++) {
            try (Response response = execute(client, url, currentCookie, headers)) {
                code = response.code();
                body = ResponseUtils.readResponse(response);
                if (!isGuardPage(body)) {
                    return new Result(code, body, currentCookie);
                }

                String guard = extractGuardCookie(response.headers("Set-Cookie"));
                if (guard == null) {
                    // 没有 guard 可解，说明拦截机制变了，交由上层当作异常处理
                    return new Result(code, body, currentCookie);
                }
                String guardRet = solve(guard);
                if (guardRet == null) {
                    return new Result(code, body, currentCookie);
                }

                currentCookie = mergeCookie(currentCookie, COOKIE_GUARD, guard);
                currentCookie = mergeCookie(currentCookie, COOKIE_GUARD_RET, guardRet);
            }
        }
        return new Result(code, body, currentCookie);
    }

    private static Response execute(OkHttpClient client, HttpUrl url, String cookie,
                                    Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder().url(url).get();
        if (cookie != null && !cookie.isEmpty()) {
            builder.addHeader("Cookie", cookie);
        }
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.addHeader(entry.getKey(), entry.getValue());
            }
        }
        return client.newCall(builder.build()).execute();
    }

    /**
     * 从 Set-Cookie 中取出 guard 值（取最后一个，与 JS getCookie 语义一致）
     */
    static String extractGuardCookie(List<String> setCookies) {
        String found = null;
        for (String value : setCookies) {
            String pair = value.split(";", 2)[0].trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (COOKIE_GUARD.equals(pair.substring(0, eq).trim())) {
                found = pair.substring(eq + 1);
            }
        }
        return found;
    }

    /**
     * 将键值对合并进 Cookie 字符串（同名键覆盖）
     */
    static String mergeCookie(String cookie, String name, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        if (cookie != null && !cookie.isEmpty()) {
            for (String part : cookie.split(";")) {
                String pair = part.trim();
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                map.put(pair.substring(0, eq).trim(), pair.substring(eq + 1));
            }
        }
        map.put(name, value);

        List<String> items = new ArrayList<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            items.add(entry.getKey() + "=" + entry.getValue());
        }
        return String.join("; ", items);
    }
}
