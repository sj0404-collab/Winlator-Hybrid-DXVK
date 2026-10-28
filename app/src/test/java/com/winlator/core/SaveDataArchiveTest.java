package com.winlator.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SaveDataArchiveTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void acceptsOnlyUserProfilePaths() {
        assertEquals("", SaveDataArchive.safeProfilePath("profile/"));
        assertEquals("profile/Documents/My Game/save.dat", SaveDataArchive.safeProfilePath("profile/Documents/My Game/save.dat"));
        assertNull(SaveDataArchive.safeProfilePath("profile/../outside"));
        assertNull(SaveDataArchive.safeProfilePath("profile/AppData/../../outside"));
        assertNull(SaveDataArchive.safeProfilePath("/profile/Documents/save.dat"));
        assertNull(SaveDataArchive.safeProfilePath("other/save.dat"));
    }

    @Test
    public void rejectsLinksThatEscapeTheProfile() throws Exception {
        File root = temporaryFolder.newFolder("profile");
        File link = new File(root, "AppData/Roaming/Game/save-link");
        assertTrue(new File(root, "AppData/Roaming/Game").mkdirs());
        assertTrue(SaveDataArchive.safeSymlink(root.getCanonicalPath(), link, "../../Local/Game/save.dat"));
        assertFalse(SaveDataArchive.safeSymlink(root.getCanonicalPath(), link, "../../../../../../outside"));
        assertFalse(SaveDataArchive.safeSymlink(root.getCanonicalPath(), link, "/etc/passwd"));
    }
}
