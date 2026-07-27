package com.gilbom.auth.mfa;

final class Base32Codec {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Base32Codec() {
    }

    static String encodeWithoutPadding(byte[] input) {
        if (input == null || input.length == 0) {
            throw new IllegalArgumentException("Base32 input must not be empty");
        }

        StringBuilder encoded =
                new StringBuilder((input.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsInBuffer = 0;
        for (byte value : input) {
            buffer = (buffer << 8) | (value & 0xff);
            bitsInBuffer += 8;
            while (bitsInBuffer >= 5) {
                encoded.append(ALPHABET[
                        (buffer >> (bitsInBuffer - 5)) & 0x1f
                ]);
                bitsInBuffer -= 5;
            }
        }
        if (bitsInBuffer > 0) {
            encoded.append(ALPHABET[
                    (buffer << (5 - bitsInBuffer)) & 0x1f
            ]);
        }
        return encoded.toString();
    }
}
