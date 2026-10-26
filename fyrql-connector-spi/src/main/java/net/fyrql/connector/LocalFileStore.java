/*
 * Copyright 2026 the FyrQL authors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 */

package net.fyrql.connector;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The files of the local file system: locations are paths, relative ones
 * resolved against a directory, or URIs of the scheme {@code file}. A file
 * is written under a temporary name, see {@link TemporaryFile}.
 */
public final class LocalFileStore implements FileStore {

    private final Path directory;

    /** Creates the store with the directory against which relative paths are resolved. */
    public LocalFileStore(Path directory) {
        this.directory = directory;
    }

    @Override
    public String scheme() {
        return "file";
    }

    /**
     * Returns the path of a location, resolved against the directory.
     *
     * @throws IllegalArgumentException if the location is no valid path
     */
    public Path path(String location) {
        return resolve(location, directory);
    }

    /**
     * Returns the path of a location, a URI of the scheme {@code file} or
     * a path, a relative one resolved against a directory.
     *
     * @throws IllegalArgumentException if the location is no valid path
     */
    public static Path resolve(String location, Path directory) {
        try {
            if (location.regionMatches(true, 0, "file:", 0, 5)) {
                return Path.of(URI.create(location));
            }
            return directory.resolve(location);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid path " + location + " (" + e.getReason() + ")", e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid path " + location + " (" + e.getMessage() + ")", e);
        }
    }

    @Override
    public List<FileObject> list(String location, String extension) throws IOException {
        Globs.checkPattern(location);
        int slash = Globs.lastSeparator(location);
        String name = location.substring(slash + 1);
        List<Path> files = new ArrayList<>();
        if (Globs.isPattern(name)) {
            Pattern pattern = Globs.regex(name);
            Path dir = path(slash < 0 ? "." : location.substring(0, slash + 1));
            if (Files.isDirectory(dir)) {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir,
                        p -> Files.isRegularFile(p) && pattern.matcher(p.getFileName().toString()).matches())) {
                    entries.forEach(files::add);
                }
            }
        } else {
            Path path = path(location);
            if (Files.isDirectory(path)) {
                String lower = extension.toLowerCase(Locale.ROOT);
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(path, p -> Files.isRegularFile(p)
                        && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(lower))) {
                    entries.forEach(files::add);
                }
            } else if (Files.isRegularFile(path)) {
                files.add(path);
            }
        }
        return objects(files);
    }

    /**
     * Returns the files a location names in a whole tree of directories,
     * see {@link FileStore#listTree}. Links to files count as files; links
     * to directories below the directory of the location are not followed.
     */
    @Override
    public List<FileObject> listTree(String location, String extension) throws IOException {
        Globs.checkTreePattern(location);
        int start = Globs.patternStart(location);
        List<Path> files = new ArrayList<>();
        if (start >= 0) {
            Path root = path(start == 0 ? "." : location.substring(0, start));
            Pattern pattern = Globs.pathRegex(location.substring(start));
            walk(root, p -> pattern.matcher(root.relativize(p).toString().replace('\\', '/')).matches(),
                    files);
        } else {
            Path path = path(location);
            if (Files.isDirectory(path)) {
                String lower = extension.toLowerCase(Locale.ROOT);
                walk(path, p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(lower), files);
            } else if (Files.isRegularFile(path)) {
                files.add(path);
            }
        }
        return objects(files);
    }

    /** Adds the regular files under a directory that a predicate takes, without following links below it. */
    private static void walk(Path root, Predicate<Path> take, List<Path> files) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                        return dir.equals(root) || !Files.isSymbolicLink(dir) ? FileVisitResult.CONTINUE
                                : FileVisitResult.SKIP_SUBTREE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        if (attributes.isRegularFile() && take.test(file)) {
                            files.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                        if (e instanceof FileSystemLoopException) {
                            return FileVisitResult.CONTINUE;
                        }
                        throw e;
                    }
                });
    }

    private static List<FileObject> objects(List<Path> files) throws IOException {
        List<FileObject> objects = new ArrayList<>();
        for (Path f : files) {
            objects.add(object(f.toAbsolutePath().normalize()));
        }
        objects.sort(Comparator.comparing(FileObject::location));
        return objects;
    }

    @Override
    public FileObject object(String location) throws IOException {
        Path path = path(location).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new NoSuchFileException(location);
        }
        return object(path);
    }

    private static FileObject object(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        return new LocalObject(path, attributes.size(), attributes.lastModifiedTime().toString());
    }

    /** Creates the temporary file of a location, see {@link TemporaryFile#create}. */
    @Override
    public FileOutput create(String location) {
        return new LocalOutput(TemporaryFile.create(path(location)));
    }

    /** A local file in the version in which it was looked up. */
    private record LocalObject(Path file, long size, String version) implements FileObject {

        @Override
        public String location() {
            return file.toString();
        }

        @Override
        public InputStream open() throws IOException {
            return SourceCounters.current().counted(Files.newInputStream(file));
        }

        @Override
        public int read(long position, ByteBuffer buffer) throws IOException {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                int total = 0;
                while (buffer.hasRemaining()) {
                    int n = channel.read(buffer, position + total);
                    if (n < 0) {
                        break;
                    }
                    total += n;
                }
                SourceCounters.current().bytes(total);
                return total == 0 && position >= channel.size() ? -1 : total;
            }
        }

        @Override
        public Optional<Path> path() {
            return Optional.of(file);
        }
    }

    /** A local file written under a temporary name. */
    private static final class LocalOutput implements FileOutput {
        private final TemporaryFile file;
        private OutputStream stream;

        LocalOutput(TemporaryFile file) {
            this.file = file;
        }

        @Override
        public String location() {
            return file.target().toString();
        }

        @Override
        public OutputStream stream() throws IOException {
            if (stream == null) {
                stream = Files.newOutputStream(file.path());
            }
            return stream;
        }

        @Override
        public void commit() throws IOException {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    file.close();
                    throw e;
                }
            }
            file.commit();
        }

        @Override
        public void close() {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    // The file is deleted anyway.
                }
            }
            file.close();
        }
    }
}
