package nz.co.electricbolt.xt.usermode.util;

import java.io.File;

public class DirectoryTranslation {

    private final String workingDirectory;
    private String currentEmulatedDirectory = "C:\\";

    public DirectoryTranslation(final String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public String getCurrentEmulatedDirectory() {
        return currentEmulatedDirectory;
    }

    public void setCurrentEmulatedDirectory(String path) {
        this.currentEmulatedDirectory = path;
    }

    public String emulatedPathToHostPath(String path) {
        if (path.charAt(1) == ':') {
            path = path.substring(2);
        }
        path = path.replace('\\', File.separatorChar);
        if (path.startsWith(File.separator)) {
            path = path.substring(1);
        }
        path = workingDirectory + path;
        return path;
    }

    public String emulatedLfnPathToHostPath(String path) {
        if (path.charAt(1) == ':') {
            path = path.substring(2);
        }
        path = path.replace('\\', File.separatorChar);
        if (path.startsWith(File.separator)) {
            path = path.substring(1);
        }
        path = workingDirectory + path;
        return path;
    }

    public String hostPathToEmulatedPath(String path) {
        if (path.startsWith(workingDirectory)) {
            path = path.substring(workingDirectory.length());
        }
        path = path.replace(File.separatorChar, '\\');
        if (!path.startsWith("\\")) {
            path = "\\" + path;
        }
        path = "C:" + path;
        return path;
    }
}
