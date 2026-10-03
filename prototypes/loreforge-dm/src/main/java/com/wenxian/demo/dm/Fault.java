package com.wenxian.demo.dm;

/** 可直接呈现给试玩者的失败；技术异常不会原样写入HTTP响应。 */
final class Fault extends RuntimeException {
    final int status;
    Fault(int status, String message) { super(message); this.status=status; }
}
