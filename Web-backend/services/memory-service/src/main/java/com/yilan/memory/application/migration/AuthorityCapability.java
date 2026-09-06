package com.yilan.memory.application.migration;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Bounded HMAC capability transported in the existing extension capability list. */
public record AuthorityCapability(String schemaVersion, AuthorityMode mode, long epoch, long watermark) {
    private static final String PREFIX = "authority-capability/v1";
    private static final Pattern TOKEN = Pattern.compile(
            "^authority-capability/v1;schema=(v1);mode=(LOCAL|SHADOW|CUTOVER_PREPARED|REMOTE);"
                    + "epoch=([0-9]{1,18});watermark=([0-9]{1,18});sig=([0-9a-f]{64})$");

    public AuthorityCapability {
        if (!"v1".equals(schemaVersion) || mode == null || epoch < 0 || watermark < 0) {
            throw new IllegalArgumentException("authority capability");
        }
    }

    public String issue(String key) {
        if (!signerAvailable(key)) throw new IllegalStateException("authority signer unavailable");
        return canonical() + ";sig=" + hmac(canonical(), key);
    }

    public static Optional<AuthorityCapability> verify(String token, String key) {
        if (token == null || token.length() > 256 || !signerAvailable(key)) return Optional.empty();
        var match = TOKEN.matcher(token);
        if (!match.matches()) return Optional.empty();
        var canonical = PREFIX + ";schema=" + match.group(1) + ";mode=" + match.group(2)
                + ";epoch=" + match.group(3) + ";watermark=" + match.group(4);
        if (!java.security.MessageDigest.isEqual(
                HexFormat.of().parseHex(match.group(5)), HexFormat.of().parseHex(hmac(canonical, key)))) {
            return Optional.empty();
        }
        try {
            return Optional.of(new AuthorityCapability(match.group(1), AuthorityMode.valueOf(match.group(2)),
                    Long.parseLong(match.group(3)), Long.parseLong(match.group(4))));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public static boolean signerAvailable(String key) {
        if (key == null) return false;
        var utf8Length = key.getBytes(StandardCharsets.UTF_8).length;
        return utf8Length >= 16 && utf8Length <= 512;
    }

    private String canonical() {
        return PREFIX + ";schema=" + schemaVersion + ";mode=" + mode + ";epoch=" + epoch + ";watermark=" + watermark;
    }

    private static String hmac(String value, String key) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HMAC unavailable", impossible);
        }
    }
}
