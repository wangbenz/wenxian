package com.wenxian.game.mail;

import com.aliyun.dm20151123.models.SingleSendMailRequest;

/**
 * 阿里云 SDK 的最小调用面，隔离生成 SDK，便于在不访问外网的测试中核对请求内容。
 */
@FunctionalInterface
interface AliyunSingleSendClient {
    void send(SingleSendMailRequest request) throws Exception;
}
