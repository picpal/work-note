package com.worknote.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * M-6 — 기동 시 DB 부모 디렉토리·업로드 루트를 700, DB 파일을 600으로 맞춘다.
 *
 * <p><b>패스가 둘인 이유(둘 중 하나만 남기면 각각 이런 구멍이 생긴다).</b>
 * <ul>
 *   <li>전반 = {@link BeanFactoryPostProcessor}. DataSource·Flyway가 DB 파일을 만들기 <b>전</b>에 실행돼야
 *       처음부터 700 디렉토리 안에서 파일이 생긴다. 디렉토리 생성·검증과 <b>기존</b> DB 600은 여기서 끝난다.
 *       (그래서 {@code @Value} 대신 {@link EnvironmentAware} — BFPP는 일반 BeanPostProcessor보다 먼저 만들어진다)</li>
 *   <li>후반 = {@link ApplicationStartedEvent}. 전반 패스 시점에 <b>신규 설치는 DB 파일이 아직 없다</b> —
 *       {@code ensureFile}은 없는 파일에 아무것도 하지 않으므로, 그 뒤 SQLite가 프로세스 umask(보통 022)로
 *       만든 파일은 644로 남는다. 700 부모가 막아주긴 하지만 "DB 파일은 600"이라는 문서화된 불변식이 거짓이 된다.
 *       스키마 생성은 빈 초기화 중에 끝나므로 이 이벤트면 충분히 늦다.</li>
 * </ul>
 *
 * <p>server 모드에서 저장소가 소유자 전용임을 확인하지 못하면 기동을 세운다. 공용 서버에서 DB가 다른 계정에
 * 읽히는 상태로 조용히 뜨는 것보다 안 뜨는 게 낫다. local 모드(개인 PC·무인증)는 WARN만.
 * 후반 패스도 같은 정책이다 — 같은 종류의 문제를 언제 발견했느냐로 결론이 갈리면 게이트가 아니다.
 * (다만 후반 패스는 포트가 이미 열린 뒤라 "거부"가 아니라 "기동 직후 중단"이다. 컨텍스트가 닫히며 포트도 닫힌다)
 *
 * <p><b>탈출구</b> {@code worknote.storage.strict=false}(env {@code WORKNOTE_STORAGE_STRICT}). 기본은 true =
 * fail-closed. 백업 계정이 정당하게 그룹 권한을 갖는 배치처럼 {@code chmod 700}이 답이 아닌 경우가 실재하는데,
 * 탈출구 없는 게이트의 현실적인 대응은 "업그레이드 롤백"이다 — 보안 수정이 장애가 되는 경로를 막는다.
 * 끄면 같은 문제 목록을 WARN으로 남기고 계속한다(통과가 아니라 <b>명시적으로 끈 것</b>임을 메시지에 박아둔다).
 *
 * <p>단, 이미 존재하는 DB 부모 디렉토리를 앱이 조여주지는 않는다({@link StoragePermissions} 원칙 1) —
 * 실패 메시지에 경로와 실행할 {@code chmod} 명령이 들어 있으니 운영자가 한 줄로 해결한다.
 */
@Component
public class StoragePermissionGuard
    implements BeanFactoryPostProcessor, EnvironmentAware, ApplicationListener<ApplicationStartedEvent> {

    private static final Logger log = LoggerFactory.getLogger(StoragePermissionGuard.class);

    private Environment env;

    @Override
    public void setEnvironment(Environment environment) {
        this.env = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        Path uploadRoot = Paths.get(env.getProperty("worknote.upload.dir", "./attachments"))
            .toAbsolutePath().normalize();

        if (!StoragePermissions.posixSupported(uploadRoot)) {
            log.warn("POSIX 권한 미지원 파일시스템 — DB·첨부 디렉토리 권한 하드닝을 건너뜁니다. "
                + "OS 수준 ACL로 {} 접근을 제한하세요.", uploadRoot);
        }

        report(StoragePermissions.harden(dbFile(), uploadRoot, serverMode()));
    }

    /**
     * 후반 패스 — 스키마가 만들어진 뒤 DB 파일을 다시 600으로. 신규 설치에서 이 패스가 없으면
     * SQLite가 umask대로 만든 644 파일이 그대로 남는다(클래스 주석 참조).
     * 디렉토리는 다시 손대지 않는다 — 만들 때 말고는 바꾸지 않는 게 규칙이고, 전반 패스에서 이미 끝났다.
     */
    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        report(StoragePermissions.hardenFile(dbFile()));
    }

    private boolean serverMode() {
        return "server".equals(env.getProperty("worknote.mode", "local"));
    }

    private Path dbFile() {
        return StoragePermissions.sqliteFile(env.getProperty("spring.datasource.url"));
    }

    /** 두 패스가 같은 정책을 쓰도록 보고는 여기 한 곳에서만 한다 — 정책이 갈리면 게이트로서 의미가 없다. */
    private void report(List<String> problems) {
        if (problems.isEmpty()) {
            return;
        }
        String detail = String.join(" / ", problems);
        if (!serverMode()) {
            log.warn("저장소 권한 점검 실패(local 모드라 기동은 계속): {}", detail);
            return;
        }
        if (env.getProperty("worknote.storage.strict", Boolean.class, true)) {
            throw new IllegalStateException(
                "저장소가 소유자 전용(700/600)임을 확인하지 못해 기동을 중단합니다: " + detail);
        }
        // 통과 로그로 오해할 여지를 남기지 않는다 — 무엇이 문제인지와 왜 그냥 떴는지를 한 줄에 같이 남긴다.
        log.warn("저장소가 소유자 전용(700/600)임을 확인하지 못했습니다. "
            + "WORKNOTE_STORAGE_STRICT=false 로 엄격 모드를 명시적으로 껐기 때문에 기동만 계속합니다 "
            + "— 점검을 통과한 것이 아닙니다. 남은 문제: {}", detail);
    }
}
