package cloud.ohiyou.service;

import cloud.ohiyou.vo.SignResultVO;
import cloud.ohiyou.vo.UserInfoVO;

/**
 * 签到服务接口
 *
 * @author ohiyou
 * 2025/7/12 15:07
 */
public interface ISignService {

    /**
     * 签到
     *
     * @param cookie cookie
     * @return 签到结果
     */
    SignResultVO signIn(String cookie);

    /**
     * 获取用户信息
     *
     * @param cookie cookie
     * @return 用户信息
     */
    UserInfoVO getUserInfo(String cookie);

    /**
     * 获取连续签到天数
     *
     * @param cookie 用户cookie
     * @return 连续签到天数
     */
    Integer getSignStreak(String cookie);

    /**
     * 校验 Cookie 是否仍然有效（登录态）
     *
     * @param cookie cookie
     * @return true: 已登录；false: 已失效
     */
    boolean isCookieValid(String cookie);

    /**
     * 站点标识（hifiti / hifihi），用于日志与账号密码登录配置
     *
     * @return 站点标识
     */
    String getSiteName();

    /**
     * 执行完整的签到流程：先校验登录态，再签到，最后补全用户信息
     *
     * @param cookie cookie
     * @return 完整的签到结果
     */
    default SignResultVO signInWithUserInfo(String cookie) {
        // 关键修复：签到接口对未登录请求也返回 code=0，必须先校验 Cookie 登录态
        if (!isCookieValid(cookie)) {
            return new SignResultVO(401, "Cookie已失效，请重新获取 Cookie");
        }

        SignResultVO signResult = signIn(cookie);
        if (signResult.isSuccess()) {
            try {
                UserInfoVO userInfo = getUserInfo(cookie);
                if (userInfo.getUserName() != null) {
                    signResult.setUserName(userInfo.getUserName());
                }
                // 获取连续签到天数
                Integer signStreak = getSignStreak(cookie);
                if (signStreak != null) {
                    userInfo.setSignStreak(signStreak);
                }
                signResult.setUserInfo(userInfo);
            } catch (Exception e) {
                System.out.println("获取用户信息失败: " + e.getMessage());
            }
        }
        return signResult;
    }
}
