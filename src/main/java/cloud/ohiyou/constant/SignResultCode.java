package cloud.ohiyou.constant;

/**
 * 签到结果码枚举
 *
 * <p>注意：站点签到接口在未登录时返回的 HTTP 状态是 200，body 为
 * {"code":"0","message":"请登录后再签到!"}，也就是 code 同样为 "0"。
 * 因此不能只看 code 判断成败，必须结合 message 与登录态校验。</p>
 *
 * @author ohiyou
 */
public enum SignResultCode {

    SUCCESS(0, "签到成功"),
    ALREADY_SIGNED(-1, "今日已签到"),
    FAILURE(2, "签到失败"),
    COOKIE_INVALID(401, "Cookie已失效"),
    NETWORK_ERROR(500, "网络错误"),
    UNKNOWN_ERROR(999, "未知错误");

    private final int code;
    private final String description;

    SignResultCode(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 根据code值获取对应的枚举
     *
     * @param code 结果码
     * @return 对应的枚举值，如果不存在则返回 UNKNOWN_ERROR
     */
    public static SignResultCode fromCode(Integer code) {
        if (code == null) {
            return UNKNOWN_ERROR;
        }
        for (SignResultCode rc : values()) {
            if (rc.code == code) {
                return rc;
            }
        }
        return UNKNOWN_ERROR;
    }

    /**
     * 判断是否为成功状态（签到成功或今日已签到）
     *
     * @return 是否成功
     */
    public boolean isSuccess() {
        return this == SUCCESS || this == ALREADY_SIGNED;
    }

    /**
     * 判断是否为需要重新登录的失效状态
     *
     * @return 是否需要重新登录
     */
    public boolean isAuthFailure() {
        return this == COOKIE_INVALID;
    }

    /**
     * 判断是否为"环境不可达"类结果。
     *
     * <p>这类结果与业务无关：网络不通、出口 IP 被站点屏蔽等，重试与换 Cookie 都无效。
     * hifihi.com 服务器位于中国大陆，从 GitHub Actions（境外机房）访问时 TCP 会被丢弃，
     * 属于典型的运行环境限制。若把它当作"签到失败"，工作流会每天变红，
     * 掩盖真正需要处理的问题（例如 Cookie 过期）。
     *
     * @return true 表示属于环境限制，不应计入签到失败
     */
    public boolean isEnvironmentBlocked() {
        return this == NETWORK_ERROR;
    }
}
