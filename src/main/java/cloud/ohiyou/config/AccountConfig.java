package cloud.ohiyou.config;

/**
 * 账号密码登录配置（可选）
 *
 * <p>Cookie 会过期，配置账号密码后，若 Cookie 失效会自动重新登录并签到，
 * 避免"因为没更新 Cookie 而长期静默失败"。</p>
 *
 * <p>环境变量 HIFINI_ACCOUNT 格式（多个用 & 分隔）：</p>
 * <pre>
 *   邮箱:密码:站点
 *   user@mail.com:pass:hifiti
 *   user@mail.com:pass:hifiti&amp;user@mail.com:pass:hifihi
 * </pre>
 *
 * @author ohiyou
 */
public class AccountConfig {

    private final String email;
    private final String password;
    private final String site;

    public AccountConfig(String email, String password, String site) {
        this.email = email;
        this.password = password;
        this.site = site;
    }

    /**
     * 解析单个账号配置，格式不合法返回 null
     *
     * @param raw 原始配置
     * @return 账号配置
     */
    public static AccountConfig parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String[] parts = raw.trim().split(":");
        if (parts.length < 2) {
            return null;
        }
        String email = parts[0].trim();
        String password = parts[1].trim();
        String site = parts.length >= 3 ? parts[2].trim().toLowerCase() : "all";
        if (email.isEmpty() || password.isEmpty()) {
            return null;
        }
        return new AccountConfig(email, password, site);
    }

    public String getEmail() {
        return email;
    }

    public String getPassword() {
        return password;
    }

    public String getSite() {
        return site;
    }

    /**
     * 判断该账号是否用于指定站点
     *
     * @param siteName 站点标识 hifiti / hifihi
     * @return 是否适用
     */
    public boolean matches(String siteName) {
        return "all".equals(site) || site.equalsIgnoreCase(siteName);
    }

    @Override
    public String toString() {
        return "AccountConfig{email='" + email + "', site='" + site + "'}";
    }
}
