package com.worknote.redmine;

import com.worknote.auth.AuthException;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import com.worknote.redmine.RedmineDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/redmine")
public class RedmineController {
    private final RedmineService service;
    public RedmineController(RedmineService service) { this.service = service; }

    public record IssueListResponse(List<RedmineIssueSummary> issues) {}

    private UserRow me(HttpServletRequest http) {
        UserRow u = (UserRow) http.getAttribute(AuthFilter.CURRENT_USER);
        if (u == null) throw AuthException.forbidden("로그인 상태에서만 가능합니다");
        return u;
    }

    @GetMapping("/issues")
    public IssueListResponse issues(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "false") boolean assignedToMe,
            @RequestParam(required = false) String statusId,
            @RequestParam(required = false) String projectId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "25") int limit,
            HttpServletRequest http) {
        return new IssueListResponse(service.search(me(http),
                new RedmineQuery(query, assignedToMe, statusId, projectId, offset, limit)));
    }

    @GetMapping("/issues/{id}")
    public RedmineIssueDetail issue(@PathVariable long id, HttpServletRequest http) {
        return service.detail(me(http), id, http.getRemoteAddr());
    }
}
