package com.worknote.redmine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class RedmineClientGuardTest {

    @Test void get_rejectsBlockedBaseBeforeAnyRequest() {
        RedmineClient client = new RedmineClient(new ObjectMapper());
        // loopback — 요청을 보내기 전에 차단돼야 함 (연결 거부 오류가 아니라 base_blocked)
        assertThatThrownBy(() -> client.fetchCurrentLogin("http://127.0.0.1:1", "tok"))
            .isInstanceOf(RedmineException.Upstream.class)
            .hasMessage("redmine_base_blocked");
    }

    @Test void readCapped_underLimit_returnsAll() throws Exception {
        byte[] data = "{\"ok\":true}".getBytes();
        assertThat(RedmineClient.readCapped(new ByteArrayInputStream(data))).isEqualTo(data);
    }

    @Test void readCapped_overLimit_throws() {
        byte[] big = new byte[RedmineClient.MAX_BODY_BYTES + 1];
        assertThatThrownBy(() -> RedmineClient.readCapped(new ByteArrayInputStream(big)))
            .isInstanceOf(RedmineException.Upstream.class)
            .hasMessage("redmine_response_too_large");
    }
}
