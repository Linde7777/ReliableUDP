import java.net.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.logging.*;

public class Sender {
    private static final short NOT_REC = -1;
    private final byte[] fileBytes;
    private final byte[][] dataArr;
    private final short[] segmentSeqNoArr;
    private final short[] expectedACKArr;
    private final byte[][] STPSegmentArr;
    private final DatagramPacket[] UDPPacketArr;
    private final long[] startTimeArr;
    private final short[] receivedACKArr;
    //todo: random ISN
    private short ISN = 0;
    /**
     * The Sender will be able to connect the Receiver via UDP
     * :param sender_port: the UDP port number to be used by the sender to send PTP segments to the receiver
     * :param receiver_port: the UDP port number on which receiver is expecting to receive PTP segments from the sender
     * :param filename: the name of the text file that must be transferred from sender to receiver using your reliable transport protocol.
     * :param max_win: the maximum window size in bytes for the sender window.
     * :param rot: the value of the retransmission timer in milliseconds. This should be an unsigned integer.
     */

    private final int senderPort;
    private final int receiverPort;
    private final InetAddress senderAddress;
    private final InetAddress receiverAddress;
    private final DatagramSocket senderSocket;
    private final String filename;
    private final int maxWin;
    private final int rto;

    private final int BUFFERSIZE = 1024;

    private int MSS = 2;

    private Semaphore semaphore;
    private short receivedACKOfSYNPkt;
    private int resentLimit = 3;
    private long SYNSentTime;
    private boolean connectionIsEstablished = false;
    private int recACKIndex = 0;
    private int amountOfDataTransferred = 0;
    private int numOfDataSegmentSent = 0;
    private int numOfRetransmittedDataSegment = 0;

    public Sender(int senderPort, int receiverPort, String filename, int maxWin, int rto) throws IOException {
        this.semaphore = new Semaphore(1);
        this.senderPort = senderPort;
        this.receiverPort = receiverPort;
        this.senderAddress = InetAddress.getByName("127.0.0.1");
        this.receiverAddress = InetAddress.getByName("127.0.0.1");
        this.filename = filename;
        this.maxWin = maxWin;
        this.rto = rto;

        this.fileBytes = readBytesFromFile(filename);
        this.dataArr = sliceFileBytesIntoDataWindow(this.fileBytes);
        this.segmentSeqNoArr = createSeqNoArr(this.ISN, this.dataArr);
        this.expectedACKArr = createExpectedACKArr(this.segmentSeqNoArr, this.dataArr);
        this.STPSegmentArr = createSTPSegmentArr(this.dataArr, this.segmentSeqNoArr);
        this.UDPPacketArr = createUDPPacketArr(this.STPSegmentArr);
        this.startTimeArr = new long[UDPPacketArr.length];
        this.receivedACKArr = new short[UDPPacketArr.length];
        Arrays.fill(receivedACKArr, NOT_REC);


        Logger.getLogger(Sender.class.getName()).log(Level.INFO, "The sender is using the address {0}:{1}", new Object[]{senderAddress, senderPort});
        this.senderSocket = new DatagramSocket(senderPort, senderAddress);

        // start the listening sub-thread
        Thread listenThread = new Thread(() -> {
            try {
                listen();
            } catch (IOException | InterruptedException e) {
                // Handle exceptions here
            }
        });
        listenThread.start();

    }


    public void listen() throws IOException, InterruptedException {
        // listen to incoming packets from receiver
        while (true) {
            byte[] receiveData = new byte[BUFFERSIZE];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            senderSocket.receive(receivePacket);
            byte[] stpSegment = receivePacket.getData();
            short recSeqNo = Utils.getSeqNo(stpSegment);
            short type = Utils.getType(stpSegment);

            System.out.println("receive ACK: " + recSeqNo);

            // received ACK is for DATA Segment
            semaphore.acquire();
            boolean condition = this.connectionIsEstablished;
            semaphore.release();
            if (condition) {
                semaphore.acquire();
                receivedACKArr[recACKIndex] = recSeqNo;
                semaphore.release();
                recACKIndex += 1;
            }

            // received ACK is for SYN Segment
            semaphore.acquire();
            condition = !this.connectionIsEstablished;
            semaphore.release();
            if (condition) {
                semaphore.acquire();
                this.receivedACKOfSYNPkt = recSeqNo;
                semaphore.release();
            }


        }
    }

    public void run() throws IOException, InterruptedException {
        sendSYNAndCheckACK();
        sendDATAAndCheckACK();
    }

    private void sendSYNAndCheckACK() throws IOException, InterruptedException {
        sendOnePktAndCheckACK(Utils.SYN, this.ISN, Utils.mod(this.ISN + 1));
    }

    // retransmit unacknowledged packet at most this.resentLimit times
    private void sendOnePktAndCheckACK(short type, short seqNo, short expACK) throws IOException, InterruptedException {
        byte[] stpSegment = Utils.createSTPSegment(type, seqNo, "".getBytes());
        DatagramPacket stpPacket = createUDPPacket(stpSegment);

        senderSocket.send(stpPacket);
        this.SYNSentTime = System.currentTimeMillis();

        Thread.sleep(this.rto);

        semaphore.acquire();
        boolean shouldRetransmit = this.receivedACKOfSYNPkt != expACK;
        semaphore.release();

        int resentCount = 0;
        while (shouldRetransmit) {
            if (resentCount > this.resentLimit) {
                System.out.println("sending Reset...");
                sendRESETAndDoNotCheckACK();
                // todo: tell listen() to stop
                System.out.println("calling System.exit...");
                System.exit(0);
            }

            senderSocket.send(stpPacket);
            this.SYNSentTime = System.currentTimeMillis();

            Thread.sleep(this.rto);
            resentCount += 1;

            semaphore.acquire();
            shouldRetransmit = this.receivedACKOfSYNPkt != expACK;
            semaphore.release();
        }

        semaphore.acquire();
        this.connectionIsEstablished = true;
        semaphore.release();
    }

    // this function doesn't have a limit for retransmit packet
    private void sendPacketsInWindow(Integer next, Integer base, int numOfSegInWindow) throws IOException {
        while (next < base + numOfSegInWindow) {
            System.out.println("sending pkt with seqNo " + segmentSeqNoArr[next]);
            senderSocket.send(this.UDPPacketArr[next]);
            this.startTimeArr[next] = System.currentTimeMillis();
            this.amountOfDataTransferred += dataArr[next].length;
            this.numOfDataSegmentSent += 1;
            next += 1;
        }
    }

    private void resentUnACKPacketsInWindow(Integer next, Integer base, int numOfSegInWindow) throws InterruptedException, IOException {
        int flag = numOfSegInWindow;
        while (flag > 0) {
            semaphore.acquire();
            boolean needToBeResent = receivedACKArr[base] < expectedACKArr[base];
            semaphore.release();
            if (needToBeResent) {
                senderSocket.send(this.UDPPacketArr[base]);
                this.startTimeArr[base] = System.currentTimeMillis();
                this.numOfRetransmittedDataSegment += 1;
                Thread.sleep(this.rto);
            } else {
                base += 1;
                flag -= 1;
            }
        }
    }

    private void sendDATAAndCheckACK() throws IOException, InterruptedException {
        Integer next = 0;
        Integer base = 0;

        while (base < this.UDPPacketArr.length) {
            int numOfSegInWindow = Math.min(maxWin / MSS, UDPPacketArr.length - base);
            // in this example, maxWin is 6 bytes, MSS is 2 bytes,
            // so generally the num of segments in a window is 3(maxWin/MSS),
            // but in the rightmost window, the num of segments may less than 3

            sendPacketsInWindow(next, base, numOfSegInWindow);

            long durationSinceOldestPktHasBeenSent = System.currentTimeMillis() - startTimeArr[base];
            long sleepDuration = this.rto - durationSinceOldestPktHasBeenSent;
            if (sleepDuration > 0) {
                Thread.sleep(sleepDuration);
            }
            // e.g.
            // startTimeArr:    10.1    10.3    10.5
            // after sending all segments in a window, current time is 10.7,
            // we need to sleep until 11.1s (rto is 1 second)

            resentUnACKPacketsInWindow(next, base, numOfSegInWindow);

        }


    }

    private void sendFINAndCheckACK() {

    }

    private void sendRESETAndDoNotCheckACK() {
    }

    private boolean detect3DupACKs(short seqNo) {
        return false;
    }

    private DatagramPacket createUDPPacket(byte[] stpSegment) {
        return Utils.createSTPPacket(stpSegment, receiverAddress, receiverPort);
    }


    private byte[][] createSTPSegmentArr(byte[][] dataArr, short[] segmentSeqNoArr) {
        byte[][] STPSegmentArr = new byte[dataArr.length][];
        for (int i = 0; i < STPSegmentArr.length; i++) {
            STPSegmentArr[i] = Utils.createSTPSegment(Utils.DATA, segmentSeqNoArr[i], dataArr[i]);
        }
        return STPSegmentArr;
    }

    private DatagramPacket[] createUDPPacketArr(byte[][] STPSegmentArrs) {
        DatagramPacket[] UDPPacketArrs = new DatagramPacket[STPSegmentArrs.length];
        for (int i = 0; i < UDPPacketArrs.length; i++) {
            DatagramPacket UDPPacket = createUDPPacket(STPSegmentArrs[i]);
            UDPPacketArrs[i] = UDPPacket;
        }

        return UDPPacketArrs;
    }

    private short[] createExpectedACKArr(short[] segmentSeqNoArr, byte[][] dataArr) {
        short[] expectedACKArr = new short[dataArr.length];
        for (int i = 0; i < expectedACKArr.length; i++) {
            expectedACKArr[i] = (short) (segmentSeqNoArr[i] + dataArr[i].length);
        }
        return expectedACKArr;
    }

    private byte[] readBytesFromFile(String filename) throws IOException {
        File file = new File(System.getProperty("user.dir") + System.getProperty("file.separator") + filename);
        return Files.readAllBytes(Path.of(file.getPath()));
    }

    // e.g. fileBytes.length is 3790,
    // the dataWindow's length should be 4:
    // [1000][1000][1000][790]
    private byte[][] sliceFileBytesIntoDataWindow(byte[] fileBytes) {
        int dataWindowLen = -1;
        if (fileBytes.length % MSS == 0) {
            dataWindowLen = fileBytes.length / MSS;
        } else {
            dataWindowLen = fileBytes.length / MSS + 1;
        }
        byte[][] dataWindow = new byte[dataWindowLen][];
        int fbIndex = 0; // fileBytes 's index
        int dwIndex = 0;// dataWindow 's index
        while (fbIndex < fileBytes.length) {
            int copyLen = Math.min(fileBytes.length - fbIndex, MSS);
            dataWindow[dwIndex] = new byte[copyLen];
            System.arraycopy(fileBytes, fbIndex, dataWindow[dwIndex], 0, copyLen);
            fbIndex += copyLen;
            dwIndex += 1;
        }
        return dataWindow;
    }

    private short[] createSeqNoArr(short ISN, byte[][] dataArr) {
        short[] seqNoArr = new short[dataArr.length];
        // SYN segment's seqNo is ISN, so the first
        // Data Segment's seqNo is ISN+1
        seqNoArr[0] = Utils.mod(ISN + 1);
        for (int i = 1; i < seqNoArr.length; i++) {
            seqNoArr[i] = Utils.mod(seqNoArr[i - 1] + dataArr[i - 1].length);
        }
        return seqNoArr;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Logger.getLogger(Sender.class.getName()).setLevel(Level.ALL);

        if (args.length != 5) {
            System.err.println("\n===== Error usage, java Sender senderPort receiverPort FileReceived.txt maxWin rto ======\n");
            System.exit(0);
        }

        Sender sender = new Sender(Integer.parseInt(args[0]), Integer.parseInt(args[1]), args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4]));
        sender.run();
    }

}

