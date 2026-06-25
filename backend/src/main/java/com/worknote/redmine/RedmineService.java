package com.worknote.redmine;

import com.worknote.audit.AuditService;
import com.worknote.auth.UserRow;
import com.worknote.redmine.RedmineDtos.*;
import com.worknote.setting.SettingService;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RedmineService {
    private final RedmineTokenService tokens;
    private final RedmineClient client;
    private final SettingService settings;
    private final AuditService audit;

    public RedmineService(RedmineTokenService tokens, RedmineClient client,
                          SettingService settings, AuditService audit) {
        this.tokens = tokens; this.client = client; this.settings = settings; this.audit = audit;
    }

    private String base() {
        if (!settings.redmineEnabled()) throw new RedmineException.NotFound("redmine_disabled");
        String b = settings.redmineBaseUrl();
        if (b == null || b.isBlank()) throw new RedmineException.NotFound("redmine_disabled");
        return b;
    }

    public List<RedmineIssueSummary> search(UserRow user, RedmineQuery q) {
        return client.listIssues(base(), tokens.tokenFor(user.id()), q);
    }

    public RedmineIssueDetail detail(UserRow user, long id, String ip) {
        RedmineIssueDetail d = client.getIssue(base(), tokens.tokenFor(user.id()), id);
        audit.log(user, "redmine.import", "issue#" + id, ip);
        return d;
    }
}
