package cloud.ohiyou.handler;

import cloud.ohiyou.constant.HifiniConstants;
import cloud.ohiyou.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Cookie 处理器
 * 负责 Cookie 的解析、验证和格式化
 *
 * @author ohiyou
 */
public class CookieHandler {

    private static final Logger logger = LoggerFactory.getLogger(CookieHandler.class);

    /**
     * 格式化 Cookie，提取 bbs_sid 和 bbs_token
     *
     * @param cookie 原始 Cookie 字符串
     * @param index  Cookie 索引（用于日志）
     * @return 格式化后的 Cookie，如果格式无效则返回 empty
     */
    public Optional<String> formatCookie(String cookie, int index) {
        logger.debug("开始解析第 {} 个cookie", index + 1);

        if (StringUtils.isBlank(cookie)) {
            logger.warn("第 {} 个cookie为空", index + 1);
            return Optional.empty();
        }

        String bbsSid = null;
        String bbsToken = null;

        String[] parts = cookie.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            // 兼容 "bbs_sid=xxx" 与 "bbs_sid: xxx" 两种写法，以及大小写差异
            if (trimmed.toLowerCase().startsWith(HifiniConstants.COOKIE_KEY_BBS_SID + "=")
                    || trimmed.toLowerCase().startsWith(HifiniConstants.COOKIE_KEY_BBS_SID + ":")) {
                bbsSid = HifiniConstants.COOKIE_KEY_BBS_SID + "=" + extractValue(trimmed);
                logger.debug("找到bbs_sid");
            } else if (trimmed.toLowerCase().startsWith(HifiniConstants.COOKIE_KEY_BBS_TOKEN + "=")
                    || trimmed.toLowerCase().startsWith(HifiniConstants.COOKIE_KEY_BBS_TOKEN + ":")) {
                bbsToken = HifiniConstants.COOKIE_KEY_BBS_TOKEN + "=" + extractValue(trimmed);
                logger.debug("找到bbs_token");
            }
        }

        if (bbsSid != null && bbsToken != null) {
            logger.info("成功解析第 {} 个cookie", index + 1);
            return Optional.of(bbsSid + ";" + bbsToken + ";");
        }

        logger.error("解析第 {} 个cookie失败，格式不正确。缺少: {}",
                index + 1,
                bbsSid == null ? HifiniConstants.COOKIE_KEY_BBS_SID : HifiniConstants.COOKIE_KEY_BBS_TOKEN);
        return Optional.empty();
    }

    /**
     * 取出等号/冒号后面的值
     */
    private String extractValue(String pair) {
        int eq = pair.indexOf('=');
        int colon = pair.indexOf(':');
        int idx = eq >= 0 ? eq : colon;
        if (idx < 0 || idx == pair.length() - 1) {
            return "";
        }
        return pair.substring(idx + 1).trim();
    }

    /**
     * 分割多个 Cookie 字符串
     *
     * <p>支持 &amp; 与换行分隔，并过滤空项，避免多账号配置末尾多一个 &amp; 时
     * 产生一个空的 Cookie 任务。</p>
     *
     * @param cookies 以 &amp; 分隔的多个 Cookie
     * @return Cookie 数组
     */
    public String[] splitCookies(String cookies) {
        if (StringUtils.isBlank(cookies)) {
            return new String[0];
        }
        return Arrays.stream(cookies.split("[&\\r\\n]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList())
                .toArray(new String[0]);
    }
}
