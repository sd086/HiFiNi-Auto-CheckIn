package cloud.ohiyou.config;

import lombok.Getter;

@Getter
public class EnvConfig {

    private final static EnvConfig INSTANCE = new EnvConfig();
    private final String serverChan;
    private final String wxworkrobotkey;
    private final String wxWorkRobotMessageType;
    private final String dingTalkRobotKey;
    private final String cookie;
    private final String hifihiCookie;
    private final String hifiniAccount;
    private final String tgChatId;
    private final String tgBotToken;
    private final String gotifyUrl;
    private final String gotifyAppToken;
    private final boolean strictFail;


    private EnvConfig() {
        cookie = System.getenv("COOKIE");
        hifihiCookie = System.getenv("HIFIHI_COOKIE");
        hifiniAccount = System.getenv("HIFINI_ACCOUNT");
        serverChan = System.getenv("SERVER_CHAN");
        wxworkrobotkey = System.getenv("WXWORK_WEBHOOK");
        wxWorkRobotMessageType = System.getenv().getOrDefault("WXWORK_MSG_TYPE", "markdown");
        dingTalkRobotKey = System.getenv("DINGTALK_WEBHOOK");
        tgBotToken = System.getenv("TG_BOT_TOKEN");
        tgChatId = System.getenv("TG_CHAT_ID");
        gotifyUrl = System.getenv("GOTIFY_URL");
        gotifyAppToken = System.getenv("GOTIFY_APP_TOKEN");
        strictFail = Boolean.parseBoolean(
                System.getenv().getOrDefault("STRICT_FAIL", "true").trim());
    }

    public static EnvConfig get() {
        return INSTANCE;
    }


    public String getWXWorkMessageType() {
        return wxWorkRobotMessageType;
    }
}
