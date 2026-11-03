package com.winlator.contents;

import com.winlator.container.ContainerFiles;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Atomic logical removal. A failed purge never exposes a partly deleted installed component. */
public final class ComponentRemoval {
    private ComponentRemoval() {}
    public static void remove(File target) throws IOException {
        File parent = target.getParentFile().getCanonicalFile();
        if (!target.getCanonicalFile().getParentFile().equals(parent) || Files.isSymbolicLink(target.toPath()))
            throw new IOException("Unsafe component removal path.");
        if (!target.isDirectory()) throw new IOException("Component directory is missing.");
        File retired = new File(parent, ".removed-" + UUID.randomUUID());
        Files.move(target.toPath(), retired.toPath(), StandardCopyOption.ATOMIC_MOVE);
        // Publication is committed. Cleanup failure means garbage remains, not a broken installation.
        if (!ContainerFiles.deleteRecursively(retired))
            ;
    }
}
