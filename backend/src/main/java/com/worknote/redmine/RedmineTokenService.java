package com.worknote.redmine;

import com.worknote.auth.UserRow;
import com.worknote.auth.totp.SecretCipher;
import com.worknote.setting.SettingService;
import com.worknote.vault.VaultException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;

@Service
public class RedmineTokenService {
    private final RedmineTokenMapper mapper;
    private final SecretCipher cipher;
    private final SettingService settings;
    private final RedmineClient client;
    private final Clock clock;

    public RedmineTokenService(RedmineTokenMapper mapper, SecretCipher cipher,
                               SettingService settings, RedmineClient client, Clock clock) {
        this.mapper = mapper; this.cipher = cipher; this.settings = settings;
        this.client = client; this.clock = clock;
    }

    public void setToken(UserRow user, String token) {
        if (!settings.redmineEnabled()) throw new RedmineException.NotFound("redmine_disabled");
        if (token == null || token.isBlank()) throw VaultException.invalid("토큰을 입력하세요");
        if (!cipher.available()) throw VaultException.invalid("암호화 키(WORKNOTE_2FA_KEY) 미구성");
        String base = settings.redmineBaseUrl();
        String login = client.fetchCurrentLogin(base, token);   // 무효 시 RedmineException.Auth
        String now = LocalDateTime.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        mapper.upsert(new RedmineTokenRow(user.id(), cipher.encrypt(token), login, now, now));
    }

    public boolean hasToken(String userId) { return mapper.find(userId) != null; }
    public RedmineTokenRow status(String userId) { return mapper.find(userId); }
    public void delete(String userId) { mapper.delete(userId); }

    public String tokenFor(String userId) {
        RedmineTokenRow r = mapper.find(userId);
        if (r == null) throw VaultException.conflict("redmine_token_missing");
        return cipher.decrypt(r.tokenEnc());
    }
}
