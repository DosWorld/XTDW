package nz.co.electricbolt.xt.usermode;

import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.cpu.Memory;
import nz.co.electricbolt.xt.cpu.SegOfs;

import java.io.FileInputStream;
import java.io.IOException;

public class ProgramLoader {

    private final CPU cpu;

    public ProgramLoader(final CPU cpu) {
        this.cpu = cpu;
    }

    public void load(final String path) {
        load(path, (short) 0x0090);
    }

    public void load(final String path, final short pspSegment) {
        try (final FileInputStream fis = new FileInputStream(path)) {
            final byte[] buf = fis.readAllBytes();

            if ((buf.length < 0x1C) || buf[0x00] != 'M' || buf[0x01] != 'Z') {
                // COM file
                if (buf.length == 0) {
                    throw new IOException("File " + path + " is not executable (length 0)");
                }

                final int startLinearAddress = new SegOfs(pspSegment, (short) 0x0100).toLinearAddress();
                cpu.getMemory().putLinearData(startLinearAddress, buf, 0, buf.length);
                cpu.getMemory().removePermission(0, startLinearAddress, Memory.PERMISSION_EXECUTE);
                cpu.getMemory().removePermission(startLinearAddress + buf.length, Memory.MEMORY_SIZE - startLinearAddress - buf.length, Memory.PERMISSION_EXECUTE);

                cpu.getReg().SP.setValue((short) 0xFFFF);
                cpu.getReg().SS.setValue(pspSegment);
                cpu.getReg().IP.setValue((short) 0x0100);
                cpu.getReg().CS.setValue(pspSegment);
                cpu.getReg().DS.setValue(pspSegment);
                cpu.getReg().ES.setValue(pspSegment);
                cpu.syncSegmentBases();
            } else {
                // EXE file
                final EXEHeader header = new EXEHeader(buf);
                final short loadSegment = (short) (pspSegment + 0x10); // Program loads at PSP + 0x10 paragraphs

                int totalFileSize;
                if (header.lastBlockSize == 0) {
                    totalFileSize = header.numberOfBlocks * 512;
                } else {
                    totalFileSize = ((header.numberOfBlocks - 1) * 512) + header.lastBlockSize;
                }
                final int headerSize = header.numberOfParagraphsInHeader * 16;
                final int codeSize = totalFileSize - headerSize;

                final int startLinearAddress = new SegOfs(loadSegment, (short) 0x0000).toLinearAddress();
                cpu.getMemory().putLinearData(startLinearAddress, buf, headerSize, codeSize);

                for (int i = 0; i < header.numberOfRelocationEntries; i++) {
                    SegOfs segOfs = header.relocationItem(i);
                    segOfs = new SegOfs((short) (segOfs.getSegment() + loadSegment), segOfs.getOffset());
                    final short value = cpu.getMemory().readWord(segOfs);
                    cpu.getMemory().writeWord(segOfs, (short) (value + loadSegment));
                }

                cpu.getReg().SP.setValue(header.SP);
                cpu.getReg().SS.setValue((short) (header.relativeSS + loadSegment));
                cpu.getReg().IP.setValue(header.IP);
                cpu.getReg().CS.setValue((short) (header.relativeCS + loadSegment));
                cpu.getReg().DS.setValue(pspSegment);
                cpu.getReg().ES.setValue(pspSegment);
                cpu.syncSegmentBases();
            }
        } catch (IOException e) {
            throw new RuntimeException("The program " + path + " could not be read.", e);
        }
    }
}
