package io.nanofaas.containerd.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class IoManagerTest {

    @Test
    void fifoRoundTrip() throws Exception {
        var fifos = IoManager.createFifoSet("test");
        try {
            assertThat(Files.exists(fifos.stdout())).isTrue();
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            var writer = executor.submit(() -> IoManager.writeFifo(fifos.stdout(), "hello fifo\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            String read = IoManager.readFifo(fifos.stdout());
            writer.get();
            assertThat(read).isEqualTo("hello fifo\n");
        } finally {
            IoManager.cleanup(fifos);
            assertThat(Files.exists(fifos.dir())).isFalse();
        }
    }

    @Test
    void anEmptyWriteStillGivesTheReaderEof() throws Exception {
        // This is what exec relies on when ExecSpec.stdin() is null: opening and closing the
        // write end is what makes the process's stdin read return, instead of blocking forever.
        var fifos = IoManager.createFifoSet("test-empty-stdin");
        try {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            var writer = executor.submit(() -> IoManager.writeFifo(fifos.stdin(), new byte[0]));
            String read = IoManager.readFifo(fifos.stdin());
            writer.get();
            assertThat(read).isEmpty();
        } finally {
            IoManager.cleanup(fifos);
        }
    }
}
