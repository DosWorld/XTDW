package nz.co.electricbolt.xt.usermode.interrupts.dos;

import nz.co.electricbolt.xt.cpu.SegOfs;

import java.io.File;

/**
 * Search session state for INT 21h AH=4Eh/4Fh. The DTA's internal id at
 * offset 0Dh holds the {@code internalId} so FindNext (AH=4Fh) can resume
 * the matching list across calls.
 */
public record FindFileData(short internalId, File[] files, short fileIndex,
                           byte searchAttribute, byte driveLetter,
                           String searchTemplate, SegOfs dtaAddress) {

    public FindFileData advance() {
        return new FindFileData(internalId, files, (short) (fileIndex + 1),
                searchAttribute, driveLetter, searchTemplate, dtaAddress);
    }
}
