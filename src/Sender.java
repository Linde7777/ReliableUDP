import java.net.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Stack;
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
    private final File senderLogFile;
    private FileOutputStream logFOS;
    //todo: random ISN
    private short initSeqNo = 0;
    private String debugMessage;
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
    private final int windowSizeInByte;
    private final int rto;

    private final int BUFFERSIZE = 1024;

    private final int maxSegmentSize = 2;

    private Semaphore semaphore;
    private short receivedACKOfSYNPkt;
    private final int resentLimit = 3;
    private long SYNSentTime;
    private boolean connectionIsEstablished = false;
    private Integer recACKNext = 0;
    private int amountOfDataTransferred = 0;
    private int numOfDataSegmentSent = 0;
    private int numOfRetransmittedDataSegment = 0;
    private int next = 0;
    private int base = 0;
    private boolean allDataHasBeenACKed = false;
    private short receivedACKOfFINPkt = -111;
    private boolean listenThreadShouldBeClosed = false;
    private long FINSentTime;
    private Stack<Short> stack = new Stack<>();
    private Thread mainThread;

    public Sender(int senderPort, int receiverPort, String filename, int windowSizeInByte, int rto) throws IOException {
        this.semaphore = new Semaphore(1);
        this.senderPort = senderPort;
        this.receiverPort = receiverPort;
        this.senderAddress = InetAddress.getByName("127.0.0.1");
        this.receiverAddress = InetAddress.getByName("127.0.0.1");
        this.filename = filename;
        this.windowSizeInByte = windowSizeInByte;
        this.rto = rto;
        this.senderLogFile = new File(System.getProperty("user.dir") +
                System.getProperty("file.separator") + "senderLog.txt");
        this.senderLogFile.createNewFile();
        this.logFOS = new FileOutputStream(senderLogFile);

        String temp = "file to be sent: " + filename + "\n"
                + "senderPort: " + senderPort +
                " receiverPort: " + receiverPort + "\n" +
                "window size in bytes: " + windowSizeInByte
                + " retransmit time: " + rto + " milliseconds\n\n";
        System.out.print(temp);
        logFOS.write(temp.getBytes());

        if (windowSizeInByte % maxSegmentSize != 0) {
            throw new IllegalArgumentException("windowSizeInByte " +
                    "must be a multiple od max segment size");
        }

        this.fileBytes = readBytesFromFile(filename);
        this.dataArr = sliceFileBytesIntoDataWindow(this.fileBytes);
        this.segmentSeqNoArr = createSeqNoArr(this.initSeqNo, this.dataArr);
        this.expectedACKArr = createExpectedACKArr(this.segmentSeqNoArr, this.dataArr);
        this.STPSegmentArr = createSTPSegmentArr(this.dataArr, this.segmentSeqNoArr);
        this.UDPPacketArr = createUDPPacketArr(this.STPSegmentArr);
        this.startTimeArr = new long[UDPPacketArr.length];
        this.receivedACKArr = new short[UDPPacketArr.length];
        Arrays.fill(receivedACKArr, NOT_REC);


        Logger.getLogger(Sender.class.getName()).log(Level.INFO, "The sender is using the address {0}:{1}", new Object[]{senderAddress, senderPort});
        this.senderSocket = new DatagramSocket(senderPort, senderAddress);

        this.mainThread = Thread.currentThread();

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

    /*
    input:
    array index:     0   1   2   3
    expectedACKArr:  3   5   7   9
    receivedACKArr:  3   9  -1  -1
    recACKNext is 1

    all packets in a window has been received,
    but ACK 5 and 7 are lost

    since I use cumulative acknowledge here,
    if I receive ACK 9, that means all packet
    before 9 has been acknowledged.

    output:
    array index:     0   1   2   3
    expectedACKArr:  3   5   7   9
    receivedACKArr:  3   5   7   9
    recACKNext is 3
     */
    private void fixACKGap(short[] receivedACKArr, short[] expectedACKArr, Integer recACKNext) {
        short currRecACK = receivedACKArr[recACKNext];

        int startIndex = recACKNext;
        int endIndex = Arrays.binarySearch(expectedACKArr, currRecACK);
        /*
        @Original Code, Copy arrays manually.
        for (int i = startIndex; i <= endIndex; i++) {
            receivedACKArr[i] = expectedACKArr[i];
        }
         */

        // the code below is equal to the @Original Code
        if (endIndex + 1 - startIndex >= 0) {
            System.arraycopy(expectedACKArr, startIndex, receivedACKArr, startIndex, endIndex + 1 - startIndex);
        }
    }

    private void readThisComment() {
        // we can not increment recACKNext here
        /*
        window length is 4(max segment size is 2,
        window size in byte is 8).
        sender send packet with seqNo 1 3 5 7,
        receiver receive packet with seqNo 1 7,
        and the ACKs of packet 1 and packet 7 do not get lost,
        so in Sender.java,
        expectedACKArr: 3  5  7  9
        receivedACKArr: 3  3 -1 -1

        now Sender.java going to resent packet 3,
        if we increment recACKNext as what we do when
        currRecACK == expRecACK and currRecACK> expRecACK,
        this will happen:
        expectedACKArr: 3  5  7  9
        receivedACKArr: 3  3  5 -1

        that is not we want, we want this:
        expectedACKArr: 3  5  7  9
        receivedACKArr: 3  5 -1 -1
         */
    }

    private void dealingWithRecACKOfDATA(short recSeqNo) throws InterruptedException, IOException {
        receivedACKArr[recACKNext] = recSeqNo;
        short currRecACK = receivedACKArr[recACKNext];
        short expRecACK = expectedACKArr[recACKNext];

        if (currRecACK > expRecACK) {
            fixACKGap(receivedACKArr, expectedACKArr, recACKNext);
            recACKNext += 1;
        } else if (currRecACK == expRecACK) {
            recACKNext += 1;
        } else {
            readThisComment();
            this.stack.push(recSeqNo);
            if (stack.size() == 3) {
                stack.clear();
                // wake up main thread
                debugMessage = "detect 3 duplicate ACK, trying " +
                        "to wake up main thread\n";
                System.out.print(debugMessage);
                logFOS.write(debugMessage.getBytes());
                mainThread.interrupt();
            }
        }
    }

    public void listen() throws IOException, InterruptedException {
        // listen to incoming packets from receiver
        while (true) {
            semaphore.acquire();
            if (this.listenThreadShouldBeClosed) {
                debugMessage = "receive signal from sending thread, closing...\n";
                System.out.print(debugMessage);
                logFOS.write(debugMessage.getBytes());
                semaphore.release();
                senderSocket.close();
                return;
            }
            semaphore.release();

            byte[] receiveData = new byte[BUFFERSIZE];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            senderSocket.receive(receivePacket);
            byte[] stpSegment = receivePacket.getData();
            short recSeqNo = Utils.getSeqNo(stpSegment);
            short type = Utils.getType(stpSegment);

            debugMessage = "receive ACK: " + recSeqNo + "\n";
            System.out.print(debugMessage);
            logFOS.write(debugMessage.getBytes());

            semaphore.acquire();

            boolean recACKIsForDATASegment = this.connectionIsEstablished && !this.allDataHasBeenACKed;
            if (recACKIsForDATASegment) {
                dealingWithRecACKOfDATA(recSeqNo);
            }

            boolean recACKIsForSYNSegment = !this.connectionIsEstablished;
            if (recACKIsForSYNSegment) {
                this.receivedACKOfSYNPkt = recSeqNo;
            }

            boolean recACKIsForFINSegment = connectionIsEstablished && this.allDataHasBeenACKed;
            if (recACKIsForFINSegment) {
                this.receivedACKOfFINPkt = recSeqNo;
            }
            semaphore.release();


        }
    }

    public void run() throws IOException, InterruptedException {
        sendSYNAndCheckACK();
        sendDATAAndCheckACK();
        sendFINAndCheckACK();
    }

    private void sendSYNAndCheckACK() throws IOException, InterruptedException {
        sendOnePktAndCheckACK(Utils.SYN, this.initSeqNo, Utils.mod(this.initSeqNo + 1));
    }

    // retransmit unacknowledged packet at most this.resentLimit times
    private void sendOnePktAndCheckACK(short type, short seqNo, short expACK) throws IOException, InterruptedException {
        if (!(type == Utils.SYN || type == Utils.FIN)) {
            throw new IllegalArgumentException("type should be SYN or FIN");
        }

        byte[] stpSegment = Utils.createSTPSegment(type, seqNo, "".getBytes());
        DatagramPacket stpPacket = createUDPPacket(stpSegment);

        debugMessage = "sending " + Utils.convertTypeNumToString(type)
                + " pkt with seqNo " + seqNo + "\n";
        System.out.print(debugMessage);
        logFOS.write(debugMessage.getBytes());
        senderSocket.send(stpPacket);
        if (type == Utils.SYN) {
            this.SYNSentTime = System.currentTimeMillis();
        } else {
            this.FINSentTime = System.currentTimeMillis();
        }

        Thread.sleep(this.rto);

        boolean shouldRetransmit;
        semaphore.acquire();
        if (type == Utils.SYN) {
            shouldRetransmit = this.receivedACKOfSYNPkt != expACK;
        } else {
            shouldRetransmit = this.receivedACKOfFINPkt != expACK;
        }
        semaphore.release();

        int resentCount = 0;
        while (shouldRetransmit) {
            if (resentCount > this.resentLimit) {
                debugMessage = "sending Reset...\n";
                System.out.print(debugMessage);
                logFOS.write(debugMessage.getBytes());
                sendRESETAndDoNotCheckACK();
                semaphore.acquire();
                this.listenThreadShouldBeClosed = true;
                semaphore.release();
                debugMessage = "calling System.exit...\n";
                System.out.print(debugMessage);
                logFOS.write(debugMessage.getBytes());
                System.exit(0);
            }

            debugMessage = "resending " + Utils.convertTypeNumToString(type)
                    + " pkt with seqNo " + seqNo + "\n";
            System.out.print(debugMessage);
            logFOS.write(debugMessage.getBytes());
            senderSocket.send(stpPacket);
            if (type == Utils.SYN) {
                this.SYNSentTime = System.currentTimeMillis();
            } else {
                this.FINSentTime = System.currentTimeMillis();
            }

            Thread.sleep(this.rto);
            resentCount += 1;

            semaphore.acquire();
            if (type == Utils.SYN) {
                shouldRetransmit = this.receivedACKOfSYNPkt != expACK;
            } else {
                shouldRetransmit = this.receivedACKOfFINPkt != expACK;
            }
            semaphore.release();
        }

        semaphore.acquire();
        if (type == Utils.SYN) {
            this.connectionIsEstablished = true;
        } else {
            this.listenThreadShouldBeClosed = true;
        }
        semaphore.release();
    }

    // this function doesn't have a limit for retransmit packet
    private void sendAllPacketsInWindow(int numOfSegInWindow) throws IOException {
        while (this.next < this.base + numOfSegInWindow) {
            debugMessage = "sending pkt with seqNo " + segmentSeqNoArr[this.next]
                    + ", content: " + Arrays.toString(dataArr[next]) + "\n";
            System.out.print(debugMessage);
            logFOS.write(debugMessage.getBytes());
            senderSocket.send(this.UDPPacketArr[this.next]);
            this.startTimeArr[this.next] = System.currentTimeMillis();
            this.amountOfDataTransferred += dataArr[this.next].length;
            this.numOfDataSegmentSent += 1;
            this.next += 1;
        }
    }

    private void resentUnACKPacketsInWindow(int numOfSegInWindow) throws InterruptedException, IOException {
        int flag = numOfSegInWindow;
        while (flag > 0) {
            semaphore.acquire();
            boolean needToBeResent = receivedACKArr[this.base] < expectedACKArr[this.base];
            semaphore.release();
            if (needToBeResent) {
                debugMessage = "resending pkt with seqNo "
                        + segmentSeqNoArr[this.base] + "\n";
                System.out.print(debugMessage);
                logFOS.write(debugMessage.getBytes());
                senderSocket.send(this.UDPPacketArr[this.base]);
                this.startTimeArr[this.base] = System.currentTimeMillis();
                this.numOfRetransmittedDataSegment += 1;
                Thread.sleep(this.rto);
            } else {
                this.base += 1;
                flag -= 1;
            }
        }
    }

    private void sendDATAAndCheckACK() throws IOException, InterruptedException {
        while (this.base < this.UDPPacketArr.length) {
            int numOfSegInWindow = Math.min(windowSizeInByte / maxSegmentSize, UDPPacketArr.length - base);
            // in this example, maxWin is 6 bytes, MSS is 2 bytes,
            // so generally the num of segments in a window is 3(maxWin/MSS),
            // but in the rightmost window, the num of segments may less than 3

            sendAllPacketsInWindow(numOfSegInWindow);

            try {
                Thread.sleep(this.rto);
            } catch (InterruptedException e) {
                debugMessage = "main thread wake up, starting fast-retransmit\n";
                System.out.print(debugMessage);
                logFOS.write(debugMessage.getBytes());
                // stop sleeping
            }
            /*
            you might think this will be a possible scenario
            // e.g.
            // startTimeArr:    10.1    10.3    10.5
            // after sending all segments in a window, current time is 10.7,
            // we need to sleep until 11.1s (rto is 1 second)

            indeed, the startTimeArr will be: 10.1  10.1  10.1
            so here we just need to directly sleep for this.rto seconds
             */

            resentUnACKPacketsInWindow(numOfSegInWindow);

        }

        semaphore.acquire();
        this.allDataHasBeenACKed = true;
        semaphore.release();
    }

    private void sendFINAndCheckACK() throws IOException, InterruptedException {
        short seqNo = Utils.mod(this.fileBytes.length + 1);
        short expACK = Utils.mod(seqNo + 1);
        sendOnePktAndCheckACK(Utils.FIN, seqNo, expACK);
        debugMessage = "FIN has been ACK, tell listenThread to close, call System.exit\n";
        System.out.print(debugMessage);
        logFOS.write(debugMessage.getBytes());
        System.exit(0);
    }

    private void sendRESETAndDoNotCheckACK() throws IOException {
        // in RESET segment, the seqNo will not be used,
        // but we can't randomly choose a sequence number,
        // since in Utils.createSTPSegment, it needs the sequence number
        // between 0 and 2^16 -1
        byte[] stpSegment = Utils.createSTPSegment(Utils.RESET, (short) (this.fileBytes.length + 1), "".getBytes());
        DatagramPacket packet = createUDPPacket(stpSegment);
        senderSocket.send(packet);
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
        if (fileBytes.length % maxSegmentSize == 0) {
            dataWindowLen = fileBytes.length / maxSegmentSize;
        } else {
            dataWindowLen = fileBytes.length / maxSegmentSize + 1;
        }
        byte[][] dataWindow = new byte[dataWindowLen][];
        int fbIndex = 0; // fileBytes 's index
        int dwIndex = 0;// dataWindow 's index
        while (fbIndex < fileBytes.length) {
            int copyLen = Math.min(fileBytes.length - fbIndex, maxSegmentSize);
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

