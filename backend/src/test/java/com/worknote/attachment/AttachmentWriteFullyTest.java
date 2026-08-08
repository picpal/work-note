package com.worknote.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code WritableByteChannel.write}는 버퍼 전체가 아니라 <b>일부만</b> 쓰고 그 개수를 돌려줄 수 있다(계약상 허용).
 * 한 번만 호출하면 첨부가 조용히 잘린 채 저장되고 DB의 size와 디스크가 어긋난다 — 무음 데이터 손상.
 *
 * <p>실디스크로는 재현이 거의 불가능하므로 <b>일부러 짧게 쓰는 채널 스텁</b>으로 불변식을 검증한다.
 * "실디스크가 웬만해선 안 자른다"에 기대는 테스트는 아무것도 증명하지 못한다.
 */
class AttachmentWriteFullyTest {

    /** 호출당 최대 {@code chunk} 바이트만 소비하는 채널 — 부분 쓰기를 결정적으로 재현한다. */
    private static final class ShortWriteChannel implements WritableByteChannel {
        private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        private final List<Integer> writes = new ArrayList<>();
        private final int chunk;
        private boolean open = true;

        ShortWriteChannel(int chunk) {
            this.chunk = chunk;
        }

        @Override
        public int write(ByteBuffer src) {
            int n = Math.min(chunk, src.remaining());
            for (int i = 0; i < n; i++) {
                sink.write(src.get());
            }
            writes.add(n);
            return n;
        }

        @Override public boolean isOpen() {
            return open;
        }

        @Override public void close() {
            open = false;
        }

        byte[] written() {
            return sink.toByteArray();
        }
    }

    private static byte[] payload(int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (i % 251);
        }
        return b;
    }

    @Test
    void writesEveryByteWhenChannelConsumesOneByteAtATime() throws IOException {
        byte[] bytes = payload(64);
        ShortWriteChannel ch = new ShortWriteChannel(1);

        AttachmentService.writeFully(ch, bytes);

        assertThat(ch.written()).isEqualTo(bytes);
        assertThat(ch.writes).hasSize(64);   // 실제로 부분 쓰기가 일어났다는 확인
    }

    @Test
    void writesEveryByteWhenChannelConsumesPartialChunks() throws IOException {
        byte[] bytes = payload(10_000);
        ShortWriteChannel ch = new ShortWriteChannel(4_096);

        AttachmentService.writeFully(ch, bytes);

        assertThat(ch.written()).isEqualTo(bytes);
        assertThat(ch.writes).containsExactly(4_096, 4_096, 1_808);
    }

    @Test
    void singleFullWriteStillWorks() throws IOException {
        byte[] bytes = payload(128);
        ShortWriteChannel ch = new ShortWriteChannel(Integer.MAX_VALUE);

        AttachmentService.writeFully(ch, bytes);

        assertThat(ch.written()).isEqualTo(bytes);
        assertThat(ch.writes).containsExactly(128);
    }

    @Test
    void emptyPayloadWritesNothing() throws IOException {
        ShortWriteChannel ch = new ShortWriteChannel(1);

        AttachmentService.writeFully(ch, new byte[0]);

        assertThat(ch.written()).isEmpty();
    }
}
