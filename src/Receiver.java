/**
 * Sample code for Receiver
 * Java
 * Usage:
 * - You need to compile it first: javac Receiver.java
 * - then run it: java Receiver receiver_port sender_port FileReceived.txt flp rlp
 * coding: utf-8
 * <p>
 * Notes:
 * Try to run the server first with the command:
 * java Receiver 10000 9000 FileReceived.txt 1 1
 * Then run the sender:
 * java Sender 9000 10000 FileToReceived.txt 1000 1
 * <p>
 * Author: Wei Song (Tutor for COMP3331/9331)
 */


import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Receiver {
    /**
     * The server will be able to receive the file from the sender via UDP
     * :param receiver_port: the UDP port number to be used by the receiver to receive PTP segments from the sender.
     * :param sender_port: the UDP port number to be used by the sender to send PTP segments to the receiver.
     * :param filename: the name of the text file into which the text sent by the sender should be stored
     * :param flp: forward loss probability, which is the probability that any segment in the forward direction (Data, FIN, SYN) is lost.
     * :param rlp: reverse loss probability, which is the probability of a segment in the reverse direction (i.e., ACKs) being lost.
     */

    private static final int BUFFERSIZE = 1024;
    private final String address = "127.0.0.1"; // change it to 0.0.0.0 or public ipv4 address if want to test it between different computers
    private final int receiverPort;
    private final int senderPort;
    private final String filename;
    private final float flp;
    private final float rlp;
    private final InetAddress serverAddress;
    private InetAddress clientAddress;

    private final DatagramSocket receiverSocket;

    private HashMap<Short, byte[]> dataBuffer;
    private int latestInOrderSeqNo;

    public Receiver(int receiverPort, int senderPort, String filename, float flp, float rlp) throws IOException {
        this.receiverPort = receiverPort;
        this.senderPort = senderPort;
        this.filename = filename;
        this.flp = flp;
        this.rlp = rlp;
        this.serverAddress = InetAddress.getByName(address);
        this.dataBuffer = new HashMap<>();

        // init the UDP socket
        // define socket for the server side and bind address
        Logger.getLogger(Receiver.class.getName()).log(Level.INFO, "The sender is using the address " + serverAddress + " to receive message!");
        this.receiverSocket = new DatagramSocket(receiverPort, serverAddress);
    }


    public void run() throws IOException, InterruptedException {

        while (true) {
            // try to receive any incoming message from the sender
            byte[] buffer = new byte[BUFFERSIZE];
            DatagramPacket incomingPacket = new DatagramPacket(buffer, buffer.length);
            receiverSocket.receive(incomingPacket);
            System.out.print("drop the incoming data? ");
            boolean dropIncomingData = Utils.scanDropOption();
            System.out.print("drop the ack? ");
            boolean dropACK = Utils.scanDropOption();

            byte[] stpSegment = incomingPacket.getData();
            short recSeqNo = Utils.getSeqNo(stpSegment);
            short recType = Utils.getType(stpSegment);
            byte[] recData = Utils.getData(stpSegment);
            this.clientAddress = incomingPacket.getAddress();

            if (dropIncomingData) {
                continue;
            }

            DatagramPacket replyPacket = createReplyPkt(recType, recSeqNo, recData);

            if (dropACK) {
                continue;
            }

            System.out.println("sending ack");
            receiverSocket.send(replyPacket);
        }
    }

    private DatagramPacket createReplyPkt(short recType, short recSeqNo, byte[] recData) {
        byte[] replySegment = new byte[0];
        switch (recType) {
            case Utils.DATA:
                if (!dataBuffer.containsKey(recSeqNo)) {
                    this.dataBuffer.put(recSeqNo, recData);
                }
                this.latestInOrderSeqNo = updateLatestInOrderSeqNo(dataBuffer,
                        this.latestInOrderSeqNo);
                replySegment = Utils.createSTPSegment(Utils.ACK,
                        (short) latestInOrderSeqNo, "".getBytes());
                break;
            case Utils.SYN:
                replySegment = Utils.createSTPSegment(Utils.ACK,
                        (short) (recSeqNo + 1), "".getBytes());
                break;
            case Utils.FIN:
                //todo
                break;
        }

        return createSTPPacket(replySegment);
    }

    private int updateLatestInOrderSeqNo(HashMap<Short, byte[]> dataBuffer, int latestInOrderSeqNo) {
        if (dataBuffer.isEmpty()) {
            throw new IllegalArgumentException("dataBuffer is empty");
        }

        if (dataBuffer.size() == 1) {
            return dataBuffer.keySet().iterator().next();
        }

        short nextSeqNo = (short) (latestInOrderSeqNo +
                dataBuffer.get(latestInOrderSeqNo).length);
        while (true) {
            if (dataBuffer.containsKey(nextSeqNo)) {
                latestInOrderSeqNo = nextSeqNo;
            } else {
                break;
            }
        }

        return latestInOrderSeqNo;
    }

    private DatagramPacket createSTPPacket(byte[] stpSegment) {
        return Utils.createSTPPacket(stpSegment, this.clientAddress, senderPort);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Logger.getLogger(Receiver.class.getName()).log(Level.INFO, "Starting Receiver...");
        if (args.length != 5) {
            System.err.println("\n===== Error usage, java Receiver <receiver_port> <sender_port> <FileReceived.txt> <flp> <rlp> =====\n");
            return;
        }

        int receiverPort = Integer.parseInt(args[0]);
        int senderPort = Integer.parseInt(args[1]);
        String filename = args[2];
        float flp = Float.parseFloat(args[3]);
        float rlp = Float.parseFloat(args[4]);

        Receiver receiver = new Receiver(receiverPort, senderPort, filename, flp, rlp);
        receiver.run();
    }
}