package nz.co.electricbolt.xt.usermode.interrupts.dos;

import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.cpu.SegOfs;
import nz.co.electricbolt.xt.usermode.AccessMode;
import nz.co.electricbolt.xt.usermode.ErrorCode;
import nz.co.electricbolt.xt.usermode.SharingMode;
import nz.co.electricbolt.xt.usermode.filedevice.DiskFile;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.*;
import nz.co.electricbolt.xt.usermode.util.DirectoryTranslation;
import nz.co.electricbolt.xt.usermode.util.MemoryUtil;
import nz.co.electricbolt.xt.usermode.util.Trace;

import java.io.File;

public class FileIOLFN {

    private void setErrorResult(CPU cpu, Trace trace, ErrorCode errorCode) {
        cpu.getReg().flags.setCarry(true);
        cpu.getReg().AX.setValue(errorCode.errorCode);
    }

    @Interrupt(interrupt = 0x21, function = 0x71, subfunction = 0x0D, description = "Reset drive (LFN)")
    public void resetDrive(CPU cpu, Trace trace) {
        cpu.getReg().flags.setCarry(false);
    }

    @Interrupt(interrupt = 0x21, function = 0x71, subfunction = 0x39, description = "Create directory (LFN)")
    public void createDirectoryLFN(CPU cpu, Trace trace, DirectoryTranslation dirTrans,
                                    @ASCIZ @DS @DX String path) {
        path = dirTrans.emulatedLfnPathToHostPath(path);
        File dir = new File(path);
        if (dir.mkdirs()) {
            cpu.getReg().flags.setCarry(false);
        } else {
            setErrorResult(cpu, trace, ErrorCode.PathNotFound);
        }
    }

    @Interrupt(interrupt = 0x21, function = 0x71, subfunction = 0x3A, description = "Remove directory (LFN)")
    public void removeDirectoryLFN(CPU cpu, Trace trace, DirectoryTranslation dirTrans,
                                    @ASCIZ @DS @DX String path) {
        path = dirTrans.emulatedLfnPathToHostPath(path);
        File dir = new File(path);
        if (dir.exists() && dir.isDirectory() && dir.delete()) {
            cpu.getReg().flags.setCarry(false);
        } else {
            setErrorResult(cpu, trace, ErrorCode.PathNotFound);
        }
    }

    @Interrupt(interrupt = 0x21, function = 0x71, subfunction = 0x3B, description = "Set current directory (LFN)")
    public void setCurrentDirectoryLFN(CPU cpu, Trace trace, DirectoryTranslation dirTrans,
                                        @ASCIZ @DS @DX String path) {
        dirTrans.setCurrentEmulatedDirectory(path);
        cpu.getReg().flags.setCarry(false);
    }

    @Interrupt(interrupt = 0x21, function = 0x71, subfunction = 0x41, description = "Delete file (LFN)")
    public void deleteFileLFN(CPU cpu, Trace trace, DirectoryTranslation dirTrans,
                               @ASCIZ @DS @DX String filename) {
        filename = dirTrans.emulatedLfnPathToHostPath(filename);
        File file = new File(filename);
        if (file.exists() && file.isFile() && file.delete()) {
            cpu.getReg().flags.setCarry(false);
        } else {
            setErrorResult(cpu, trace, ErrorCode.FileNotFound);
        }
    }

    @Interrupt(interrupt = 0x21, function = 0x71, subfunction = 0x43, description = "Get/Set file attributes (LFN)")
    public void fileAttributesLFN(CPU cpu, Trace trace, DirectoryTranslation dirTrans,
                                   @ASCIZ @DS @DX String filename, final @AL byte operation, final @CX short attributes) {
        filename = dirTrans.emulatedLfnPathToHostPath(filename);
        if (operation == 0) {
            cpu.getReg().flags.setCarry(false);
            cpu.getReg().CX.setValue((short) 0);
        } else {
            cpu.getReg().flags.setCarry(false);
        }
    }

    @Interrupt(interrupt = 0x21, function = 0x71, subfunction = 0x47, description = "Get current directory (LFN)")
    public void getCurrentDirectoryLFN(CPU cpu, Trace trace, DirectoryTranslation dirTrans,
                                        final @DL byte drive, final @DS @SI SegOfs buffer) {
        String currentDir = dirTrans.getCurrentEmulatedDirectory();
        MemoryUtil.writeStringZ(cpu.getMemory(), buffer, currentDir, '\0');
        cpu.getReg().flags.setCarry(false);
    }

    @Interrupt(interrupt = 0x21, function = 0x71, subfunction = 0x4E, description = "Find first file (LFN)")
    public void findFirstLFN(CPU cpu, Trace trace, DirectoryTranslation dirTrans,
                              final @AL byte appendFlag, final @ASCIZ @DS @DX String path,
                              final @CX short attributeMask) {
        cpu.getReg().flags.setCarry(false);
    }

    @Interrupt(interrupt = 0x21, function = 0x71, subfunction = 0x4F, description = "Find next file (LFN)")
    public void findNextLFN(CPU cpu, Trace trace) {
        cpu.getReg().flags.setCarry(false);
    }

    @Interrupt(interrupt = 0x21, function = 0x71, subfunction = 0x56, description = "Move file (LFN)")
    public void renameFileLFN(CPU cpu, Trace trace, DirectoryTranslation dirTrans,
                               @ASCIZ @DS @DX String source, @ASCIZ @ES @DI String dest) {
        source = dirTrans.emulatedLfnPathToHostPath(source);
        dest = dirTrans.emulatedPathToHostPath(dest);
        File src = new File(source);
        File dst = new File(dest);
        if (src.exists() && src.renameTo(dst)) {
            cpu.getReg().flags.setCarry(false);
        } else {
            setErrorResult(cpu, trace, ErrorCode.PathNotFound);
        }
    }

    @Interrupt(interrupt = 0x21, function = 0x71, subfunction = 0x60, description = "Truename (LFN)")
    public void truenameLFN(CPU cpu, Trace trace, DirectoryTranslation dirTrans,
                             final @CL byte flags, @ASCIZ @DS @SI String sourcePath, @ES @DI SegOfs destBuffer) {
        String resolved = dirTrans.emulatedLfnPathToHostPath(sourcePath);
        MemoryUtil.writeStringZ(cpu.getMemory(), destBuffer, resolved, '\0');
        cpu.getReg().flags.setCarry(false);
    }

    @Interrupt(interrupt = 0x21, function = 0x71, subfunction = 0x6C, description = "Create/Open file (LFN)")
    public void openFileLFN(CPU cpu, Trace trace, DirectoryTranslation dirTrans,
                             @ASCIZ @DS @SI String filename, final @BX short accessSharingMode,
                             final @CX short attributes, final @DX short action, final @AL byte fileHandleReturn) {
        filename = dirTrans.emulatedLfnPathToHostPath(filename);
        AccessMode accessMode = AccessMode.readWrite;
        SharingMode sharingMode = SharingMode.compatibilityMode;
        DiskFile file = new DiskFile(filename, accessMode, sharingMode, false);
        if ((action & 0x01) != 0) {
            if (!file.create()) {
                setErrorResult(cpu, trace, ErrorCode.AccessDenied);
                return;
            }
        } else if ((action & 0x02) != 0) {
            if (!file.open()) {
                setErrorResult(cpu, trace, ErrorCode.FileNotFound);
                return;
            }
        } else {
            setErrorResult(cpu, trace, ErrorCode.FunctionNumberInvalid);
            return;
        }
        short handle = (short) (System.currentTimeMillis() % 0xFFFF);
        cpu.getReg().flags.setCarry(false);
        cpu.getReg().AX.setValue(handle);
    }
}
