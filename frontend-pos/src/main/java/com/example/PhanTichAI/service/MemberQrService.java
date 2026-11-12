package com.example.PhanTichAI.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The member card's QR code changes every 60 seconds, like an e-wallet:
 * {@code CM1.<customer code>.<minute number>.<signature>}. The signature is an HMAC-SHA256 with a secret key that
 * only the server knows, so nobody can make a valid code for another customer, and a photo of an old code stops
 * working after at most two minutes. The cashier can still type the customer code or phone number by hand.
 */
@Service
public class MemberQrService {
    public static final int STEP_SECONDS = 60;
    private static final Pattern TOKEN = Pattern.compile("CM1\\.(KH\\d{1,10})\\.(\\d{1,12})\\.([0-9A-F]{20})");
    private final byte[] key;
    private final Clock clock;

    public enum Status { VALID, EXPIRED, INVALID }
    public record Token(String value, long expiresAtEpochSecond, int secondsLeft) {}
    public record Check(Status status, String customerCode) {
        public String message() {
            return switch (status) {
                case VALID -> "";
                case EXPIRED -> "Mã QR đã hết hạn (mã đổi mỗi 60 giây). Nhờ khách mở lại thẻ thành viên trên điện thoại rồi quét lại.";
                case INVALID -> "Mã QR không hợp lệ. Nhờ khách mở thẻ thành viên trên trang của cửa hàng, hoặc tra cứu bằng số điện thoại.";
            };
        }
    }

    @Autowired
    public MemberQrService(@Value("${member.qr-key-file:../.runtime/member-qr.key}") String filename) throws IOException {
        this(loadKey(filename), Clock.systemUTC());
    }

    public MemberQrService(byte[] key, Clock clock) {
        if (key.length < 32) throw new IllegalArgumentException("Member QR key must have at least 32 bytes");
        this.key = key.clone();
        this.clock = clock;
    }

    private static byte[] loadKey(String filename) throws IOException {
        String configured = System.getenv("MEMBER_QR_KEY");
        if (configured != null && !configured.isBlank()) return configured.trim().getBytes(StandardCharsets.UTF_8);
        Path path = Path.of(filename).toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            try { Files.writeString(path, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), StandardOpenOption.CREATE_NEW); }
            catch (FileAlreadyExistsException ignored) { /* created by a parallel start */ }
        }
        return Files.readString(path).trim().getBytes(StandardCharsets.UTF_8);
    }

    public static boolean looksLikeToken(String value) { return value != null && value.toUpperCase(Locale.ROOT).startsWith("CM1."); }

    public Token issue(String customerCode) {
        long now = clock.instant().getEpochSecond(), step = now / STEP_SECONDS, expires = (step + 1) * STEP_SECONDS;
        return new Token("CM1." + customerCode + "." + step + "." + signature(customerCode, step), expires, (int) (expires - now));
    }

    /** Valid during the minute it was made and the next one, so a code shown just before it changes still scans. */
    public Check verify(String value) {
        Matcher match = TOKEN.matcher(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        if (!match.matches()) return new Check(Status.INVALID, null);
        String code = match.group(1);
        long step = Long.parseLong(match.group(2)), current = clock.instant().getEpochSecond() / STEP_SECONDS;
        boolean signed = MessageDigest.isEqual(signature(code, step).getBytes(StandardCharsets.US_ASCII),
                match.group(3).getBytes(StandardCharsets.US_ASCII));
        if (!signed || step > current) return new Check(Status.INVALID, null);
        return current - step <= 1 ? new Check(Status.VALID, code) : new Check(Status.EXPIRED, code);
    }

    private String signature(String customerCode, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(("CM1|" + customerCode + "|" + step).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest, 0, 10);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HmacSHA256 is not available", error);
        }
    }
}
