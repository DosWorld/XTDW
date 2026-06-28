package nz.co.electricbolt.xt.usermode.interrupts.dos;

import nz.co.electricbolt.xt.usermode.interrupts.annotations.AL;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.BX;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.DS;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.DX;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.SI;
import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.cpu.EMS;
import nz.co.electricbolt.xt.cpu.Memory;
import nz.co.electricbolt.xt.cpu.SegOfs;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.Interrupt;

public class EMSManagement {

    public EMSManagement() {
    }

    private static EMS ems() {
        return EMS.getInstance();
    }

    @Interrupt(interrupt = 0x67, function = 0x40, description = "EMS: Get status")
    public void emsGetStatus(CPU cpu) {
        if (ems() == null) {
            cpu.getReg().AH.setValue((byte) 0x80);
            return;
        }
        cpu.getReg().AH.setValue((byte) 0x00);
    }

    @Interrupt(interrupt = 0x67, function = 0x41, description = "EMS: Get page frame segment")
    public void emsGetPageFrame(CPU cpu) {
        EMS ems = ems();
        if (ems == null) {
            cpu.getReg().AH.setValue((byte) 0x80);
            return;
        }
        cpu.getReg().AH.setValue((byte) 0x00);
        cpu.getReg().BX.setValue((short) ems.getPageFrameSegment());
    }

    @Interrupt(interrupt = 0x67, function = 0x42, description = "EMS: Get unallocated page count")
    public void emsGetUnallocatedPages(CPU cpu) {
        EMS ems = ems();
        if (ems == null) {
            cpu.getReg().AH.setValue((byte) 0x80);
            return;
        }
        cpu.getReg().AH.setValue((byte) 0x00);
        cpu.getReg().BX.setValue((short) ems.getFreePages());
        cpu.getReg().DX.setValue((short) ems.getTotalPages());
    }

    @Interrupt(interrupt = 0x67, function = 0x43, description = "EMS: Allocate pages")
    public void emsAllocatePages(CPU cpu, final @BX short pages) {
        EMS ems = ems();
        if (ems == null) {
            cpu.getReg().AH.setValue((byte) 0x80);
            return;
        }
        int[] handle = new int[1];
        int err = ems.allocatePages(pages & 0xFFFF, handle);
        if (err != 0) {
            cpu.getReg().AH.setValue((byte) err);
        } else {
            cpu.getReg().AH.setValue((byte) 0x00);
            cpu.getReg().DX.setValue((short) handle[0]);
        }
    }

    @Interrupt(interrupt = 0x67, function = 0x44, description = "EMS: Map handle page")
    public void emsMapPage(CPU cpu, final @AL byte physicalPage,
                           final @BX short logicalPage, final @DX short handle) {
        // LIM EMS 3.2 function 44h "Map Handle Page":
        //   AL = physical page (page-frame window slot), BX = logical page, DX = handle.
        // Logical page 0xFFFF unmaps the physical page.
        EMS ems = ems();
        if (ems == null) {
            cpu.getReg().AH.setValue((byte) 0x80);
            return;
        }
        int err = ems.mapPage(handle & 0xFFFF, logicalPage & 0xFFFF, physicalPage & 0xFF);
        cpu.getReg().AH.setValue((byte) (err == 0 ? 0 : err));
    }

    @Interrupt(interrupt = 0x67, function = 0x57, description = "EMS: Move/Exchange memory region")
    public void emsMoveMemoryRegion(CPU cpu, final @AL byte subfunction,
                                    final @DS @SI SegOfs descriptor) {
        // LIM EMS 4.0 function 57h. DS:SI -> 18-byte parameter block:
        //   +00 LONGINT region length (bytes)
        //   +04 BYTE  source type (0=conventional, 1=expanded)
        //   +05 WORD  source handle
        //   +07 WORD  source offset
        //   +09 WORD  source segment (conv) or logical page (exp)
        //   +0B BYTE  dest type
        //   +0C WORD  dest handle
        //   +0E WORD  dest offset
        //   +10 WORD  dest segment (conv) or logical page (exp)
        // AL=00h move, AL=01h exchange.
        EMS ems = ems();
        if (ems == null) {
            cpu.getReg().AH.setValue((byte) 0x80);
            return;
        }
        final Memory mem = cpu.getMemory();
        int base = descriptor.toLinearAddress();

        long len = getLinearDword(mem, base) & 0xFFFFFFFFL;
        int srcType = getLinearByte(mem, base + 0x04);
        int srcHandle = getLinearWord(mem, base + 0x05);
        int srcOffset = getLinearWord(mem, base + 0x07);
        int srcSegPage = getLinearWord(mem, base + 0x09);
        int dstType = getLinearByte(mem, base + 0x0B);
        int dstHandle = getLinearWord(mem, base + 0x0C);
        int dstOffset = getLinearWord(mem, base + 0x0E);
        int dstSegPage = getLinearWord(mem, base + 0x10);

        boolean exchange = (subfunction == 0x01);

        // Validate handles up front (error 0x83 = invalid handle).
        if (srcType == 1 && !ems.handleExists(srcHandle)) {
            cpu.getReg().AH.setValue((byte) 0x83);
            return;
        }
        if (dstType == 1 && !ems.handleExists(dstHandle)) {
            cpu.getReg().AH.setValue((byte) 0x83);
            return;
        }

        for (long i = 0; i < len; i++) {
            int s = readRegionByte(mem, ems, srcType, srcHandle, srcSegPage, srcOffset, i);
            int d = readRegionByte(mem, ems, dstType, dstHandle, dstSegPage, dstOffset, i);
            if (s < 0 || d < 0) {
                cpu.getReg().AH.setValue((byte) 0x8A); // invalid logical page within handle
                return;
            }
            if (exchange) {
                writeRegionByte(mem, ems, srcType, srcHandle, srcSegPage, srcOffset, i, d);
                writeRegionByte(mem, ems, dstType, dstHandle, dstSegPage, dstOffset, i, s);
            } else {
                writeRegionByte(mem, ems, dstType, dstHandle, dstSegPage, dstOffset, i, s);
            }
        }
        cpu.getReg().AH.setValue((byte) 0x00);
    }

    private static int readRegionByte(Memory mem, EMS ems, int type, int handle, int segPage,
                                      int offset, long i) {
        if (type == 0) {
            int lin = ((segPage & 0xFFFF) << 4) + offset + (int) i;
            return mem.getLinearByte(lin & 0xFFFFF) & 0xFF;
        } else {
            return ems.readLogical(handle, segPage, offset + (int) i);
        }
    }

    private static void writeRegionByte(Memory mem, EMS ems, int type, int handle, int segPage,
                                        int offset, long i, int value) {
        if (type == 0) {
            int lin = ((segPage & 0xFFFF) << 4) + offset + (int) i;
            mem.setLinearByte(lin & 0xFFFFF, (byte) (value & 0xFF));
        } else {
            ems.writeLogical(handle, segPage, offset + (int) i, value);
        }
    }

    private static int getLinearByte(Memory mem, int linear) {
        return mem.getLinearByte(linear & 0xFFFFF) & 0xFF;
    }

    private static int getLinearWord(Memory mem, int linear) {
        int lo = getLinearByte(mem, linear);
        int hi = getLinearByte(mem, linear + 1);
        return (hi << 8) | lo;
    }

    private static long getLinearDword(Memory mem, int linear) {
        long lo = getLinearWord(mem, linear);
        long hi = getLinearWord(mem, linear + 2);
        return (hi << 16) | lo;
    }

    @Interrupt(interrupt = 0x67, function = 0x45, description = "EMS: Free pages")
    public void emsFreePages(CPU cpu, final @DX short handle) {
        EMS ems = ems();
        if (ems == null) {
            cpu.getReg().AH.setValue((byte) 0x80);
            return;
        }
        int err = ems.freePages(handle & 0xFFFF);
        cpu.getReg().AH.setValue((byte) (err == 0 ? 0 : err));
    }

    @Interrupt(interrupt = 0x67, function = 0x46, description = "EMS: Get version")
    public void emsGetVersion(CPU cpu) {
        EMS ems = ems();
        if (ems == null) {
            cpu.getReg().AH.setValue((byte) 0x80);
            return;
        }
        int version = ems.getVersion();
        cpu.getReg().AH.setValue((byte) 0x00);
        cpu.getReg().AL.setValue((byte) (version & 0xFF));
    }

    @Interrupt(interrupt = 0x67, function = 0x4C, description = "EMS: Get handle pages")
    public void emsGetHandlePages(CPU cpu, final @DX short handle) {
        EMS ems = ems();
        if (ems == null) {
            cpu.getReg().AH.setValue((byte) 0x80);
            return;
        }
        int[] pages = new int[1];
        int err = ems.getHandlePages(handle & 0xFFFF, pages);
        if (err != 0) {
            cpu.getReg().AH.setValue((byte) err);
        } else {
            cpu.getReg().AH.setValue((byte) 0x00);
            cpu.getReg().BX.setValue((short) pages[0]);
        }
    }

    @Interrupt(interrupt = 0x67, function = 0x51, description = "EMS: Reallocate pages")
    public void emsReallocatePages(CPU cpu, final @BX short pages, final @DX short handle) {
        EMS ems = ems();
        if (ems == null) {
            cpu.getReg().AH.setValue((byte) 0x80);
            return;
        }
        int[] pagesOut = new int[1];
        int err = ems.reallocatePages(handle & 0xFFFF, pages & 0xFFFF, pagesOut);
        cpu.getReg().AH.setValue((byte) (err == 0 ? 0 : err));
        cpu.getReg().BX.setValue((short) pagesOut[0]);
    }
}
