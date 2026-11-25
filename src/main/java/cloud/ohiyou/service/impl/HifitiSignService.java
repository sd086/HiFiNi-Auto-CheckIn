package cloud.ohiyou.service.impl;

import cloud.ohiyou.config.AccountConfig;
import cloud.ohiyou.constant.HifiniConstants;
import cloud.ohiyou.constant.SignResultCode;
import cloud.ohiyou.service.ISignService;
import cloud.ohiyou.utils.CryptoUtils;
import cloud.ohiyou.utils.OkHttpUtils;
import cloud.ohiyou.utils.ResponseUtils;
import cloud.ohiyou.utils.SignResponseParser;
import cloud.ohiyou.vo.SignResultVO;
import cloud.ohiyou.vo.UserInfoVO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HiFiTi 签到服务实现
 *
 * @author ohiyou
 */
public class HifitiSignService implements ISignService {

    private static final Logger logger = LoggerFactory.getLogger(HifitiSignService.class);

    private static final OkHttpClient client = OkHttpUtils.getClient();
    private final Random random = new Random();

    @Override
    public String getSiteName() {
        return HifiniConstants.SITE_HIFITI;
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

            RequestBody emptyBody = RequestBody.create("",
                    MediaType.get("application/x-www-form-urlencoded; charset=UTF-8"));

            Request request = new Request.Builder()
                    .url(HifiniConstants.HIFITI_SIGN_URL)
                    .post(emptyBody)
                    .addHeader("Cookie", cookie)
                    .addHeader("User-Agent", userAgent)
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .addHeader("Referer", HifiniConstants.HIFITI_SIGN_URL)
                    .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return new SignResultVO(SignResultCode.FAILURE.getCode(),
                            "请求失败，Http状态码：" + response.code());
                }

                String responseBody = ResponseUtils.readResponse(response);
                logger.info("HiFiTi 签到响应内容: {}", responseBody);
                return SignResponseParser.parse(responseBody);
            }
        } catch (Exception e) {
            logger.error("签到异常: {}", e.getMessage(), e);
            return new SignResultVO(SignResultCode.NETWORK_ERROR.getCode(), "签到异常：" + e.getMessage());
        }
    }

    /**
     * 获取随机的 User-Agent
     */
    private String getRandomUserAgent() {
        return HifiniConstants.USER_AGENTS.get(random.nextInt(HifiniConstants.USER_AGENTS.size()));
    }

    /**
     * 校验 Cookie 是否有效：访问 my.htm，能解析到用户名/uid 说明登录态正常
     */
    @Override
    public boolean isCookieValid(String cookie) {
        try {
            String userAgent = getRandomUserAgent();

            Request request = new Request.Builder()
                    .url(HifiniConstants.HIFITI_USER_INFO_URL)
                    .get()
                    .addHeader("Cookie", cookie)
                    .addHeader("User-Agent", userAgent)
                    .addHeader("Referer", HifiniConstants.HIFITI_BASE_URL + "/")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.warn("HiFiTi Cookie 校验失败，HTTP 状态码: {}", response.code());
                    return false;
                }
                String pageContent = ResponseUtils.readResponse(response);
                if (pageContent.contains(HifiniConstants.REGEX_LOGIN_PAGE)) {
                    logger.warn("HiFiTi Cookie 已失效（返回登录页）");
                    return false;
                }
                UserInfoVO userInfo = parseUserInfo(pageContent);
                boolean valid = userInfo.getUserName() != null;
                if (!valid) {
                    logger.warn("HiFiTi Cookie 已失效（页面中未找到用户信息）");
                } else {
                    logger.info("HiFiTi Cookie 有效，用户: {}", userInfo.getUserName());
                }
                return valid;
            }
        } catch (Exception e) {
            logger.error("HiFiTi 校验 Cookie 异常: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 账号密码登录，成功后返回站点下发的 Cookie（bbs_sid;bbs_token）
     *
     * @param account 账号配置
     * @return 登录成功返回 Cookie，失败返回 null
     */
    public String login(AccountConfig account) {
        try {
            String userAgent = getRandomUserAgent();
            Map<String, String> form = new HashMap<>();
            form.put("email", account.getEmail());
            // Xiuno BBS 前端会先做一次 md5 再提交
            form.put("password", CryptoUtils.md5(account.getPassword()));

            RequestBody body = RequestBody.create(buildFormBody(form),
                    MediaType.get("application/x-www-form-urlencoded; charset=UTF-8"));

            Request request = new Request.Builder()
                    .url(HifiniConstants.HIFITI_LOGIN_URL)
                    .post(body)
                    .addHeader("User-Agent", userAgent)
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .addHeader("Referer", HifiniConstants.HIFITI_LOGIN_URL)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String responseBody = ResponseUtils.readResponse(response);
                if (!response.isSuccessful()) {
                    logger.error("HiFiTi 登录失败，HTTP 状态码: {}", response.code());
                    return null;
                }

                JSONObject json = JSON.parseObject(responseBody);
                String code = json.getString("code");
                if (!"0".equals(code)) {
                    logger.error("HiFiTi 登录失败: {}", responseBody);
                    return null;
                }

                String bbsSid = null;
                String bbsToken = null;
                List<Cookie> cookies = Cookie.parseAll(request.url(), response.headers());
                for (Cookie cookie : cookies) {
                    if (HifiniConstants.COOKIE_KEY_BBS_SID.equals(cookie.name())) {
                        bbsSid = cookie.name() + "=" + cookie.value();
                    } else if (HifiniConstants.COOKIE_KEY_BBS_TOKEN.equals(cookie.name())) {
                        bbsToken = cookie.name() + "=" + cookie.value();
                    }
                }

                if (bbsSid == null) {
                    logger.error("HiFiTi 登录成功但未获取到 bbs_sid，返回的 Set-Cookie: {}",
                            response.headers("Set-Cookie"));
                    return null;
                }
                String newCookie = bbsToken != null ? bbsSid + ";" + bbsToken + ";" : bbsSid + ";";
                logger.info("HiFiTi 账号密码登录成功, 用户: {}", account.getEmail());
                return newCookie;
            }
        } catch (Exception e) {
            logger.error("HiFiTi 账号密码登录异常: {}", e.getMessage(), e);
            return null;
        }
    }

    private String buildFormBody(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return sb.toString();
    }

    private String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
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

            Request request = new Request.Builder()
                    .url(HifiniConstants.HIFITI_USER_INFO_URL)
                    .get()
                    .addHeader("Cookie", cookie)
                    .addHeader("User-Agent", userAgent)
                    .addHeader("Referer", HifiniConstants.HIFITI_BASE_URL + "/")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("获取用户信息失败，HTTP状态码: " + response.code());
                }

                String pageContent = ResponseUtils.readResponse(response);
                return parseUserInfo(pageContent);
            }

        } catch (Exception e) {
            logger.error("获取用户信息异常: {}", e.getMessage(), e);
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

            Request request = new Request.Builder()
                    .url(HifiniConstants.HIFITI_SIGN_URL)
                    .get()
                    .addHeader("Cookie", cookie)
                    .addHeader("User-Agent", userAgent)
                    .addHeader("Referer", HifiniConstants.HIFITI_BASE_URL + "/")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return null;
                }

                String pageContent = ResponseUtils.readResponse(response);
                return parseSignStreak(pageContent);
            }

        } catch (Exception e) {
            logger.error("获取连续签到天数异常: {}", e.getMessage(), e);
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
            logger.error("解析用户信息时出错: {}", e.getMessage(), e);
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
                logger.debug("解析连续签到天数成功: {} 天", streak);
                return streak;
            } else {
                logger.debug("页面中未找到连续签到天数信息");
                // 尝试更宽松的匹配
                Pattern fallbackPattern = Pattern.compile(HifiniConstants.REGEX_SIGN_STREAK_FALLBACK);
                Matcher fallbackMatcher = fallbackPattern.matcher(signPageContent);
                if (fallbackMatcher.find()) {
                    int streak = Integer.parseInt(fallbackMatcher.group(1));
                    logger.debug("通过备用方式解析连续签到天数成功: {} 天", streak);
                    return streak;
                }
            }
        } catch (Exception e) {
            logger.error("解析连续签到天数时出错: {}", e.getMessage(), e);
        }
        return null;
    }
}
