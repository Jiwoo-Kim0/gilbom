package com.gilbom.common.web;

import java.io.IOException;

/**
 * Content-Length가 없거나 실제 본문보다 작게 선언된 요청을 읽는 도중 크기 상한을 넘었음을 나타낸다.
 */
public final class RequestBodyTooLargeException extends IOException {

    public RequestBodyTooLargeException() {
        super("Request body exceeds the configured maximum size");
    }

    public static boolean isCausedBy(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof RequestBodyTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
