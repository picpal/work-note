package com.worknote.redmine;

public sealed class RedmineException extends RuntimeException
    permits RedmineException.Auth, RedmineException.NotFound, RedmineException.Upstream {
    RedmineException(String m) { super(m); }
    public static final class Auth extends RedmineException { public Auth(String m){super(m);} }       // 401/403 키 무효
    public static final class NotFound extends RedmineException { public NotFound(String m){super(m);} } // 404 없음/권한없음
    public static final class Upstream extends RedmineException { public Upstream(String m){super(m);} } // 5xx/timeout/파싱
}
