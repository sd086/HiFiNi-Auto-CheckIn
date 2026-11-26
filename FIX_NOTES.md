# HiFiNi-Auto-CheckIn 签到失效排查与修复说明

> 排查时间：2026-11-25
> 仓库：`https://github.com/sd086/HiFiNi-Auto-CheckIn`（你的 fork）

---

## 一、结论：签到"失效"不是站点坏了，而是两个问题叠加

### 1. 真正原因：Cookie 已过期（登录态失效）

站点 Cookie（`bbs_sid` / `bbs_token`）有效期约 **100 天**，过期后：

- `POST https://www.hifiti.com/sg_sign.htm` 依然返回 **HTTP 200**，body 为：
  ```json
  { "code": "0", "message": "请登录后再签到!" }
  ```
- 访问 `my.htm` 会被重定向到登录页，页面标题是 `用户登录`，页面里出现登录表单和「请登录后再签到」。

所以服务端本身运行正常（实测 `sg_sign.htm` 可达、`user-login.htm` 登录接口正常返回 JSON），**只是你配置的 Cookie 不再代表一个已登录用户**。

### 2. 放大器：程序把"未登录"当成了"签到成功"

这是明明失败了、GitHub Actions 却一直是绿色 ✔ 的原因：

| 位置 | 原代码行为 | 后果 |
| --- | --- | --- |
| `SignResultVO.code` 为 `Integer`，用 `JSON.parseObject` 反序列化 | `"code":"0"` 被解析为 `0` | 未登录（code 也是 0）与签到成功无法区分 |
| `SignResultCode.fromCode(0)` | 返回 `SUCCESS` | 「请登录后再签到!」被判为**成功** |
| `ResultPublisher` | 只要 `SUCCESS` 就 `successCount++` | 推送标题显示"自动签到成功" |
| `HifiniApplication.main` | 捕获异常只 `logger.error`，**不设置非 0 退出码** | 工作流永远绿色，静默失败 |
| `HifitiSignService` 用 `SIGN_URL` | `SIGN_URL` 是 `@Deprecated` 别名，值恰好还是 hifiti，暂未出问题 | 隐患：以后改别名会静默签到到错误站点 |
| `ISignService.signInWithUserInfo` | 只在最后补用户信息，**没有校验登录态** | 无法在使用前发现 Cookie 失效 |
| `CookieHandler.splitCookies` | `&` 结尾会产生空项 | 多账号配置末尾带 `&` 时多一个"Cookie格式无效"的失败项 |

**验证方式（可复现）**：用一个不存在的 `bbs_sid` 直接 POST `sg_sign.htm`，同样返回
`{"code":"0","message":"请登录后再签到!"}`，说明该接口对未登录请求也返回 code=0。

---

## 二、立即恢复签到（1 分钟）

1. 浏览器登录 <https://www.hifiti.com/>（HiFiHi 则 <https://hifihi.com/>）。
2. `F12` → Network → 刷新页面 → 点任意一个请求 → 请求头里复制完整 `Cookie`
   （确保包含 `bbs_sid=...` 和 `bbs_token=...`）。
3. 你的仓库 → `Settings` → `Secrets and variables` → `Actions` → 更新 `COOKIE`（HiFiHi 用 `HIFIHI_COOKIE`）。
4. `Actions` → `HiFINI-Auto-CheckIn` → `Run workflow` 手动跑一次确认。

> 建议同时完成下面的代码修复，否则下次 Cookie 过期你还是只会看到"绿色成功"。

---

## 三、代码修复清单

本次已在 `repo/` 目录生成修复后的完整源码，改动如下：

| 文件 | 改动 |
| --- | --- |
| `service/ISignService.java` | 新增 `isCookieValid()`、`getSiteName()`；`signInWithUserInfo()` **先校验登录态**，失效直接返回 `401 Cookie已失效` |
| `service/impl/HifitiSignService.java` | 新增 `isCookieValid()`（访问 `my.htm`，识别 `用户登录` 页面/用户名解析失败）与 `login(AccountConfig)`；改用 `HIFITI_SIGN_URL`，补齐 `Accept` 头 |
| `service/impl/HifihiSignService.java` | 新增 `isCookieValid()`；改用 `SignResponseParser` |
| `utils/SignResponseParser.java`（新增） | 集中解析签到响应：`code` 兼容字符串/数字；`message` 含「请登录/未登录」一律判为 `COOKIE_INVALID`；非 JSON（返回登录页）也判为失效 |
| `utils/CryptoUtils.java`（新增） | MD5（32 位小写），供账号密码登录使用 |
| `config/AccountConfig.java`（新增） | 可选环境变量 `HIFINI_ACCOUNT` 的解析（`邮箱:密码:站点`） |
| `config/EnvConfig.java` | 新增 `HIFINI_ACCOUNT`、`STRICT_FAIL` |
| `executor/SignTaskExecutor.java` | Cookie 失效且配置了账号密码时，自动重新登录并重试一次；结果日志打印具体 message |
| `handler/CookieHandler.java` | 兼容 `bbs_sid: xxx` 写法；`splitCookies` 过滤空项、支持换行分隔 |
| `vo/SignResultVO.java` | 移除 `code == 0` 的直接比较，统一走 `SignResultCode`；新增 `isAuthFailure()` |
| `constant/HifiniConstants.java` | 新增 `HIFITI_*` / `HIFIHI_*` / 登录 URL / `REGEX_LOGIN_PAGE` |
| `HifiniApplication.java` | 汇总失败数；`STRICT_FAIL=true` 时以退出码 1 结束，让 Actions 变红；输出 `EXIT_CODE` |
| `.github/workflows/HiFiNi-Auto-CheckIn.yml` | 新增 `HIFINI_ACCOUNT`、`STRICT_FAIL`；`permissions` 移到 job 外（原位置非法）；`cache: maven`；改用完整插件坐标；日志回显关键行 |
| `pom.xml` | 显式声明 `exec-maven-plugin 3.1.1`（原工作流用 `mvn exec:java` 但 pom 未声明该插件，Maven Central 返回 429 时会报 `No plugin found for prefix 'exec'` 而构建失败，见 11-25 10:04 那次运行） |
| `ScfHandler.java` / `AliyunFcHandler.java` | 改为调用 `runCheckIn()` 拿退出码，避免 `System.exit` 打断云函数收尾 |

### 关键代码（核心补丁）

```java
// ISignService#signInWithUserInfo
default SignResultVO signInWithUserInfo(String cookie) {
    if (!isCookieValid(cookie)) {
        return new SignResultVO(401, "Cookie已失效，请重新获取 Cookie");
    }
    SignResultVO signResult = signIn(cookie);
    ...
}
```

```java
// SignResponseParser#parse —— 关键：未登录也返回 code=0
if (isAuthMessage(message)) {          // "请登录后再签到!"
    code = SignResultCode.COOKIE_INVALID.getCode();
}
```

```java
// HifiniApplication —— 失败要让工作流变红
long failed = allResults.stream()
        .filter(r -> r.getSignResult() == null || !r.getSignResult().isSuccess())
        .count();
if (failed > 0 && strictFail) System.exit(1);
```

---

## 四、强烈建议：配置账号密码，彻底告别 Cookie 过期

在 `Settings → Secrets` 增加 `HIFINI_ACCOUNT`（可选，但推荐）：

```
格式：邮箱:密码:站点（多个用 & 分隔，站点可填 hifiti / hifihi / all）
user@mail.com:你的密码:hifiti
user@mail.com:你的密码:hifiti&user@mail.com:你的密码:hifihi
```

> 登录接口是 Xiuno BBS，`password` 需要 32 位小写 MD5，程序已自动处理；
> 登录成功后从 `Set-Cookie` 取 `bbs_sid` / `bbs_token` 再签到。
> 如果站点开启了验证码/人机校验，自动登录会失败，此时仍需手动更新 Cookie。

---

## 五、如何应用改动

`repo/` 是一个可用的完整源码快照（`pom.xml` + `src/` + `.github/workflows`）。两种方式：

**方式 A：覆盖到你本地 clone**
```bash
# 先备份
cp -r <你本地clone> <你本地clone>.bak
# 覆盖
cp -r repo/pom.xml repo/src repo/.github <你本地clone>/
cd <你本地clone>
git diff            # 确认改动
git add -A && git commit -m "fix: 校验登录态，修复未登录被判为签到成功导致的静默失败"
git push origin master
```

**方式 B：只挑关键文件**
至少要改 3 处才能止血：
1. `HifitiSignService.signIn()` 里判断 `message.contains("请登录")` → 返回 `401`；
2. `ISignService.signInWithUserInfo()` 开头加 `isCookieValid`；
3. `HifiniApplication.main()` 结尾有失败就以非 0 退出。

---

## 六、验证结果

| 检查项 | 结果 |
| --- | --- |
| `https://www.hifiti.com/` | 200，站点正常 |
| `POST /sg_sign.htm` 无 Cookie | 200，`{"code":"0","message":"请登录后再签到!"}` |
| `POST /sg_sign.htm` 伪造 Cookie | 200，同上（证明 code=0 不能作为成功依据） |
| `GET /my.htm` 无/无效 Cookie | 返回 `用户登录` 登录页（用户名正则不匹配） |
| `POST /user-login.htm` | 正常返回 JSON（`{"code":"email","message":"邮箱不存在"}`），登录链路可用 |
| 你的 fork Actions 最近 4 次运行 | 全部 `success`（正是静默失败的证据） |

> 注：本机当前无法启动子进程（javac/mvn 均返回 `0xC0000005`），因此改动未经本地编译验证；
> 代码严格保持 Java 8 语法与原有依赖版本，建议推送后先用 `workflow_dispatch` 手动跑一次确认。

---

## 七、补充修复（2026-11-26）：HiFiHi 反爬网关 + 环境不可达判定

### 1. `unexpected end of stream` 的真相：不是代码问题

现象：HiFiTi 签到成功，HiFiHi 报 `unexpected end of stream on https://hifihi.com/...`。

排查过程：

| 测试 | 本机（中国境内 IP） | GitHub Actions（Azure westus2，境外） |
| --- | --- | --- |
| 连续请求 `https://hifihi.com/` | **5/5 成功** | **0/4 成功** |
| 第一次握手拿 `guard` cookie | 成功，39 字节网关页 | 首次 `unexpected end of stream`，重试后全部 `connect timed out` |
| 带 `guardret` 二次请求 | 成功，拿到 27795 字节正常页面 | 无法进入该步骤 |

DNS 解析结果给出了直接答案：

| 域名 | 解析 IP | 归属 |
| --- | --- | --- |
| `hifihi.com` | `111.92.243.5` | **中国移动（福建）** |
| `hifiti.com` | `154.222.26.89` | 境外 |

**结论：`hifihi.com` 的服务器在中国大陆，对境外机房 IP 会直接丢弃 TCP 连接。**
换 Cookie、重试、改请求头都无效，这是网络位置问题。

### 2. 已做的代码改动

**a) 反爬网关握手（`GuardSolver`）**

hifihi 在 nginx 前置了 `/_guard/auto.js` 网关，缺 `guardret` cookie 时只返回 39 字节空壳页。
已从官方 JS 逐字还原算法（RC4 + Base64）：

```
key      = guard.substring(0, 8)
numPlain = parseInt(guard.substring(12), 10)   // 遇非数字截断
guardArg = numPlain * 2 + 16
guardret = base64(rc4(String(guardArg), key))
```

**已实测验证通过**：本机两次请求把 39 字节拦截页换成 27795 字节正常页面。

**b) 连接层加固**

- `OkHttpUtils.getNoReuseClient()`：hifihi 专用客户端，关闭连接复用（`ConnectionPool(0, ...)`），
  避免云机房网络复用已被中间设备掐断的"僵尸连接"。
- 连接超时收紧到 12 秒（原来 30 秒，跨境丢包时纯属白等）。
- 请求头补齐 `Sec-Fetch-*`、`Upgrade-Insecure-Requests` 等浏览器指纹字段。

**c) 请求重试（`GuardSolver.get` / `executeWithRetry`）**

对 `unexpected end of stream` / `connect timed out` / `connection reset` 做 2 次指数退避重试
（800ms、1600ms），签到 POST 同样支持重试。

**d) 关键区分：环境限制 ≠ 签到失败**

这是本次最重要的设计修正。新增 `SignResultCode.HOST_UNREACHABLE(502)`：

| 情况 | 结果码 | 是否算失败 | 是否让工作流变红 |
| --- | --- | --- | --- |
| TCP 根本连不上（connect timed out / 域名解析失败） | `HOST_UNREACHABLE(502)` | 否 | 否 |
| 服务器有响应但请求被拒（**HTTP 403** / 500） | `NETWORK_ERROR(500)` | **是** | **是** |
| Cookie 失效 | `COOKIE_INVALID(401)` | 是 | 是 |
| 签到成功 / 今日已签 | `0` / `-1` | 否 | 否 |

> ⚠️ 这处分界必须严格：曾经把 403 也归为"环境限制"，结果 HiFiTi 明明没签到成功，
> 工作流却显示绿色 —— 那正是最初要消灭的静默失败。现在 403 一律算失败。

**e) HiFiTi 的 403 风控重试**

站点偶尔对云机房 IP 返回 403（多为瞬时风控）。现在会换一个 UA、等待 1.2s/2.4s 重试，
最多 3 次；重试后仍 403 才判失败。实测加此逻辑后，连续两轮运行 HiFiTi 均恢复正常签到。

**f) 推送标题更准确**

- 全部成功 → `自动签到成功`
- 有账号因网络环境跳过、其余成功 → `自动签到部分完成（N 个站点因网络环境受限已跳过）`
- 有真实失败 → `自动签到失败`

### 3. 最终验证（run #45771094743，2026-11-26 14:51）

```
HiFiTi Cookie 有效，用户: Mocha86
HiFiTi 签到响应: {"code":"-1","message":"今天已经签过啦！"}    ← 连续签到 3 天
HiFiHi 触发 2 次重试后判定: 运行环境无法连接站点 (connect timed out)
推送结果: 自动签到部分完成（1 个站点因网络环境受限已跳过） | 成功率 1/2
EXIT_CODE=0 → 工作流 success
```

### 4. 如果你希望 HiFiHi 也真正签到成功

GitHub Actions 做不到（境外 IP 被墙）。可选方案：

1. **云函数固定 IP**（项目已内置 `ScfHandler` / `AliyunFcHandler` 入口）——腾讯云函数 / 阿里云函数，
   选境内地域，改成 HTTP 触发 + 定时触发器。
2. **自建设备**：家里的 NAS / 树莓派 / 常开的路由器，用 `mvn exec:java` 跑定时任务。
3. **境内 VPS**：任意一台国内服务器加个 cron。

前两种不需要改代码，`STRICT_FAIL` 保持默认即可。

