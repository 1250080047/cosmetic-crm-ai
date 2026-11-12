package com.example.PhanTichAI.service;

import java.io.IOException;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiInternalKey {
    private final String key;
    public AiInternalKey(@Value("${ai.internal-key-file:../.runtime/ai-internal.key}") String filename) throws IOException {
        String configured = System.getenv("AI_INTERNAL_KEY");
        if (configured != null && !configured.isBlank()) { key = configured.trim(); return; }
        Path path = Path.of(filename).toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            try { Files.writeString(path, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), StandardOpenOption.CREATE_NEW); }
            catch (FileAlreadyExistsException ignored) { /* Another startup created the shared key. */ }
        }
        key = Files.readString(path).trim();
        if (key.length() < 32) throw new IOException("AI internal key must contain at least 32 characters");
    }
    public String value() { return key; }
}
