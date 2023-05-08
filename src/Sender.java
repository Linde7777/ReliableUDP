import java.net.*;
import java.io.*;
import java.util.Scanner;
import java.util.concurrent.Semaphore;
import java.util.logging.*;

public class Sender {
    private static final short NOTREC = -1;
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

    private Semaphore semaphore;
    private short receivedACKOfSYNPkt;
    private int resentLimit = 3;
    private long SYNSentTime;
    private boolean connectionIsEstablished = false;

    public Sender(int senderPort, int receiverPort, String filename, int maxWin, int rto) throws IOException {
        this.semaphore = new Semaphore(1);
        this.senderPort = senderPort;
        this.receiverPort = receiverPort;
        this.senderAddress = InetAddress.getByName("127.0.0.1");
        this.receiverAddress = InetAddress.getByName("127.0.0.1");
        this.filename = filename;
        this.maxWin = maxWin;
        this.rto = rto;

        // init the UDP socket
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

            if (dropPkt()) {
                continue;
            }

            System.out.println("receive ACK: " + recSeqNo);
            semaphore.acquire();
            boolean condition = !this.connectionIsEstablished;
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
    }

    private void sendSYNAndCheckACK() throws IOException, InterruptedException {
        sendOnePktAndCheckACK(Utils.SYN, this.ISN, Utils.mod(this.ISN + 1));
    }

    private boolean dropPkt() throws InterruptedException {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Enter your choice, d for drop, otherwise not drop:");
        String input = scanner.next();
        return input.equals("d");
    }

    private void sendOnePktAndCheckACK(short type, short seqNo, short expACK) throws IOException, InterruptedException {
        byte[] stpSegment = Utils.createSTPSegment(type, seqNo, "".getBytes());
        DatagramPacket stpPacket = createSTPPacket(stpSegment);

        if (!dropPkt()) {
            senderSocket.send(stpPacket);
            this.SYNSentTime = System.currentTimeMillis();
            System.out.println(Utils.convertTypeNumToString(type) + " sent");
        } else {
            System.out.println(Utils.convertTypeNumToString(type) + " lost");
        }

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

            if (dropPkt()) {
                senderSocket.send(stpPacket);
                this.SYNSentTime = System.currentTimeMillis();
                System.out.println(Utils.convertTypeNumToString(type) + " sent");
            } else {
                System.out.println(Utils.convertTypeNumToString(type) + " lost");
            }

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


    private void sendFINAndCheckACK() {
    }

    private void sendDATAAndCheckACK() {
    }

    private void sendRESETAndDoNotCheckACK() {
    }

    private boolean detect3DupACKs(short seqNo) {
        return false;
    }

    private DatagramPacket createSTPPacket(byte[] stpSegment) {
        return Utils.createSTPPacket(stpSegment, receiverAddress, receiverPort);
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

