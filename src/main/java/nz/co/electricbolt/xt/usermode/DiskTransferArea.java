package nz.co.electricbolt.xt.usermode;

import nz.co.electricbolt.xt.cpu.Memory;
import nz.co.electricbolt.xt.cpu.SegOfs;

/**
 * Disk Transfer Area layout used by INT 21h AH=4Eh (Find First) and AH=4Fh (Find Next).
 *
 * <pre>
 *  Offset Size Field
 *  00h    1    drive letter (bit 7 set = remote/network)
 *  01h    11   search template (8.3, space-padded, no dot)
 *  0Ch    1   search attribute mask
 *  0Dh    2   directory entry index (internal id used to resume search)
 *  0Fh    2   reserved (cluster of parent directory)
 *  11h    4   reserved
 *  15h    1   file attribute
 *  16h    2   file time (DOS packed)
 *  18h    2   file date (DOS packed)
 *  1Ah    4   file size in bytes
 *  1Eh    13  ASCIZ filename (uppercased 8.3 with dot, max 12 chars + nul)
 * </pre>
 */
public class DiskTransferArea {

    private static final short DRIVE_LETTER = 0x00;       // byte
    private static final short SEARCH_TEMPLATE = 0x01;    // 11 bytes
    private static final short SEARCH_ATTRIBUTE = 0x0C;   // byte
    private static final short INTERNAL_ID = 0x0D;        // word
    private static final short PARENT_CLUSTER = 0x0F;     // word
    private static final short RESERVED = 0x11;           // 4 bytes
    private static final short FILE_ATTRIBUTE = 0x15;     // byte
    private static final short FILE_TIME = 0x16;          // word
    private static final short FILE_DATE = 0x18;          // word
    private static final short FILE_SIZE = 0x1A;          // dword
    private static final short FILE_NAME = 0x1E;          // 13 bytes ASCIIZ

    private final Memory memory;
    private final short segment;
    private final short offset;

    public DiskTransferArea(final Memory memory, final short segment, final short offset) {
        this.memory = memory;
        this.segment = segment;
        this.offset = offset;
    }

    public short getSegment() {
        return segment;
    }

    public short getOffset() {
        return offset;
    }

    public byte getDriveLetter() {
        return getByte(DRIVE_LETTER);
    }

    public void writeDriveLetter(final byte drive) {
        setByte(DRIVE_LETTER, drive);
    }

    public String getSearchTemplate() {
        final StringBuilder sb = new StringBuilder(11);
        for (int i = 0; i < 11; i++) {
            sb.append((char) (getByte((short) (SEARCH_TEMPLATE + i)) & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Writes the 11-byte 8.3 search template. {@code template} must already be
     * uppercased and space-padded (no dot) — exactly 11 bytes.
     */
    public void writeSearchTemplate(final String template) {
        for (int i = 0; i < 11; i++) {
            final char c = i < template.length() ? template.charAt(i) : ' ';
            setByte((short) (SEARCH_TEMPLATE + i), (byte) c);
        }
    }

    public byte getSearchAttribute() {
        return getByte(SEARCH_ATTRIBUTE);
    }

    public void writeSearchAttribute(final byte attribute) {
        setByte(SEARCH_ATTRIBUTE, attribute);
    }

    public short getInternalId() {
        return getWord(INTERNAL_ID);
    }

    public void setInternalId(final short internalId) {
        setWord(INTERNAL_ID, internalId);
    }

    public void writeParentCluster(final short cluster) {
        setWord(PARENT_CLUSTER, cluster);
    }

    public void writeReserved() {
        setDoubleWord(RESERVED, 0);
    }

    public void writeFileAttribute(final byte fileAttribute) {
        setByte(FILE_ATTRIBUTE, fileAttribute);
    }

    public void writeFileTime(final short fileTime) {
        setWord(FILE_TIME, fileTime);
    }

    public void writeFileDate(final short fileDate) {
        setWord(FILE_DATE, fileDate);
    }

    public void writeFileSize(final int fileSize) {
        setDoubleWord(FILE_SIZE, fileSize);
    }

    public void writeFilename(final String filename) {
        final int max = Math.min(filename.length(), 12);
        for (int i = 0; i < max; i++) {
            setByte((short) (FILE_NAME + i), (byte) filename.charAt(i));
        }
        setByte((short) (FILE_NAME + max), (byte) 0x00);
    }

    private byte getByte(final short dtaOfs) {
        return memory.readByte(new SegOfs(segment, (short) (offset + dtaOfs)));
    }

    private short getWord(final short dtaOfs) {
        return memory.readWord(new SegOfs(segment, (short) (offset + dtaOfs)));
    }

    private void setByte(final short dtaOfs, final byte value) {
        memory.setByte(new SegOfs(segment, (short) (offset + dtaOfs)), value);
    }

    private void setWord(final short dtaOfs, final short value) {
        memory.setWord(new SegOfs(segment, (short) (offset + dtaOfs)), value);
    }

    private void setDoubleWord(final short dtaOfs, final int value) {
        memory.setDoubleWord(new SegOfs(segment, (short) (offset + dtaOfs)), value);
    }
}
