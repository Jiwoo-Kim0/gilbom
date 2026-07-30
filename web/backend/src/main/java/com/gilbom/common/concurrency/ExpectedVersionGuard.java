package com.gilbom.common.concurrency;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

/**
 * Compares an API client's last observed version with the locked current
 * version. The public exception intentionally carries no resource snapshot.
 */
@Component
public class ExpectedVersionGuard {

    public void requireMatch(
            int expectedVersionNo,
            int currentVersionNo,
            ErrorCode conflictCode
    ) {
        Objects.requireNonNull(
                conflictCode,
                "conflictCode must not be null");
        if (conflictCode.httpStatus() != HttpStatus.CONFLICT) {
            throw new IllegalArgumentException(
                    "conflictCode must use HTTP 409");
        }
        if (expectedVersionNo < 1
                || currentVersionNo < 1
                || expectedVersionNo != currentVersionNo) {
            throw new BusinessException(conflictCode);
        }
    }
}
