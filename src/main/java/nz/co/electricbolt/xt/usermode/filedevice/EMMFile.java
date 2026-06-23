package nz.co.electricbolt.xt.usermode.filedevice;

import nz.co.electricbolt.xt.usermode.AccessMode;
import nz.co.electricbolt.xt.usermode.SharingMode;

public class EMMFile extends BaseFile {

    public EMMFile(final AccessMode accessMode, final SharingMode sharingMode, final boolean inheritenceFlag) {
        super("EMMXXXX0", accessMode, sharingMode, inheritenceFlag);
    }

    @Override
    public boolean open() {
        return true;
    }

    @Override
    public boolean close() {
        return false;
    }

    public short getDeviceInformationWord() {
        return (short) 0x0080;
    }
}
