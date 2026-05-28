package nz.co.electricbolt.xt.usermode.util;

import java.io.File;
import java.nio.file.Path;

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
        path = resolveEmulatedPath(path);
        this.currentEmulatedDirectory = path;
    }

    private String resolveEmulatedPath(String path) {
        if (path.length() >= 2 && path.charAt(1) == ':') {
            path = path.substring(2);
        }
        boolean trailingSep = path.endsWith("\\");
        if (path.isEmpty() || !path.startsWith("\\")) {
            String base = currentEmulatedDirectory;
            if (base.length() >= 2 && base.charAt(1) == ':') {
                base = base.substring(2);
            }
            if (!base.endsWith("\\")) {
                base = base + "\\";
            }
            path = base + path;
        }
        path = path.replace('\\', '/');
        Path normalized = Path.of("/" + path).normalize();
        path = normalized.toString().substring(1);
        path = path.replace('/', '\\');
        if (!path.startsWith("\\")) {
            path = "\\" + path;
        }
        if (trailingSep && !path.endsWith("\\")) {
            path = path + "\\";
        }
        return "C:" + path;
    }

    public String emulatedPathToHostPath(String path) {
        path = resolveEmulatedPath(path);
        path = path.substring(2);
        path = path.replace('\\', File.separatorChar);
        if (path.startsWith(File.separator)) {
            path = path.substring(1);
        }
        path = workingDirectory + path;
        return path;
    }

    public String emulatedLfnPathToHostPath(String path) {
        path = resolveEmulatedPath(path);
        path = path.substring(2);
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
