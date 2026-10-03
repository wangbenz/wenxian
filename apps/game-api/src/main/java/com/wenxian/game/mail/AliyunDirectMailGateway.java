package com.wenxian.game.mail;

import com.aliyun.dm20151123.models.SingleSendMailRequest;

/**
 * 使用阿里云邮件推送 SingleSendMail 接口发送单收件人事务邮件。
 *
 * <p>该适配器不重试、不记录邮件内容，也不把供应商异常继续向外传播。调用结果不确定时统一失败，
 * 由验证码等上层业务依据自身幂等和频率规则处理。</p>
 */
final class AliyunDirectMailGateway implements TransactionalMailGateway {
    private static final int TRIGGER_MAIL_ADDRESS_TYPE = 1;

    private final AliyunSingleSendClient client;
    private final String accountName;
    private final String fromAlias;

    AliyunDirectMailGateway(AliyunSingleSendClient client, String accountName, String fromAlias) {
        this.client = client;
        this.accountName = requireConfigured(accountName, "game.mail.aliyun.account-name");
        this.fromAlias = requireConfigured(fromAlias, "game.mail.aliyun.from-alias");
    }

    @Override
    public void send(Message message) {
        SingleSendMailRequest request = new SingleSendMailRequest()
                .setAccountName(accountName)
                .setAddressType(TRIGGER_MAIL_ADDRESS_TYPE)
                .setReplyToAddress(false)
                .setToAddress(message.recipientEmail())
                .setSubject(message.subject())
                .setTextBody(message.plainText())
                .setFromAlias(fromAlias)
                .setClickTrace("0");
        try {
            client.send(request);
        } catch (Exception ignored) {
            throw new MailDeliveryException();
        }
    }

    private static String requireConfigured(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must be configured when mail is enabled");
        }
        return value.strip();
    }
}
