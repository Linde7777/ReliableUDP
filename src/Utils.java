import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Scanner;

public class Utils {
    static final short DATA = 0;
    static final short ACK = 1;
    static final short SYN = 2;
    static final short FIN = 3;
    static final short RESET = 4;

    static String outputFormat = "%s %.6f \t%s %d %d\n";

    //if scan dd, that means drop the incoming packet and the reply ACK.
    public static String scanDropOption() throws InterruptedException {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("Enter your option. You can enter [help] to get help: ");
            String input = scanner.next();
            if (input.equals("nn") || input.equals("dd")
                    || input.equals("nd") || input.equals("dn")) {
                return input;

            } else if (input.equals("help")) {
                String statement =
                        "If you enter [dd] (drop drop), the receiver will drop\n"
                                + "the incoming packet and the reply ACK\n"
                                + "If you enter [nd] (notDrop drop)\n"
                                + "the receiver will receive the incoming packet\n"
                                + "and drop the reply ACK\n"
                                + "same for the [nn] [dn]\n"
                                + "Indeed [dn] is verbose, if a packet is dropped, "
                                + "the receive wouldn't know it, hence it won't send an ACK";
                System.out.println(statement);
            } else {
                System.out.println("Invalid argument, try again.");
            }
        }
    }


    public static short mod(int seqNo) {
        // short is 2 bytes, aka 16 bits
        return (short) (seqNo % Math.pow(2, 16));
    }

    public static double convertTime(long time) {
        return (time / 100.0);
    }

    public static String convertTypeNumToString(short type) {
        if (type < 0 || type > 4) {
            throw new IllegalArgumentException("type should should between 0 and 4");
        }
        String str = "Unknown type";
        switch (type) {
            case DATA:
                str = "DATA";
                break;
            case ACK:
                str = "ACK";
                break;
            case SYN:
                str = "SYN";
                break;
            case FIN:
                str = "FIN";
                break;
            case RESET:
                str = "RESET";
                break;
        }

        return str;
    }

    public static DatagramPacket createSTPPacket(
            byte[] stpSegment, InetAddress address, int portNum) {

        DatagramPacket packet = new DatagramPacket(stpSegment,
                stpSegment.length, address, portNum);
        return packet;
    }

    public static short getType(byte[] STPSegmentArr) {
        byte[] arr = new byte[2];
        System.arraycopy(STPSegmentArr, 0, arr, 0, 2);
        return ByteBuffer.wrap(arr).getShort();
    }

    public static short getSeqNo(byte[] STPSegmentArr) {
        byte[] arr = new byte[2];
        System.arraycopy(STPSegmentArr, 2, arr, 0, 2);
        return ByteBuffer.wrap(arr).getShort();
    }

    private static byte[] createHeaderArr(short segmentType, short segmentSeqNo) {
        if (segmentType < 0 || segmentType > 5) {
            throw new IllegalArgumentException("Invalid type, it must between 0 and 4");
        }
        if (segmentSeqNo < 0 || segmentSeqNo > Math.pow(2, 16) - 1) {
            throw new IllegalArgumentException("Invalid sequence number, it must between 0 and 2^16-1");
        }
        ByteBuffer buffer;

        byte[] typeArr = new byte[2];
        buffer = ByteBuffer.wrap(typeArr);
        buffer.putShort(segmentType);

        byte[] seqNoArr = new byte[2];
        buffer = ByteBuffer.wrap(seqNoArr);
        buffer.putShort(segmentSeqNo);

        byte[] headerArr = new byte[4];
        System.arraycopy(typeArr, 0, headerArr, 0, typeArr.length);
        System.arraycopy(seqNoArr, 0, headerArr, typeArr.length, seqNoArr.length);

        return headerArr;
    }

    /*
    static byte[] createSTPSegment(byte[] headerArr, String data) {
        byte[] dataArr = data.getBytes();
        return createSTPSegment(headerArr, dataArr);
    }
     */

    public static byte[] createSTPSegment(short segmentType, short segmentSeqNo, byte[] data) {
        byte[] headerArr = createHeaderArr(segmentType, segmentSeqNo);
        byte[] STPSegmentArr = new byte[headerArr.length + data.length];
        System.arraycopy(headerArr, 0, STPSegmentArr, 0, headerArr.length);
        System.arraycopy(data, 0, STPSegmentArr, headerArr.length, data.length);
        return STPSegmentArr;
    }

    public static byte[] getData(byte[] STPSegment) {
        byte[] data = new byte[STPSegment.length - 4];
        System.arraycopy(STPSegment, 4, data, 0, data.length);
        return data;
    }

}
