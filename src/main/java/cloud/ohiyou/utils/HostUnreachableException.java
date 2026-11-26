package cloud.ohiyou.utils;

import java.io.IOException;

/**
 * 表示"运行环境无法连接目标站点"的专用异常。
 *
 * <p>与普通 {@link IOException} 的区别在于语义：
 * 普通 IO 异常（如 HTTP 403、接口返回异常）说明"连上了但有问题"，属于真实失败；
 * 而本异常表示"TCP 层面根本连不上"，换 Cookie、重试都无效，
 * 属于运行环境限制（例如境外机房无法访问中国大陆服务器）。
 *
 * <p>上层据此决定是否把结果计入"签到失败"：
 * 计入会导致工作流每天变红却无从修复，不计入才不会掩盖真实问题。
 *
 * @author ohiyou
 */
public class HostUnreachableException extends IOException {

    private static final long serialVersionUID = 1L;

    public HostUnreachableException(String message) {
        super(message);
    }

    public HostUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
