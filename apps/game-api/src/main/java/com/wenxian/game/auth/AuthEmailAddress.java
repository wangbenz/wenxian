package com.wenxian.game.auth;

import com.wenxian.game.api.GameApiException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 账号认证域统一使用的邮箱规范化与格式校验。 */
final class AuthEmailAddress {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private AuthEmailAddress() { }

    static String normalize(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 255 || !EMAIL_PATTERN.matcher(email).matches()) {
            throw GameApiException.of(HttpStatus.BAD_REQUEST, "EMAIL_INVALID", "INPUT",
                    "邮箱格式不正确", "请输入可以长期接收邮件的完整邮箱地址。",
                    List.of("检查邮箱中是否包含 @ 和正确后缀"));
        }
        return email;
    }
}
