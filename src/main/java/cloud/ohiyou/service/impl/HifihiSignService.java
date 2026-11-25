package cloud.ohiyou.service.impl;

import cloud.ohiyou.constant.HifiniConstants;
import cloud.ohiyou.constant.SignResultCode;
import cloud.ohiyou.service.ISignService;
import cloud.ohiyou.utils.GuardSolver;
import cloud.ohiyou.utils.OkHttpUtils;
import cloud.ohiyou.utils.ResponseUtils;
import cloud.ohiyou.utils.SignResponseParser;
import cloud.ohiyou.vo.SignResultVO;
import cloud.ohiyou.vo.UserInfoVO;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HiFiHi 签到服务实现
 *
 * @author ohiyou
 */
public class HifihiSignService implements ISignService {

    private static final Logger logger = LoggerFactory.getLogger(HifihiSignService.class);

    private static final OkHttpClient client = OkHttpUtils.getClient();
    private final Random random = new Random();

    @Override
    public String getSiteName() {
        return HifiniConstants.SITE_HIFIHI;
    }

    /**
     * 签到
     *
     * @param cookie cookie
     * @return 签到结果
     */
    @Override
    public SignResultVO signIn(String cookie) {
        try {
            String userAgent = getRandomUserAgent();
            String effectiveCookie = ensureGuard(cookie, userAgent);

            RequestBody emptyBody = RequestBody.create("",
                    MediaType.get("application/x-www-form-urlencoded; charset=UTF-8"));

            Request request = new Request.Builder()
                    .url(HifiniConstants.HIFIHI_SIGN_URL)
                    .post(emptyBody)
                    .addHeader("Cookie", effectiveCookie)
                    .addHeader("User-Agent", userAgent)
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .addHeader("Referer", HifiniConstants.HIFIHI_BASE_URL + "/")
                    .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                    .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return new SignResultVO(SignResultCode.FAILURE.getCode(),
                            "请求失败，Http状态码：" + response.code());
                }

                String responseBody = ResponseUtils.readResponse(response);
                logger.info("HiFiHi 签到响应内容: {}", responseBody);

                // 网关拦截页不是 JSON，直接判为网络/防护问题，避免被当成"请登录"误报
                if (GuardSolver.isGuardPage(responseBody)) {
                    return new SignResultVO(SignResultCode.NETWORK_ERROR.getCode(),
                            "请求被站点反爬网关拦截，请检查 GuardSolver 实现");
                }
                return SignResponseParser.parse(responseBody);
            }
        } catch (Exception e) {
            logger.error("HiFiHi 签到异常: {}", e.getMessage(), e);
            return new SignResultVO(SignResultCode.NETWORK_ERROR.getCode(), "签到异常：" + e.getMessage());
        }
    }

    /**
     * 确保 Cookie 中带有 guardret（站点 JS 反爬网关要求）。
     * 先访问一次首页完成握手，返回可直接用于后续请求的 Cookie；握手失败时回退原 Cookie。
     */
    private String ensureGuard(String cookie, String userAgent) {
        if (cookie != null && cookie.contains("guardret=")) {
            return cookie;
        }
        try {
            GuardSolver.Result result = GuardSolver.get(client,
                    HttpUrl.get(HifiniConstants.HIFIHI_BASE_URL + "/"), cookie,
                    htmlHeaders(userAgent));
            if (!GuardSolver.isGuardPage(result.getBody())) {
                logger.debug("HiFiHi 反爬网关握手成功");
                return result.getCookie();
            }
            logger.warn("HiFiHi 反爬网关握手未通过，继续使用原始 Cookie 尝试");
        } catch (Exception e) {
            logger.warn("HiFiHi 反爬网关握手异常: {}", e.getMessage());
        }
        return cookie;
    }

    /**
     * 获取随机的 User-Agent
     */
    private String getRandomUserAgent() {
        return HifiniConstants.USER_AGENTS.get(random.nextInt(HifiniConstants.USER_AGENTS.size()));
    }

    /**
     * 校验 Cookie 是否有效：访问 my.htm，能解析到用户名说明登录态正常
     *
     * @throws IOException 网络异常或反爬网关握手失败（无法判断登录态，不可当作失效）
     */
    @Override
    public boolean isCookieValid(String cookie) throws IOException {
        String userAgent = getRandomUserAgent();
        Map<String, String> headers = htmlHeaders(userAgent);

        GuardSolver.Result result = GuardSolver.get(client,
                HttpUrl.get(HifiniConstants.HIFIHI_USER_INFO_URL), cookie, headers);

        if (result.getCode() != 200) {
            throw new IOException("校验登录态失败，HTTP 状态码: " + result.getCode());
        }

        String pageContent = result.getBody();
        if (GuardSolver.isGuardPage(pageContent)) {
            logger.error("HiFiHi 反爬网关握手失败（未能通过 /_guard/auto.js 校验）");
            throw new IOException("站点反爬网关拦截，握手未通过");
        }
        if (pageContent.contains(HifiniConstants.REGEX_LOGIN_PAGE)) {
            logger.warn("HiFiHi Cookie 已失效（返回登录页）");
            return false;
        }
        UserInfoVO userInfo = parseUserInfo(pageContent);
        boolean valid = userInfo.getUserName() != null;
        if (!valid) {
            logger.warn("HiFiHi Cookie 已失效（页面中未找到用户信息）");
        } else {
            logger.info("HiFiHi Cookie 有效，用户: {}", userInfo.getUserName());
        }
        return valid;
    }

    /**
     * 拼接 HTML 页面请求头
     */
    private Map<String, String> htmlHeaders(String userAgent) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", userAgent);
        headers.put("Referer", HifiniConstants.HIFIHI_BASE_URL + "/");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        return headers;
    }

    /**
     * 获取用户信息
     *
     * @param cookie cookie
     * @return 用户信息
     */
    @Override
    public UserInfoVO getUserInfo(String cookie) {
        try {
            String userAgent = getRandomUserAgent();

            GuardSolver.Result result = GuardSolver.get(client,
                    HttpUrl.get(HifiniConstants.HIFIHI_USER_INFO_URL), cookie,
                    htmlHeaders(userAgent));

            if (result.getCode() != 200) {
                throw new RuntimeException("获取用户信息失败，HTTP状态码: " + result.getCode());
            }
            return parseUserInfo(result.getBody());

        } catch (Exception e) {
            logger.error("HiFiHi 获取用户信息异常: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户信息失败: " + e.getMessage());
        }
    }

    /**
     * 获取连续签到天数
     *
     * @param cookie 用户cookie
     * @return 连续签到天数
     */
    @Override
    public Integer getSignStreak(String cookie) {
        try {
            String userAgent = getRandomUserAgent();

            GuardSolver.Result result = GuardSolver.get(client,
                    HttpUrl.get(HifiniConstants.HIFIHI_SIGN_URL), cookie,
                    htmlHeaders(userAgent));

            if (result.getCode() != 200) {
                return null;
            }
            return parseSignStreak(result.getBody());

        } catch (Exception e) {
            logger.error("HiFiHi 获取连续签到天数异常: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从my.htm页面内容解析用户信息
     */
    private UserInfoVO parseUserInfo(String pageContent) {
        UserInfoVO userInfo = new UserInfoVO();

        try {
            // 解析用户名
            Pattern namePattern = Pattern.compile(HifiniConstants.REGEX_USERNAME);
            Matcher nameMatcher = namePattern.matcher(pageContent);
            if (nameMatcher.find()) {
                userInfo.setUserName(nameMatcher.group(1).trim());
            }

            // 解析金币数量
            Pattern coinPattern = Pattern.compile(HifiniConstants.REGEX_COINS);
            Matcher coinMatcher = coinPattern.matcher(pageContent);
            if (coinMatcher.find()) {
                userInfo.setCoins(Integer.parseInt(coinMatcher.group(1)));
            }

        } catch (Exception e) {
            logger.error("HiFiHi 解析用户信息时出错: {}", e.getMessage(), e);
        }

        return userInfo;
    }

    /**
     * 从sg_sign.htm页面解析连续签到天数
     */
    private Integer parseSignStreak(String signPageContent) {
        try {
            // 匹配 JavaScript 变量中的连续签到天数
            Pattern streakPattern = Pattern.compile(HifiniConstants.REGEX_SIGN_STREAK);
            Matcher streakMatcher = streakPattern.matcher(signPageContent);
            if (streakMatcher.find()) {
                int streak = Integer.parseInt(streakMatcher.group(1));
                logger.debug("HiFiHi 解析连续签到天数成功: {} 天", streak);
                return streak;
            } else {
                logger.debug("HiFiHi 页面中未找到连续签到天数信息");
                // 尝试更宽松的匹配
                Pattern fallbackPattern = Pattern.compile(HifiniConstants.REGEX_SIGN_STREAK_FALLBACK);
                Matcher fallbackMatcher = fallbackPattern.matcher(signPageContent);
                if (fallbackMatcher.find()) {
                    int streak = Integer.parseInt(fallbackMatcher.group(1));
                    logger.debug("HiFiHi 通过备用方式解析连续签到天数成功: {} 天", streak);
                    return streak;
                }
            }
        } catch (Exception e) {
            logger.error("HiFiHi 解析连续签到天数时出错: {}", e.getMessage(), e);
        }
        return null;
    }
}
