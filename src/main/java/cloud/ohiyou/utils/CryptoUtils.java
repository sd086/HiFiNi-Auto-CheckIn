package cloud.ohiyou.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 通用工具类
 *
 * @author ohiyou
 */
public final class CryptoUtils {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CryptoUtils() {
    }

    /**
     * 计算 MD5（32 位小写），用于 Xiuno BBS 登录接口的 password 字段
     *
     * @param input 明文
     * @return 32 位小写 md5
     */
    public static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(HEX[(b >> 4) & 0x0f]).append(HEX[b & 0x0f]);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 计算失败", e);
        }
    }
}
