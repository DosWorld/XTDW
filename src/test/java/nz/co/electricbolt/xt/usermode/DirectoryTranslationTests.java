// DirectoryUtilTests.java
// XT Copyright © 2025; Electric Bolt Limited.

package nz.co.electricbolt.xt.usermode;

import nz.co.electricbolt.xt.usermode.util.DirectoryTranslation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DirectoryTranslationTests {

    @Test
    public void emulatedPathToHostPathTests() {
        final DirectoryTranslation directoryTranslation = new DirectoryTranslation("/Users/matthew/Documents/");
        assertEquals("/Users/matthew/Documents/", directoryTranslation.emulatedPathToHostPath("C:"));
        assertEquals("/Users/matthew/Documents/", directoryTranslation.emulatedPathToHostPath("C:\\"));
        assertEquals("/Users/matthew/Documents/Dev", directoryTranslation.emulatedPathToHostPath("C:\\Dev"));
        assertEquals("/Users/matthew/Documents/Dev/TP6", directoryTranslation.emulatedPathToHostPath("C:\\Dev\\TP6"));
        assertEquals("/Users/matthew/Documents/Dev/TP6/", directoryTranslation.emulatedPathToHostPath("C:\\Dev\\TP6\\"));
        assertEquals("/Users/matthew/Documents/Dev/TP6/TASM.EXE", directoryTranslation.emulatedPathToHostPath("C:\\Dev\\TP6\\TASM.EXE"));
    }

    @Test
    public void emulatedPathToHostPathRelativeTests() {
        final DirectoryTranslation dt = new DirectoryTranslation("/Users/matthew/Documents/");
        dt.setCurrentEmulatedDirectory("C:\\TWD");
        assertEquals("/Users/matthew/Documents/TWD", dt.emulatedPathToHostPath("C:\\TWD"));
        assertEquals("/Users/matthew/Documents/TWD/FOO.TXT", dt.emulatedPathToHostPath("FOO.TXT"));
        assertEquals("/Users/matthew/Documents/FOO.TXT", dt.emulatedPathToHostPath("..\\FOO.TXT"));
        assertEquals("/Users/matthew/Documents/TWD/SUB/BAR.TXT", dt.emulatedPathToHostPath("SUB\\BAR.TXT"));
        assertEquals("/Users/matthew/Documents/", dt.emulatedPathToHostPath("..\\"));
        assertEquals("/Users/matthew/Documents/OTHER", dt.emulatedPathToHostPath("..\\OTHER"));
    }

    @Test
    public void setCurrentEmulatedDirectoryTests() {
        final DirectoryTranslation dt = new DirectoryTranslation("/Users/matthew/Documents/");
        assertEquals("C:\\", dt.getCurrentEmulatedDirectory());
        dt.setCurrentEmulatedDirectory("TWD");
        assertEquals("C:\\TWD", dt.getCurrentEmulatedDirectory());
        dt.setCurrentEmulatedDirectory("C:\\DEV\\TP6");
        assertEquals("C:\\DEV\\TP6", dt.getCurrentEmulatedDirectory());
        dt.setCurrentEmulatedDirectory("..");
        assertEquals("C:\\DEV", dt.getCurrentEmulatedDirectory());
        dt.setCurrentEmulatedDirectory("C:\\");
        assertEquals("C:\\", dt.getCurrentEmulatedDirectory());
    }

    @Test
    public void hostPathToEmulatedPathTests() {
        final DirectoryTranslation directoryTranslation = new DirectoryTranslation("/Users/matthew/Documents/");
        assertEquals("C:\\Random\\Folder\\Structure", directoryTranslation.hostPathToEmulatedPath("/Random/Folder/Structure"));
        assertEquals("C:\\Random\\Folder\\Structure\\", directoryTranslation.hostPathToEmulatedPath("/Random/Folder/Structure/"));
        assertEquals("C:\\Random\\Folder\\Structure\\", directoryTranslation.hostPathToEmulatedPath("Random/Folder/Structure/"));
        assertEquals("C:\\", directoryTranslation.hostPathToEmulatedPath("/Users/matthew/Documents/"));
        assertEquals("C:\\Dev", directoryTranslation.hostPathToEmulatedPath("/Users/matthew/Documents/Dev"));
        assertEquals("C:\\Dev\\", directoryTranslation.hostPathToEmulatedPath("/Users/matthew/Documents/Dev/"));
        assertEquals("C:\\Dev\\TP6", directoryTranslation.hostPathToEmulatedPath("/Users/matthew/Documents/Dev/TP6"));
        assertEquals("C:\\Dev\\TP6\\", directoryTranslation.hostPathToEmulatedPath("/Users/matthew/Documents/Dev/TP6/"));
        assertEquals("C:\\Dev\\TP6\\TASM.EXE", directoryTranslation.hostPathToEmulatedPath("/Users/matthew/Documents/Dev/TP6/TASM.EXE"));
    }
}