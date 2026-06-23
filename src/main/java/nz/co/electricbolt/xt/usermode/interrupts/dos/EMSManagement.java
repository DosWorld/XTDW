package nz.co.electricbolt.xt.usermode.interrupts.dos;

import nz.co.electricbolt.xt.usermode.interrupts.annotations.AL;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.BX;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.DI;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.DX;
import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.cpu.EMS;
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

    @Interrupt(interrupt = 0x67, function = 0x44, description = "EMS: Map/Unmap pages")
    public void emsMapPage(CPU cpu, final @AL byte subfunction, final @DX short physicalPageFrame,
                           final @BX short handle, final @DI short logicalPage) {
        EMS ems = ems();
        if (ems == null) {
            cpu.getReg().AH.setValue((byte) 0x80);
            return;
        }
        int err;
        if (subfunction == 0x00) {
            err = ems.mapPage(handle & 0xFFFF, logicalPage & 0xFFFF, physicalPageFrame & 0xFFFF);
        } else if (subfunction == 0x01) {
            err = ems.unmapPage(physicalPageFrame & 0xFFFF);
        } else {
            cpu.getReg().AH.setValue((byte) 0x8C);
            return;
        }
        cpu.getReg().AH.setValue((byte) (err == 0 ? 0 : err));
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

    @Interrupt(interrupt = 0x67, function = 0x4B, description = "EMS: Get handle pages")
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
}
