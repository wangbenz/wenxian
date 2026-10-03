package com.wenxian.game.account;

import java.time.Instant;

/**
 * 账号删除预通知的外部发送端口。
 *
 * <p>实现只能把已定稿的标题和纯文本正文交给邮件供应商，不得记录收件地址、正文或供应商原始响应。
 * noticeId 是发送幂等键；供应商超时或结果不确定时必须抛出异常，由留存服务按同一键重试。</p>
 */
public interface AccountDeletionNoticeGateway {
    void send(NoticeMessage message);

    record NoticeMessage(String noticeId, String recipientEmail, String subject, String plainText,
                         Instant scheduledDeletionAt) { }
}
