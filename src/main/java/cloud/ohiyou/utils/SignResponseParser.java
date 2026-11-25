package cloud.ohiyou.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import cloud.ohiyou.constant.SignResultCode;
import cloud.ohiyou.vo.SignResultVO;

/**
 * 签到接口响应解析工具
 *
 * <p>站点签到接口的真实返回（Xiuno 风格）为字符串 code：
 * <pre>
 *   {"code":"0","message":"签到成功，获得 2 金币"}
 *   {"code":"-1","message":"今天已经签过了，明天再来吧"}
 *   {"code":"0","message":"请登录后再签到!"}   &lt;-- 未登录也返回 0
 * </pre>
 * 直接反序列化成 Integer 会失败（"0" 能转，"请登录后再签到" 也可转 0），
 * 但未登录与成功无法区分，所以这里集中做语义判断。</p>
 *
 * @author ohiyou
 */
public final class SignResponseParser {

    private SignResponseParser() {
    }

    /**
     * 解析签到响应
     *
     * @param body 响应文本
     * @return 签到结果（code 已归一化为项目内部结果码）
     */
    public static SignResultVO parse(String body) {
        if (body == null || body.trim().isEmpty()) {
            return new SignResultVO(SignResultCode.FAILURE.getCode(), "签到接口返回为空");
        }

        JSONObject json;
        try {
            json = JSON.parseObject(body);
        } catch (Exception e) {
            // 不是 JSON：通常是站点返回了登录页 / 防火墙页面
            if (body.contains("用户登录") || body.contains("user-login")) {
                return new SignResultVO(SignResultCode.COOKIE_INVALID.getCode(),
                        "Cookie已失效（站点返回登录页）");
            }
            String snippet = body.length() > 200 ? body.substring(0, 200) : body;
            return new SignResultVO(SignResultCode.FAILURE.getCode(), "响应格式异常: " + snippet);
        }

        if (json == null) {
            return new SignResultVO(SignResultCode.FAILURE.getCode(), "响应格式异常: " + body);
        }

        String rawCode = json.getString("code");
        String message = json.getString("message");
        if (message == null) {
            message = json.getString("msg");
        }

        int code = parseCode(rawCode);

        // 关键修复：未登录返回 {"code":"0","message":"请登录后再签到!"}
        if (isAuthMessage(message)) {
            code = SignResultCode.COOKIE_INVALID.getCode();
        }

        return new SignResultVO(code, message);
    }

    /**
     * 解析 code 字段（兼容字符串与数字）
     */
    private static int parseCode(String rawCode) {
        if (rawCode == null) {
            return SignResultCode.UNKNOWN_ERROR.getCode();
        }
        String c = rawCode.trim();
        if ("0".equals(c)) {
            return SignResultCode.SUCCESS.getCode();
        }
        if ("-1".equals(c)) {
            return SignResultCode.ALREADY_SIGNED.getCode();
        }
        if ("1".equals(c)) {
            // 部分版本用 1 表示"已签到"
            return SignResultCode.ALREADY_SIGNED.getCode();
        }
        return SignResultCode.FAILURE.getCode();
    }

    /**
     * 判断 message 是否表示登录态失效
     */
    public static boolean isAuthMessage(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("请登录")
                || message.contains("未登录")
                || message.contains("登录后再")
                || message.contains("请先登录");
    }
}
