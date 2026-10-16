package io.github.brick.web.auth;
import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordHasher {
    private final SecureRandom random;
    public PasswordHasher(SecureRandom random) { this.random = random; }
    public String hash(String raw) {
        CredentialRules.validatePassword(raw);
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return "pbkdf2-sha256$600000$" + Base64.getEncoder().encodeToString(salt)
            + "$" + Base64.getEncoder().encodeToString(derive(raw, salt));
    }
    public boolean matches(String raw, String encoded) {
        try {
            CredentialRules.validatePassword(raw);
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256") || !parts[1].equals("600000")) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]), expected = Base64.getDecoder().decode(parts[3]);
            if (salt.length != 16 || expected.length != 32) return false;
            return MessageDigest.isEqual(expected, derive(raw, salt));
        } catch (IllegalArgumentException | io.github.brick.web.api.GameException e) { return false; }
    }
    private byte[] derive(String raw, byte[] salt) {
        PBEKeySpec key = new PBEKeySpec(raw.toCharArray(), salt, 600000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(key).getEncoded(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("Password algorithm unavailable"); }
        finally { key.clearPassword(); }
    }
}
