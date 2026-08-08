package com.worknote.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * M-6 — 기동 시 DB 부모 디렉토리·업로드 루트를 700, 기존 DB 파일을 600으로 맞춘다.
 *
 * <p>{@link BeanFactoryPostProcessor}인 이유: DataSource·Flyway가 DB 파일을 만들기 <b>전</b>에 실행돼야
 * 처음부터 700 디렉토리 안에서 파일이 생긴다. ApplicationRunner는 이미 늦다.
 * (그래서 {@code @Value} 대신 {@link EnvironmentAware} — BFPP는 일반 BeanPostProcessor보다 먼저 만들어진다)
 *
 * <p>server 모드에서 보정까지 실패하면 기동을 세운다. 공용 서버에서 DB가 다른 계정에 읽히는 상태로
 * 조용히 뜨는 것보다 안 뜨는 게 낫다. local 모드(개인 PC·무인증)는 WARN만.
 */
@Component
public class StoragePermissionGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(StoragePermissionGuard.class);

    private Environment env;

    @Override
    public void setEnvironment(Environment environment) {
        this.env = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        boolean serverMode = "server".equals(env.getProperty("worknote.mode", "local"));
        Path dbFile = StoragePermissions.sqliteFile(env.getProperty("spring.datasource.url"));
        Path uploadRoot = Paths.get(env.getProperty("worknote.upload.dir", "./attachments"))
            .toAbsolutePath().normalize();

        if (!StoragePermissions.posixSupported(uploadRoot)) {
            log.warn("POSIX 권한 미지원 파일시스템 — DB·첨부 디렉토리 권한 하드닝을 건너뜁니다. "
                + "OS 수준 ACL로 {} 접근을 제한하세요.", uploadRoot);
        }

        List<String> problems = StoragePermissions.harden(dbFile, uploadRoot, serverMode);
        if (problems.isEmpty()) {
            return;
        }
        String detail = String.join(" / ", problems);
        if (serverMode) {
            throw new IllegalStateException("저장소 권한을 700/600으로 보정하지 못했습니다: " + detail);
        }
        log.warn("저장소 권한 보정 실패(local 모드라 기동은 계속): {}", detail);
    }
}
