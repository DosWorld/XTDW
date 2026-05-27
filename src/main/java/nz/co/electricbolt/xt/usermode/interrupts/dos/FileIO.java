package nz.co.electricbolt.xt.usermode.interrupts.dos;

import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.cpu.SegOfs;
import nz.co.electricbolt.xt.usermode.AccessMode;
import nz.co.electricbolt.xt.usermode.DiskTransferArea;
import nz.co.electricbolt.xt.usermode.ErrorCode;
import nz.co.electricbolt.xt.usermode.SharingMode;
import nz.co.electricbolt.xt.usermode.filedevice.NULFile;
import nz.co.electricbolt.xt.usermode.filedevice.BaseFile;
import nz.co.electricbolt.xt.usermode.filedevice.CONFile;
import nz.co.electricbolt.xt.usermode.filedevice.DiskFile;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.*;
import nz.co.electricbolt.xt.usermode.util.DirectoryTranslation;
import nz.co.electricbolt.xt.usermode.util.MemoryUtil;
import nz.co.electricbolt.xt.usermode.util.Trace;
import nz.co.electricbolt.xt.usermode.util.WildcardFileMatcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.*;
import java.util.HashMap;
import java.util.Map;

public class FileIO {

    private final Map<Short, BaseFile> fileHandleMap = new HashMap<>();
    private final Map<Short, FindFileData> findFileMap = new HashMap<>();
    private short fileHandleCount = 0;
    private SegOfs diskTransferArea;
    private short findFileCount = 0;

    public FileIO() {
        fileHandleMap.put(fileHandleCount++, new CONFile(AccessMode.readOnly, SharingMode.denyWrite, true)); // STDIN - keyboard.
        fileHandleMap.put(fileHandleCount++, new CONFile(AccessMode.writeOnly, SharingMode.denyRead, true)); // STDOUT - screen.
        fileHandleMap.put(fileHandleCount++, new CONFile(AccessMode.writeOnly, SharingMode.denyNone, true, true)); // STDERR - screen.
        fileHandleMap.put(fileHandleCount++, new CONFile(AccessMode.writeOnly, SharingMode.denyRead, true)); // STDAUX - first serial port.
        fileHandleMap.put(fileHandleCount++, new CONFile(AccessMode.writeOnly, SharingMode.denyRead, true)); // STDPRN - first printer port.

        diskTransferArea = new SegOfs((short) 0x0090, (short) 0x0080);
    }

    @Interrupt(function = 0x1A, description = "Set disk transfer area address")
    public void setDiskTransferArea(final CPU cpu, final @DS @DX SegOfs address) {
        this.diskTransferArea = address;
    }

    @Interrupt(function = 0x2F, description = "Get disk transfer area address")
    public void getDiskTransferArea(final CPU cpu) {
        cpu.getReg().ES.setValue(diskTransferArea.getSegment());
        cpu.getReg().BX.setValue(diskTransferArea.getOffset());
    }

    @Interrupt(function = 0x3C, description = "Create or truncate file")
    public void createFile(final CPU cpu, final Trace trace, final DirectoryTranslation directoryTranslation,
                           final @CX short fileAttributes, @ASCIZ @DS @DX String filename) {
        filename = directoryTranslation.emulatedPathToHostPath(filename.toUpperCase());
        trace.interrupt("Host filename " + filename);
        final BaseFile baseFile = new DiskFile(filename, AccessMode.readWrite, SharingMode.compatibilityMode, false);
        if (!baseFile.create()) {
            trace.interrupt("Failed");
            setErrorResult(cpu, trace, ErrorCode.PathNotFound);
            return;
        }

        trace.interrupt("Returning filehandle " + fileHandleCount);
        baseFile.setFileHandle(fileHandleCount);
        fileHandleMap.put(fileHandleCount, baseFile);
        fileHandleCount++;
        cpu.getReg().flags.setCarry(false);
        cpu.getReg().AX.setValue(baseFile.getFileHandle());
    }

    @Interrupt(function = 0x3D, description = "Open existing file")
    public void openFile(final CPU cpu, final Trace trace, final DirectoryTranslation directoryTranslation,
                         @ASCIZ @DS @DX String filename, final @AL byte accessSharingMode, final @CL byte attributeMask) {
        AccessMode accessMode = null;
        byte _accessMode = 0;
        try {
            _accessMode = (byte) (accessSharingMode & 0x07);
            accessMode = AccessMode.values()[_accessMode];
        } catch (IndexOutOfBoundsException e) {
            trace.interrupt("Invalid access mode " + _accessMode);
            setErrorResult(cpu, trace, ErrorCode.AccessCodeInvalid);
            return;
        }

        SharingMode sharingMode = null;
        byte _sharingMode = 0;
        try {
            _sharingMode = (byte) ((accessSharingMode >>> 4) & 0x07);
            sharingMode = SharingMode.values()[_sharingMode];
        } catch (IndexOutOfBoundsException e) {
            trace.interrupt("Invalid sharing mode " + _sharingMode);
            setErrorResult(cpu, trace, ErrorCode.AccessCodeInvalid);
            return;
        }

        final boolean inheritenceFlag = (accessSharingMode & 0x80) == 0x80;

        BaseFile baseFile = null;
        if (filename.equals("NUL")) {
            trace.interrupt("Returning filehandle " + fileHandleCount);

            baseFile = new NULFile(accessMode, sharingMode, inheritenceFlag);
            baseFile.setFileHandle(fileHandleCount);
            fileHandleMap.put(fileHandleCount, baseFile);
            fileHandleCount++;

            cpu.getReg().flags.setCarry(false);
            cpu.getReg().AX.setValue(baseFile.getFileHandle());
        } else if (filename.equals("EMMXXXX0")) {
            trace.interrupt("EMM memory not implemented");
            setErrorResult(cpu, trace, ErrorCode.FileNotFound);
        } else {
            filename = directoryTranslation.emulatedPathToHostPath(filename.toUpperCase());
            trace.interrupt("Host filename " + filename);
            baseFile = new DiskFile(filename, accessMode, sharingMode, inheritenceFlag);
            if (!baseFile.open()) {
                trace.interrupt("Failed");
                setErrorResult(cpu, trace, ErrorCode.FileNotFound);
            } else {
                trace.interrupt("Returning filehandle " + fileHandleCount);

                baseFile.setFileHandle(fileHandleCount);
                fileHandleMap.put(fileHandleCount, baseFile);
                fileHandleCount++;

                cpu.getReg().flags.setCarry(false);
                cpu.getReg().AX.setValue(baseFile.getFileHandle());
            }
        }
    }

    @Interrupt(function = 0x3E, description = "Close file")
    public void closeFile(final CPU cpu, final Trace trace, final @BX short fileHandle) {
        final BaseFile baseFile = getFileHandleOrSetErrorResult(cpu, trace, fileHandle);
        if (baseFile != null) {
            if (baseFile.close()) {
                fileHandleMap.remove(fileHandle);
            }
            cpu.getReg().flags.setCarry(false);
        }
    }

    @Interrupt(function = 0x3F, description = "Read from file or device")
    public void readFile(final CPU cpu, final Trace trace, final @BX short fileHandle,
                         final @CX short numberOfBytesToRead, final @DS @DX SegOfs address) {
        final BaseFile baseFile = getFileHandleOrSetErrorResult(cpu, trace, fileHandle);
        if (baseFile != null) {
            final byte[] buf = baseFile.read(numberOfBytesToRead & 0xFFFF);
            cpu.getReg().flags.setCarry(false);
            if (buf == null) {
                cpu.getReg().AX.setValue((short) 0);
            } else {
                MemoryUtil.writeBuf(cpu.getMemory(), address, buf);
                cpu.getReg().AX.setValue((short) buf.length);
            }
        }
    }

    @Interrupt(function = 0x40, description = "Write to file or device")
    public void writeFile(final CPU cpu, final Trace trace, final @BX short fileHandle,
                          final @CX short numberOfBytesToWrite, final @DS @DX SegOfs address) {
        final BaseFile baseFile = getFileHandleOrSetErrorResult(cpu, trace, fileHandle);
        if (baseFile != null) {
            final byte[] buf = MemoryUtil.readBuf(cpu.getMemory(), address, numberOfBytesToWrite);
            baseFile.write(buf);
            cpu.getReg().flags.setCarry(false);
            cpu.getReg().AX.setValue((short) buf.length);
        }
    }

    @Interrupt(function = 0x41, description = "Delete file")
    public void deleteFile(final CPU cpu, final Trace trace, final @BX short fileHandle) {
        final BaseFile baseFile = getFileHandleOrSetErrorResult(cpu, trace, fileHandle);
        if (baseFile != null) {
            if (!baseFile.delete()) {
                setErrorResult(cpu, trace, ErrorCode.FileNotFound);
                return;
            }
            cpu.getReg().flags.setCarry(false);
            cpu.getReg().AL.setValue((byte) 0x03);
        }
    }

    @Interrupt(function = 0x42, description = "Set current file position")
    public void seekFile(final CPU cpu, final Trace trace, final @BX short fileHandle, @CX @DX int offset,
                         final @AL byte origin) {
        final BaseFile baseFile = getFileHandleOrSetErrorResult(cpu, trace, fileHandle);
        if (baseFile != null) {
            switch (origin) {
                case 0x00: {
                    trace.interrupt("Seeking to beginning");
                    break;
                }
                case 0x01: {
                    trace.interrupt("Seeking from current");
                    offset += baseFile.currentPos();
                    break;
                }
                case 0x02: {
                    trace.interrupt("Seeking from end");
                    offset += baseFile.size();
                    break;
                }
                default: {
                    trace.interrupt("Invalid seek mode " + origin);
                    setErrorResult(cpu, trace, ErrorCode.FunctionNumberInvalid);
                    break;
                }
            }
            baseFile.seek(offset);
            cpu.getReg().flags.setCarry(false);
            cpu.getReg().DX.setValue((short) ((offset >>> 16) & 0xFFFF));
            cpu.getReg().AX.setValue((short) (offset & 0xFFFF));
        }
    }

    @Interrupt(function = 0x43, subfunction = 0x00, description = "Get file attributes")
    public void getFileAttributes(final CPU cpu, final Trace trace, final DirectoryTranslation directoryTranslation,
                                  @ASCIZ @DS @DX String filename) {
        filename = directoryTranslation.emulatedPathToHostPath(filename.toUpperCase());
        trace.interrupt("Host filename " + filename);
        final File file = new File(filename);
        try {
            short result = 0;
            final DosFileAttributeView dosView = Files.getFileAttributeView(file.toPath(), DosFileAttributeView.class);
            if (dosView != null) {
                final DosFileAttributes attrs = dosView.readAttributes();
                if (attrs.isReadOnly())
                    result |= FileAttribute.readOnly;
                if (attrs.isSystem())
                    result |= FileAttribute.system;
                if (attrs.isHidden())
                    result |= FileAttribute.hidden;
                if (attrs.isDirectory())
                    result |= FileAttribute.directory;
                if (attrs.isArchive())
                    result |= FileAttribute.archive;
            } else {
                final PosixFileAttributeView posixView = Files.getFileAttributeView(file.toPath(), PosixFileAttributeView.class);
                if (posixView != null) {
                    PosixFileAttributes attrs = posixView.readAttributes();
                    if (!attrs.permissions().contains(PosixFilePermission.OWNER_WRITE))
                        result |= FileAttribute.readOnly;
                    if (!attrs.isDirectory())
                        result |= FileAttribute.directory;
                } else {
                    BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
                    if (attrs.isDirectory())
                        result |= FileAttribute.directory;
                }
            }
            cpu.getReg().flags.setCarry(false);
            cpu.getReg().AX.setValue(result);
        } catch (IOException e) {
            setErrorResult(cpu, trace, ErrorCode.FileNotFound);
        }
    }

    @Interrupt(function = 0x44, subfunction = 0x00, description = "IOCTL Get device information")
    public void getDeviceInformation(final CPU cpu, final Trace trace, final @BX short fileHandle) {
        final BaseFile baseFile = getFileHandleOrSetErrorResult(cpu, trace, fileHandle);
        if (baseFile != null) {
            cpu.getReg().flags.setCarry(false);
            cpu.getReg().AX.setValue(baseFile.getDeviceInformationWord());
        }
    }

    @Interrupt(function = 0x44, subfunction = 0x01, description = "IOCTL Set device information")
    public void setDeviceInformation(final CPU cpu, final Trace trace, final @BX short fileHandle,
                                     final @DX short newDeviceInformationWord) {
        final BaseFile baseFile = getFileHandleOrSetErrorResult(cpu, trace, fileHandle);
        if (baseFile != null) {
            cpu.getReg().flags.setCarry(false);
        }
    }

    @Interrupt(function = 0x44, subfunction = 0x07, description = "IOCTL Get input/output status")
    public void getOutputStatus(final CPU cpu, final Trace trace, final @BX short fileHandle) {
        final BaseFile baseFile = getFileHandleOrSetErrorResult(cpu, trace, fileHandle);
        if (baseFile != null) {
            cpu.getReg().flags.setCarry(false);
            cpu.getReg().AL.setValue((byte) 0xFF);
        }
    }

    public static String getDirectoryFromPath(final String path) {
        final int index = path.lastIndexOf("\\");
        if (index != -1) {
            return path.substring(0, index);
        }
        return "";
    }

    public static String getFilenameFromPath(final String path) {
        final int index = path.lastIndexOf("\\");
        if (index != -1) {
            return path.substring(index + 1);
        }
        return path;
    }

    @Interrupt(function = 0x4E, description = "Find first matching file")
    public void findFirstMatchingFile(final CPU cpu, final Trace trace, final DirectoryTranslation directoryTranslation,
                                      final @AL byte appendFlag, final @ASCIZ @DS @DX String path,
                                      final @CX short attributeMask) {
        final String upperPath = path.toUpperCase();
        final String emulatedDirectory = getDirectoryFromPath(upperPath);
        final String filenamePattern = getFilenameFromPath(upperPath);
        final String hostDirectory = directoryTranslation.emulatedPathToHostPath(
                emulatedDirectory.isEmpty() ? directoryTranslation.getCurrentEmulatedDirectory() : emulatedDirectory);
        trace.interrupt("Host path " + hostDirectory + " filename " + filenamePattern);

        final byte attrMaskByte = (byte) (attributeMask & 0xFF);
        final byte driveLetter = extractDriveLetter(upperPath, directoryTranslation);

        final File hostDir = new File(hostDirectory);
        final WildcardFileMatcher matcher = new WildcardFileMatcher(filenamePattern);
        final File[] candidates = hostDir.listFiles();
        if (candidates == null) {
            setErrorResult(cpu, trace, ErrorCode.PathNotFound);
            return;
        }

        final java.util.List<File> matched = new java.util.ArrayList<>();
        for (final File f : candidates) {
            final String upperName = f.getName().toUpperCase();
            if (!matcher.matches(upperName))
                continue;
            if (!attributeAllowed(getDosAttribute(f), attrMaskByte))
                continue;
            matched.add(f);
        }
        // "." and ".." are not listed by File.listFiles(), so synthesize them
        // when the caller asked for directories and the wildcard would match.
        if ((attrMaskByte & FileAttribute.directory) != 0) {
            if (matcher.matches(".") && hostDir.isDirectory())
                matched.add(0, new File(hostDir, "."));
            if (matcher.matches("..") && hostDir.getParentFile() != null)
                matched.add(matcher.matches(".") ? 1 : 0, new File(hostDir, ".."));
        }

        if (matched.isEmpty()) {
            setErrorResult(cpu, trace, ErrorCode.FileNotFound);
            return;
        }

        final File[] files = matched.toArray(new File[0]);
        final SegOfs dtaAddress = diskTransferArea.copy();
        final String template = toSearchTemplate(filenamePattern);
        final FindFileData data = new FindFileData(findFileCount, files, (short) 0,
                attrMaskByte, driveLetter, template, dtaAddress);

        final DiskTransferArea dta = new DiskTransferArea(cpu.getMemory(), dtaAddress.getSegment(), dtaAddress.getOffset());
        dta.writeDriveLetter(driveLetter);
        dta.writeSearchTemplate(template);
        dta.writeSearchAttribute(attrMaskByte);
        dta.writeParentCluster((short) 0);
        dta.writeReserved();
        writeMatchToDTA(dta, files[0]);

        findFileMap.put(findFileCount, data);
        dta.setInternalId(findFileCount);
        findFileCount++;
        cpu.getReg().flags.setCarry(false);
        cpu.getReg().AX.setValue((short) 0);
    }

    @Interrupt(function = 0x4F, description = "Find next matching file")
    public void findNextMatchingFile(final CPU cpu, final Trace trace) {
        final DiskTransferArea dta = new DiskTransferArea(cpu.getMemory(),
                diskTransferArea.getSegment(), diskTransferArea.getOffset());
        final short id = dta.getInternalId();
        final FindFileData data = findFileMap.get(id);
        if (data == null) {
            trace.interrupt("Unknown FindFirst session id " + id);
            setErrorResult(cpu, trace, ErrorCode.NoMoreFiles);
            return;
        }

        final int nextIndex = (data.fileIndex() & 0xFFFF) + 1;
        if (nextIndex >= data.files().length) {
            trace.interrupt("Find session " + id + " exhausted");
            findFileMap.remove(id);
            setErrorResult(cpu, trace, ErrorCode.NoMoreFiles);
            return;
        }

        writeMatchToDTA(dta, data.files()[nextIndex]);
        findFileMap.put(id, data.advance());
        cpu.getReg().flags.setCarry(false);
        cpu.getReg().AX.setValue((short) 0);
    }

    private void writeMatchToDTA(final DiskTransferArea dta, final File file) {
        final FileDateTime fileDateTime = new FileDateTime(file.lastModified());
        dta.writeFileAttribute(getDosAttribute(file));
        dta.writeFileTime(fileDateTime.toDOSTime());
        dta.writeFileDate(fileDateTime.toDOSDate());
        // ".", ".." and directories have an undefined size — DOS reports 0.
        final long size = file.isDirectory() ? 0L : file.length();
        dta.writeFileSize((int) size);
        dta.writeFilename(file.getName().toUpperCase());
    }

    private static boolean attributeAllowed(final byte fileAttr, final byte mask) {
        // Volume label is exclusive: a search with the volume-label bit set in
        // the mask returns ONLY volume labels.
        final boolean fileIsVolumeLabel = (fileAttr & FileAttribute.volumeLabel) != 0;
        final boolean maskWantsVolumeLabel = (mask & FileAttribute.volumeLabel) != 0;
        if (maskWantsVolumeLabel != fileIsVolumeLabel)
            return false;
        // Read-only (0x01) and archive (0x20) are always returned.
        // Hidden (0x02), system (0x04) and directory (0x10) are returned only
        // if their bit is set in the mask.
        final int selectiveBits = FileAttribute.hidden | FileAttribute.system | FileAttribute.directory;
        return (fileAttr & selectiveBits & ~mask) == 0;
    }

    private static byte getDosAttribute(final File file) {
        byte attr = 0;
        try {
            final DosFileAttributeView dosView = Files.getFileAttributeView(file.toPath(), DosFileAttributeView.class);
            if (dosView != null) {
                final DosFileAttributes attrs = dosView.readAttributes();
                if (attrs.isReadOnly())  attr |= FileAttribute.readOnly;
                if (attrs.isHidden())    attr |= FileAttribute.hidden;
                if (attrs.isSystem())    attr |= FileAttribute.system;
                if (attrs.isDirectory()) attr |= FileAttribute.directory;
                if (attrs.isArchive())   attr |= FileAttribute.archive;
                return attr;
            }
            final BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
            if (attrs.isDirectory()) attr |= FileAttribute.directory;
            else                     attr |= FileAttribute.archive;
            if (!file.canWrite())    attr |= FileAttribute.readOnly;
            if (file.isHidden())     attr |= FileAttribute.hidden;
        } catch (IOException e) {
            // Fall through with whatever bits we've already gathered.
        }
        return attr;
    }

    /**
     * Converts a wildcard filename (e.g. {@code "FOO.TXT"} or {@code "*.C"})
     * into the 11-byte 8.3 search template stored in the DTA: 8 bytes name
     * padded with spaces, then 3 bytes extension padded with spaces, no dot.
     * A trailing {@code *} expands to the remaining {@code ?}s in its field.
     */
    private static String toSearchTemplate(final String pattern) {
        final int dot = pattern.indexOf('.');
        final String name = dot < 0 ? pattern : pattern.substring(0, dot);
        final String ext = dot < 0 ? "" : pattern.substring(dot + 1);
        final StringBuilder sb = new StringBuilder(11);
        appendTemplateField(sb, name, 8);
        appendTemplateField(sb, ext, 3);
        return sb.toString();
    }

    private static void appendTemplateField(final StringBuilder sb, final String field, final int width) {
        int written = 0;
        for (int i = 0; i < field.length() && written < width; i++) {
            final char c = field.charAt(i);
            if (c == '*') {
                while (written < width) { sb.append('?'); written++; }
                break;
            }
            sb.append(c);
            written++;
        }
        while (written < width) { sb.append(' '); written++; }
    }

    private static byte extractDriveLetter(final String upperPath, final DirectoryTranslation dirTrans) {
        if (upperPath.length() >= 2 && upperPath.charAt(1) == ':') {
            final char c = upperPath.charAt(0);
            if (c >= 'A' && c <= 'Z')
                return (byte) (c - 'A' + 1);
        }
        final String cur = dirTrans.getCurrentEmulatedDirectory();
        if (cur != null && cur.length() >= 2 && cur.charAt(1) == ':') {
            final char c = Character.toUpperCase(cur.charAt(0));
            if (c >= 'A' && c <= 'Z')
                return (byte) (c - 'A' + 1);
        }
        return 3; // default C:
    }

    @Interrupt(function = 0x56, description = "Rename file")
    public void renameFile(final CPU cpu, final Trace trace, final DirectoryTranslation directoryTranslation,
                           final @ASCIZ @DS @DX String source, final @ASCIZ @ES @DI String dest) {
        final File sourceFile = new File(directoryTranslation.emulatedPathToHostPath(source.toUpperCase()));
        final File destFile = new File(directoryTranslation.emulatedPathToHostPath(dest.toUpperCase()));

        if (!sourceFile.exists()) {
            setErrorResult(cpu, trace, ErrorCode.FileNotFound);
            return;
        } else if (destFile.exists()) {
            setErrorResult(cpu, trace, ErrorCode.PathNotFound);
            return;
        }

        if (sourceFile.renameTo(destFile)) {
            cpu.getReg().flags.setCarry(false);
        } else {
            setErrorResult(cpu, trace, ErrorCode.PathNotFound);
        }
    }

    @Interrupt(function = 0x57, subfunction = 0x00, description = "Get file's date and time")
    public void getFileDateTime(final CPU cpu, final Trace trace, final @BX short fileHandle) {
        final BaseFile baseFile = getFileHandleOrSetErrorResult(cpu, trace, fileHandle);
        if (baseFile != null) {
            final FileDateTime fileDateTime = baseFile.getDateTime();
            if (fileDateTime == null) {
                setErrorResult(cpu, trace, ErrorCode.InvalidHandle);
                return;
            }

            cpu.getReg().flags.setCarry(false);
            cpu.getReg().CX.setValue(fileDateTime.toDOSTime());
            cpu.getReg().DX.setValue(fileDateTime.toDOSDate());
        }
    }

    @Interrupt(function = 0x39, description = "Create directory")
    public void createDirectory(final CPU cpu, final Trace trace, final DirectoryTranslation directoryTranslation,
                                @ASCIZ @DS @DX String path) {
        path = directoryTranslation.emulatedPathToHostPath(path.toUpperCase());
        File dir = new File(path);
        if (dir.mkdirs()) {
            cpu.getReg().flags.setCarry(false);
        } else {
            setErrorResult(cpu, trace, ErrorCode.PathNotFound);
        }
    }

    @Interrupt(function = 0x3A, description = "Remove directory")
    public void removeDirectory(final CPU cpu, final Trace trace, final DirectoryTranslation directoryTranslation,
                                @ASCIZ @DS @DX String path) {
        path = directoryTranslation.emulatedPathToHostPath(path.toUpperCase());
        File dir = new File(path);
        if (dir.exists() && dir.isDirectory() && dir.delete()) {
            cpu.getReg().flags.setCarry(false);
        } else {
            setErrorResult(cpu, trace, ErrorCode.PathNotFound);
        }
    }

    @Interrupt(function = 0x3B, description = "Set current directory")
    public void setCurrentDirectory(final CPU cpu, final Trace trace, final DirectoryTranslation directoryTranslation,
                                    @ASCIZ @DS @DX String path) {
        directoryTranslation.setCurrentEmulatedDirectory(path);
        cpu.getReg().flags.setCarry(false);
    }

    @Interrupt(function = 0x47, description = "Get current directory")
    public void getCurrentDirectory(final CPU cpu, final Trace trace, final DirectoryTranslation directoryTranslation,
                                    final @DL byte drive, final @DS @SI SegOfs buffer) {
        String currentDir = directoryTranslation.getCurrentEmulatedDirectory();
        MemoryUtil.writeStringZ(cpu.getMemory(), buffer, currentDir, '\0');
        cpu.getReg().flags.setCarry(false);
    }

    private BaseFile getFileHandleOrSetErrorResult(final CPU cpu, final Trace trace, final short fileHandle) {
        final BaseFile baseFile = fileHandleMap.get(fileHandle);
        if (baseFile == null) {
            trace.interrupt("Invalid filehandle " + fileHandle);
            setErrorResult(cpu, trace, ErrorCode.InvalidHandle);
            return null;
        }
        return baseFile;
    }

    private void setErrorResult(final CPU cpu, final Trace trace, final ErrorCode errorCode) {
        trace.interrupt("Returning error " + errorCode.errorCode);
        cpu.getReg().flags.setCarry(true);
        cpu.getReg().AX.setValue(errorCode.errorCode);
    }
}
