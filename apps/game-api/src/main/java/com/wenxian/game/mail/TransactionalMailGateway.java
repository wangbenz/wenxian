package com.wenxian.game.mail;

import java.util.Objects;

/**
 * 事务邮件的外部发送端口。
 *
 * <p>调用方负责生成并校验业务内容；实现只把已定稿的单收件人纯文本邮件交给供应商。
 * 实现不得记录收件地址、正文、凭证或供应商原始响应。供应商拒绝、超时或结果不确定时抛出
 * {@link MailDeliveryException}，由上层业务决定是否以及如何重试。</p>
 */
public interface TransactionalMailGateway {
    void send(Message message);

    /**
     * 单封事务邮件。当前只允许纯文本，避免验证码等安全内容被模板或追踪脚本二次处理。
     */
    record Message(String recipientEmail, String subject, String plainText) {
        public Message {
            recipientEmail = requireText(recipientEmail, "recipientEmail");
            subject = requireText(subject, "subject");
            plainText = requireText(plainText, "plainText");
            rejectMultiRecipientOrHeaderBreak(recipientEmail, "recipientEmail");
            rejectHeaderBreak(subject, "subject");
        }

        private static String requireText(String value, String field) {
            Objects.requireNonNull(value, field + " must not be null");
            String normalized = value.strip();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return normalized;
        }

        private static void rejectMultiRecipientOrHeaderBreak(String value, String field) {
            if (value.indexOf(',') >= 0 || value.indexOf(';') >= 0) {
                throw new IllegalArgumentException(field + " must contain exactly one address");
            }
            rejectHeaderBreak(value, field);
        }

        private static void rejectHeaderBreak(String value, String field) {
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException(field + " must not contain line breaks");
            }
        }
    }
}
