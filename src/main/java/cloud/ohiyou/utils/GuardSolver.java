package cloud.ohiyou.utils;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

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

    /**
     * 发起 GET 并自动通过网关。
     *
     * @param client  OkHttp 客户端
     * @param url     目标地址
     * @param cookie  业务 Cookie（bbs_sid / bbs_token 等）
     * @param headers 额外请求头
     * @return 握手结果（code + body + 可复用 Cookie）
     * @throws Exception 网络异常
     */
    public static Result get(OkHttpClient client, HttpUrl url, String cookie,
                             Map<String, String> headers) throws Exception {
        try (Response response = execute(client, url, cookie, headers)) {
            String body = ResponseUtils.readResponse(response);
            if (!isGuardPage(body)) {
                return new Result(response.code(), body, cookie);
            }

            String guard = extractGuardCookie(response.headers("Set-Cookie"));
            if (guard == null) {
                return new Result(response.code(), body, cookie);
            }
            String guardRet = solve(guard);
            if (guardRet == null) {
                return new Result(response.code(), body, cookie);
            }

            String merged = mergeCookie(cookie, COOKIE_GUARD, guard);
            merged = mergeCookie(merged, COOKIE_GUARD_RET, guardRet);

            try (Response second = execute(client, url, merged, headers)) {
                String secondBody = ResponseUtils.readResponse(second);
                return new Result(second.code(), secondBody, merged);
            }
        }
    }

    private static Response execute(OkHttpClient client, HttpUrl url, String cookie,
                                    Map<String, String> headers) throws Exception {
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
