package com.wenxian.game.mail;

import com.aliyun.dm20151123.models.SingleSendMailRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AliyunDirectMailGatewayTest {
    @Test
    void mapsPlainTextTransactionMailToSingleSendRequest() {
        AtomicReference<SingleSendMailRequest> captured = new AtomicReference<>();
        AliyunDirectMailGateway gateway = new AliyunDirectMailGateway(
                captured::set,
                "no-reply@notice.wenxianlu.cn",
                "问仙录"
        );

        gateway.send(new TransactionalMailGateway.Message(
                "player@example.com",
                "问仙录验证码",
                "验证码：123456"
        ));

        SingleSendMailRequest request = captured.get();
        assertThat(request.getAccountName()).isEqualTo("no-reply@notice.wenxianlu.cn");
        assertThat(request.getAddressType()).isEqualTo(1);
        assertThat(request.getReplyToAddress()).isFalse();
        assertThat(request.getToAddress()).isEqualTo("player@example.com");
        assertThat(request.getSubject()).isEqualTo("问仙录验证码");
        assertThat(request.getTextBody()).isEqualTo("验证码：123456");
        assertThat(request.getHtmlBody()).isNull();
        assertThat(request.getFromAlias()).isEqualTo("问仙录");
        assertThat(request.getClickTrace()).isEqualTo("0");
    }

    @Test
    void hidesProviderDetailsWhenDeliveryFails() {
        AliyunDirectMailGateway gateway = new AliyunDirectMailGateway(
                request -> {
                    throw new IllegalStateException("provider response contains player@example.com and body");
                },
                "no-reply@notice.wenxianlu.cn",
                "问仙录"
        );

        assertThatThrownBy(() -> gateway.send(new TransactionalMailGateway.Message(
                "player@example.com",
                "secret subject",
                "secret body"
        )))
                .isInstanceOf(MailDeliveryException.class)
                .hasMessage(MailDeliveryException.ERROR_CODE)
                .hasNoCause();
    }

    @Test
    void rejectsBlankMessageFieldsBeforeCallingProvider() {
        assertThatThrownBy(() -> new TransactionalMailGateway.Message(" ", "subject", "body"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("recipientEmail must not be blank");
    }

    @Test
    void rejectsMultipleRecipientsAndHeaderLineBreaks() {
        assertThatThrownBy(() -> new TransactionalMailGateway.Message(
                "one@example.com,two@example.com",
                "subject",
                "body"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("recipientEmail must contain exactly one address");

        assertThatThrownBy(() -> new TransactionalMailGateway.Message(
                "one@example.com",
                "subject\r\nInjected: value",
                "body"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("subject must not contain line breaks");
    }
}
