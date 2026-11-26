package cloud.ohiyou;

import cloud.ohiyou.config.AccountConfig;
import cloud.ohiyou.config.EnvConfig;
import cloud.ohiyou.executor.SignTaskExecutor;
import cloud.ohiyou.factory.PushStrategyFactory;
import cloud.ohiyou.handler.CookieHandler;
import cloud.ohiyou.handler.ResultPublisher;
import cloud.ohiyou.service.ISignService;
import cloud.ohiyou.service.impl.HifihiSignService;
import cloud.ohiyou.service.impl.HifitiSignService;
import cloud.ohiyou.service.push.IMessagePushStrategy;
import cloud.ohiyou.utils.OkHttpUtils;
import cloud.ohiyou.vo.CookieSignResult;
import cloud.ohiyou.vo.SignResultVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HiFiNi 自动签到应用入口
 * 支持 HiFiTi 和 HiFiHi 两个站点的签到
 *
 * @author ohiyou
 */
public class HifiniApplication {

    private static final Logger logger = LoggerFactory.getLogger(HifiniApplication.class);

    /**
     * 退出码常量：GitHub Actions 里非 0 退出码会让工作流变红，
     * 避免出现"日志一片绿、其实根本没签到成功"的静默失败。
     */
    private static final int EXIT_OK = 0;
    private static final int EXIT_FAIL = 1;

    public static void main(String[] args) {
        int exitCode = runCheckIn();
        // 仅在 GitHub Actions / Docker 等独立进程场景下生效。
        // 云函数（ScfHandler / AliyunFcHandler）复用 main，由 STRICT_FAIL=false 关闭退出，
        // 避免 System.exit 中断函数运行时的收尾流程。
        if (exitCode != EXIT_OK) {
            System.exit(exitCode);
        }
    }

    public static int runCheckIn() {
        logger.info("自动签到任务开始");

        List<CookieSignResult> allResults = new ArrayList<>();
        CookieHandler cookieHandler = new CookieHandler();
        SignTaskExecutor executor = new SignTaskExecutor();
        boolean strictFail = true;

        try {
            EnvConfig config = EnvConfig.get();
            strictFail = config.isStrictFail();

            Map<String, AccountConfig> accounts = parseAccounts(config.getHifiniAccount());

            // 执行 HiFiTi 签到
            String hifitiCookies = config.getCookie();
            if (hifitiCookies != null && !hifitiCookies.trim().isEmpty()) {
                logger.info("===== HiFiTi 站点签到开始 =====");
                List<CookieSignResult> hifitiResults = executeSignIn(
                        hifitiCookies, new HifitiSignService(), cookieHandler, executor, "HiFiTi",
                        accounts.get(cloud.ohiyou.constant.HifiniConstants.SITE_HIFITI));
                allResults.addAll(hifitiResults);
            } else {
                logger.info("未设置 HiFiTi Cookie，跳过该站点签到");
            }

            // 执行 HiFiHi 签到
            String hifihiCookies = config.getHifihiCookie();
            if (hifihiCookies != null && !hifihiCookies.trim().isEmpty()) {
                logger.info("===== HiFiHi 站点签到开始 =====");
                List<CookieSignResult> hifihiResults = executeSignIn(
                        hifihiCookies, new HifihiSignService(), cookieHandler, executor, "HiFiHi",
                        accounts.get(cloud.ohiyou.constant.HifiniConstants.SITE_HIFIHI));
                allResults.addAll(hifihiResults);
            } else {
                logger.info("未设置 HiFiHi Cookie，跳过该站点签到");
            }

            // 检查是否有任何签到任务
            if (allResults.isEmpty()) {
                logger.warn("未配置任何站点的 Cookie，无签到任务执行");
                return exitWithError(strictFail, EXIT_FAIL);
            }

            // 发布汇总结果
            List<IMessagePushStrategy> strategies = PushStrategyFactory.createStrategies();
            ResultPublisher publisher = new ResultPublisher(strategies);
            publisher.publish(allResults);

            // 统计口径区分三类，避免"环境不可达"被当成"签到失败"：
            //   失败   —— 业务问题（Cookie 失效、接口报错），需要人处理 → 工作流变红
            //   受限   —— 网络/环境不可达，重试与换 Cookie 都无效 → 仅告警，不变红
            //   成功   —— 签到成功或今日已签到
            long failed = 0;
            long blocked = 0;
            for (CookieSignResult r : allResults) {
                SignResultVO sr = r.getSignResult();
                if (sr == null) {
                    failed++;
                    continue;
                }
                if (sr.isSuccess()) {
                    continue;
                }
                if (sr.getResultCode().isEnvironmentBlocked()) {
                    blocked++;
                    continue;
                }
                failed++;
            }

            if (blocked > 0) {
                logger.warn("有 {} 个账号因运行环境限制未能签到（通常是出口 IP 无法访问站点，"
                        + "非 Cookie 问题，重试无效）。如日志所示的目标站点位于中国大陆时，"
                        + "请改用境内网络或云函数固定 IP 运行。", blocked);
            }

            logger.info("EXIT_CODE={}", failed > 0 ? EXIT_FAIL : EXIT_OK);
            if (failed > 0 && strictFail) {
                logger.error("有 {} 个账号签到失败，工作流标记为失败", failed);
                return EXIT_FAIL;
            }
            return EXIT_OK;
        } catch (Exception e) {
            logger.error("签到任务执行失败: {}", e.getMessage(), e);
            return exitWithError(strictFail, EXIT_FAIL);
        } finally {
            executor.shutdown();
            OkHttpUtils.shutdown();
            logger.info("自动签到任务完成");
        }
    }

    /**
     * 记录退出码并返回；STRICT_FAIL=false 时统一返回 0（保持旧行为，不影响云函数）
     */
    private static int exitWithError(boolean strictFail, int code) {
        logger.info("EXIT_CODE={}", code);
        return strictFail ? code : EXIT_OK;
    }

    /**
     * 解析账号密码配置（HIFINI_ACCOUNT），按站点归类
     */
    private static Map<String, AccountConfig> parseAccounts(String raw) {
        Map<String, AccountConfig> map = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) {
            return map;
        }
        for (String item : raw.split("[&\\r\\n]+")) {
            AccountConfig account = AccountConfig.parse(item);
            if (account == null) {
                logger.warn("HIFINI_ACCOUNT 配置项格式不正确，已忽略: {}", item);
                continue;
            }
            if (!map.containsKey(account.getSite())) {
                map.put(account.getSite(), account);
            }
        }
        return map;
    }

    /**
     * 执行指定站点的签到任务
     *
     * @param cookies       cookie字符串
     * @param signService   签到服务
     * @param cookieHandler cookie处理器
     * @param executor      任务执行器
     * @param siteName      站点名称
     * @param account       账号密码兜底配置，可为 null
     * @return 签到结果列表
     */
    private static List<CookieSignResult> executeSignIn(
            String cookies,
            ISignService signService,
            CookieHandler cookieHandler,
            SignTaskExecutor executor,
            String siteName,
            AccountConfig account) {

        String[] cookieArray = cookieHandler.splitCookies(cookies);
        logger.info("{}: 检测到 {} 个cookie，开始签到", siteName, cookieArray.length);

        List<CookieSignResult> results = executor.executeSignTasks(
                cookieArray, signService, cookieHandler, account);

        // 为结果添加站点标识
        for (CookieSignResult result : results) {
            if (result.getSignResult() != null) {
                String originalUserName = result.getSignResult().getUserName();
                result.getSignResult().setUserName("[" + siteName + "] " +
                        (originalUserName != null ? originalUserName : "未知用户"));
            }
        }

        logger.info("{}: 签到任务完成，共 {} 个结果", siteName, results.size());
        return results;
    }
}
