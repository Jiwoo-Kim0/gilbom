package com.gilbom.auth.mfa;

final class TotpComputationException extends RuntimeException {

    TotpComputationException() {
        super("TOTP computation failed");
    }
}
