package com.wenxian.game.mail;

/**
 * 邮件供应商没有确认发送成功。
 *
 * <p>异常只暴露稳定、非敏感的内部错误码，不携带供应商原始异常，防止日志意外写入请求或响应细节。</p>
 */
public final class MailDeliveryException extends RuntimeException {
    public static final String ERROR_CODE = "MAIL_PROVIDER_DELIVERY_FAILED";

    public MailDeliveryException() {
        super(ERROR_CODE);
    }
}
