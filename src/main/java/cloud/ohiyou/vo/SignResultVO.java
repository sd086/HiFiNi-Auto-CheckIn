package cloud.ohiyou.vo;

import cloud.ohiyou.constant.SignResultCode;
import lombok.Data;

/**
 * 签到结果值对象
 *
 * @author ohiyou
 * @since 2024/2/21 11:37
 */
@Data
public class SignResultVO {
    /**
     * 返回code
     */
    private Integer code;
    /**
     * 返回信息
     */
    private String message;
    /**
     * 用户名
     */
    private String userName;
    /**
     * 用户信息
     */
    private UserInfoVO userInfo;

    public SignResultVO(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 获取格式化的签到结果消息
     */
    public String getFormattedMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append(message == null ? getResultCode().getDescription() : message);

        if (userInfo != null) {
            if (userInfo.getCoins() != null) {
                sb.append("\n\n当前金币: ").append(userInfo.getCoins());
            }
            if (userInfo.getSignStreak() != null) {
                sb.append("\n\n连续签到: 第").append(userInfo.getSignStreak()).append("天");
            }
        }

        return sb.toString();
    }

    /**
     * 获取签到结果码枚举
     *
     * @return 签到结果码枚举
     */
    public SignResultCode getResultCode() {
        return SignResultCode.fromCode(this.code);
    }

    /**
     * 判断签到是否成功
     *
     * @return 是否成功（签到成功或今日已签到）
     */
    public boolean isSuccess() {
        return getResultCode().isSuccess();
    }

    /**
     * 判断是否需要重新登录
     *
     * @return Cookie 是否失效
     */
    public boolean isAuthFailure() {
        return getResultCode().isAuthFailure();
    }
}
