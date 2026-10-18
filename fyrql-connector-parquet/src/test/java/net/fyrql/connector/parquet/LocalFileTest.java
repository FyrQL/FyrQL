/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.connector.parquet;

import org.apache.parquet.io.SeekableInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The stream of a local file: ranges of bytes into arrays and buffers, positions, and the end. */
class LocalFileTest {

    @TempDir
    Path dir;

    @Test
    void theStreamReadsRangesAtTheirPositions() throws IOException {
        byte[] content = new byte[1000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        Path file = Files.write(dir.resolve("bytes.bin"), content);
        LocalFile local = new LocalFile(file);
        assertEquals(1000, local.getLength());
        try (SeekableInputStream in = local.newStream()) {
            in.seek(10);
            byte[] bytes = new byte[60];
            assertEquals(50, in.read(bytes, 5, 50));
            assertArrayEquals(Arrays.copyOfRange(content, 10, 60), Arrays.copyOfRange(bytes, 5, 55));
            assertEquals(60, in.getPos());
            assertEquals(60, in.read());

            ByteBuffer heap = ByteBuffer.allocate(20);
            heap.position(4);
            assertEquals(16, in.read(heap));
            assertArrayEquals(Arrays.copyOfRange(content, 61, 77), Arrays.copyOfRange(heap.array(), 4, 20));

            ByteBuffer direct = ByteBuffer.allocateDirect(30);
            in.readFully(direct);
            direct.flip();
            byte[] read = new byte[30];
            direct.get(read);
            assertArrayEquals(Arrays.copyOfRange(content, 77, 107), read);

            in.seek(990);
            assertThrows(EOFException.class, () -> in.readFully(ByteBuffer.allocate(20)));
            in.seek(1000);
            assertEquals(-1, in.read(new byte[4], 0, 4));
        }
    }
}
