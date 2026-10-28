package com.winlator.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ContainerBackupArchiveTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void acceptsOnlyContainerEntries() {
        assertEquals(".container", ContainerBackupArchive.getSafeContainerRelativePath("container/.container"));
        assertEquals(".wine/drive_c/Game/game.exe", ContainerBackupArchive.getSafeContainerRelativePath("container/.wine/drive_c/Game/game.exe"));
        assertEquals("", ContainerBackupArchive.getSafeContainerRelativePath("container/"));
    }

    @Test
    public void rejectsTraversalAndAbsolutePaths() {
        assertNull(ContainerBackupArchive.getSafeContainerRelativePath("container/../outside"));
        assertNull(ContainerBackupArchive.getSafeContainerRelativePath("container/.wine/../../outside"));
        assertNull(ContainerBackupArchive.getSafeContainerRelativePath("/container/.container"));
        assertNull(ContainerBackupArchive.getSafeContainerRelativePath("container\\..\\outside"));
        assertNull(ContainerBackupArchive.getSafeContainerRelativePath("other/file"));
    }

    @Test
    public void allowsOnlyInternalLinksAndTheWineZDriveMapping() throws Exception {
        File root = temporaryFolder.newFolder("container");
        File dosDevices = new File(root, ".wine/dosdevices");
        assertTrue(dosDevices.mkdirs());
        File cDrive = new File(dosDevices, "c:");
        File zDrive = new File(dosDevices, "z:");

        assertTrue(ContainerBackupArchive.isSafeLink(root.getCanonicalPath(), cDrive, "../drive_c"));
        assertTrue(ContainerBackupArchive.isSafeLink(root.getCanonicalPath(), zDrive, "/"));
        assertFalse(ContainerBackupArchive.isSafeLink(root.getCanonicalPath(), cDrive, "../../../../outside"));
        assertFalse(ContainerBackupArchive.isSafeLink(root.getCanonicalPath(), cDrive, "/etc/passwd"));
    }
}
