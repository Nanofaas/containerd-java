package io.nanofaas.containerd.internal;

import io.nanofaas.containerd.ContainerdException;
import io.nanofaas.containerd.Identifiers;
import io.nanofaas.containerd.NetworkAttachment;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.stream.IntStream;

/** One atomic record per container; no reflective serialization, including in native images. */
final class ContainerJournal {
    final containerd.services.containers.v1.Container container;
    boolean pending;
    boolean removeSnapshot;
    boolean networkPending;
    NetworkAttachment attachment;

    ContainerJournal(containerd.services.containers.v1.Container container) {
        this.container = container;
    }

    static ContainerJournal read(Path directory, String id) {
        Identifiers.requireValid(id);
        Path path = directory.resolve(id).resolve("lifecycle.properties");
        Properties values = new Properties();
        try (var in = Files.newInputStream(path)) {
            values.load(in);
            var entry = new ContainerJournal(containerd.services.containers.v1.Container.parseFrom(
                    Base64.getDecoder().decode(values.getProperty("container"))));
            if (!entry.container.getId().equals(id)) {
                throw new IOException("container identity does not match journal directory");
            }
            entry.pending = Boolean.parseBoolean(values.getProperty("pending"));
            entry.removeSnapshot = Boolean.parseBoolean(values.getProperty("removeSnapshot"));
            entry.networkPending = Boolean.parseBoolean(values.getProperty("networkPending"));
            if (Boolean.parseBoolean(values.getProperty("attached"))) {
                entry.attachment = new NetworkAttachment(list(values, "addresses"), list(values, "gateways"),
                        list(values, "nameservers"), list(values, "searchDomains"), values.getProperty("domain"));
            }
            return entry;
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | IllegalArgumentException | NullPointerException e) {
            throw new ContainerdException("could not read lifecycle state " + path + "; check stateDirectory", e);
        }
    }

    void save(Path directory) {
        Path dir = directory.resolve(container.getId());
        Properties values = new Properties();
        values.setProperty("container", Base64.getEncoder().encodeToString(container.toByteArray()));
        values.setProperty("pending", Boolean.toString(pending));
        values.setProperty("removeSnapshot", Boolean.toString(removeSnapshot));
        values.setProperty("networkPending", Boolean.toString(networkPending));
        values.setProperty("attached", Boolean.toString(attachment != null));
        if (attachment != null) {
            putList(values, "addresses", attachment.addresses());
            putList(values, "gateways", attachment.gateways());
            putList(values, "nameservers", attachment.nameservers());
            putList(values, "searchDomains", attachment.searchDomains());
            if (attachment.domain() != null) values.setProperty("domain", attachment.domain());
        }
        Path temporary = null;
        try {
            Files.createDirectories(dir);
            temporary = Files.createTempFile(dir, "lifecycle-", ".tmp");
            try (var out = Files.newOutputStream(temporary)) { values.store(out, "containerd-java lifecycle v1"); }
            try (var file = FileChannel.open(temporary, StandardOpenOption.WRITE)) { file.force(true); }
            Files.move(temporary, dir.resolve("lifecycle.properties"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var folder = FileChannel.open(dir, StandardOpenOption.READ)) { folder.force(true); }
        } catch (IOException e) {
            throw new ContainerdException("could not persist lifecycle state in " + dir
                    + "; configure a writable stateDirectory", e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* original failure wins */ }
            }
        }
    }

    static List<ContainerJournal> list(Path directory) {
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isDirectory)
                    .map(path -> read(directory, path.getFileName().toString()))
                    .filter(java.util.Objects::nonNull).toList();
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new ContainerdException("could not list lifecycle state in " + directory, e);
        }
    }

    private static List<String> list(Properties values, String name) {
        int size = Integer.parseInt(values.getProperty(name + ".size"));
        return IntStream.range(0, size).mapToObj(i -> values.getProperty(name + "." + i)).toList();
    }

    private static void putList(Properties values, String name, List<String> items) {
        values.setProperty(name + ".size", Integer.toString(items.size()));
        for (int i = 0; i < items.size(); i++) values.setProperty(name + "." + i, items.get(i));
    }
}
