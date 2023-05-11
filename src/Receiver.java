import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;
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
    private final File receiverLogFile;
    private FileOutputStream logFOS;
    private InetAddress clientAddress;

    private final DatagramSocket receiverSocket;

    private HashMap<Short, byte[]> dataBuffer;
    private short latestInOrderSeqNo;
    private Random random = new Random();
    private short writeNext = -111;
    private File fileReceived;
    private FileOutputStream recFileFOS;
    private short debug_replyACK = -111;
    private boolean connectionIsEstablished = false;
    private boolean receiveFIN = false;

    private String debugMessage;
    private boolean writeNextHasBeenInit = false;
    private short seqNoOfSYN;
    private short seqNoOfFIN;

    public Receiver(int receiverPort, int senderPort, String filename, float flp, float rlp) throws IOException {
        this.receiverPort = receiverPort;
        this.senderPort = senderPort;
        this.filename = filename;
        this.flp = flp;
        this.rlp = rlp;
        this.serverAddress = InetAddress.getByName(address);
        this.dataBuffer = new HashMap<>();
        this.fileReceived = new File(System.getProperty("user.dir")
                + System.getProperty("file.separator") + filename);
        if (!fileReceived.exists()) {
            fileReceived.createNewFile();
        }
        this.recFileFOS = new FileOutputStream(fileReceived);
        this.receiverLogFile = new File(System.getProperty("user.dir")
                + System.getProperty("file.separator") + "receiverLog.txt");
        receiverLogFile.createNewFile();
        this.logFOS = new FileOutputStream(receiverLogFile);

        String temp = "received message are stored in " + filename + "\n"
                + "receiver port: " + receiverPort
                + ", sender port: " + senderPort + "\n"
                + "incoming packets loss probability is set to " + flp + "\n"
                + "outgoing packets loss probability is set to " + rlp + "\n\n";
        System.out.print(temp);
        logFOS.write(temp.getBytes());

        // init the UDP socket
        // define socket for the server side and bind address
        this.receiverSocket = new DatagramSocket(receiverPort, serverAddress);
    }

    private boolean randomDropIncomingData() {
        return random.nextFloat() < this.flp;
    }

    private boolean randomDropACK() {
        return random.nextFloat() < this.rlp;
    }

    private void writeDataIntoFile() throws IOException {
        if (!dataBuffer.containsKey(writeNext)) {
            return;
        }

        while (this.writeNext <= this.latestInOrderSeqNo) {
            byte[] data = dataBuffer.get(this.writeNext);
            recFileFOS.write(data);
            recFileFOS.flush();
            debugMessage = "write content: " + Arrays.toString(data) + "\n";
            System.out.print(debugMessage);
            logFOS.write(debugMessage.getBytes());
            writeNext += data.length;
        }
    }

    public void run() throws IOException, InterruptedException {

        while (true) {
            // try to receive any incoming message from the sender
            byte[] buffer = new byte[BUFFERSIZE];
            DatagramPacket incomingPacket =
                    new DatagramPacket(buffer, buffer.length);
            receiverSocket.receive(incomingPacket);

            /*
            //todo: comment this
            //@ manual control packet lost, for debug usage
            String dropOption = Utils.scanDropOption();
            boolean dropIncomingData = dropOption.charAt(0) == 'd';
            boolean dropACK = dropOption.charAt(1) == 'd';
             */

            //@random control packet lost
            boolean dropIncomingData = randomDropIncomingData();
            boolean dropACK = randomDropACK();

            byte[] stpSegment = incomingPacket.getData();
            short recSeqNo = Utils.getSeqNo(stpSegment);
            short recType = Utils.getType(stpSegment);
            byte[] recData = Utils.getData(stpSegment);
            recData = filterNullValue(recData);
            this.clientAddress = incomingPacket.getAddress();

            if (dropIncomingData) {
                debugMessage = "drop packet with seqNo " + recSeqNo + "\n";
                System.out.print(debugMessage);
                logFOS.write(debugMessage.getBytes());
                continue;
            }

            debugMessage = "receive pkt with seqNo " + recSeqNo
                    + " ,content: " + Arrays.toString(recData) + "\n";
            System.out.print(debugMessage);
            logFOS.write(debugMessage.getBytes());

            DatagramPacket replyPacket = recDataAndCreateReplyPacket(recType, recSeqNo, recData);
            if (!this.writeNextHasBeenInit &&
                    this.latestInOrderSeqNo != this.seqNoOfSYN) {
                this.writeNext = (short) (this.seqNoOfSYN + 1);
                this.writeNextHasBeenInit = true;
            }
            writeDataIntoFile();

            if (dropACK) {
                debugMessage = "drop ACK " + debug_replyACK + "\n";
                System.out.print(debugMessage);
                logFOS.write(debugMessage.getBytes());
                continue;
            }

            debugMessage = "sending ack " + debug_replyACK + "\n";
            System.out.print(debugMessage);
            logFOS.write(debugMessage.getBytes());
            receiverSocket.send(replyPacket);

            if (this.receiveFIN) {
                debugMessage = "ACK of FIN has been sent, " +
                        "to avoid this ACK get lost \n" +
                        "on the way to the sender, receiver will " +
                        "wait for 3 seconds \nfor the possible " +
                        "FIN from sender, then receiver will close.\n";
                System.out.print(debugMessage);
                logFOS.write(debugMessage.getBytes());
                this.receiverSocket.setSoTimeout(3000);
                return;
            }
        }
    }

    private byte[] filterNullValue(byte[] data) {
        int nullValuePosition = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) {
                nullValuePosition = i;
                break;
            }
        }

        // if the data[0] is null, this code also works,
        // create an empty array
        byte[] arr = new byte[nullValuePosition];
        System.arraycopy(data, 0, arr, 0, nullValuePosition);
        return arr;

    }

    private DatagramPacket recDataAndCreateReplyPacket(short recType, short recSeqNo, byte[] recData) throws IOException {
        byte[] replySegment = new byte[0];
        short replyACK;
        switch (recType) {
            case Utils.DATA:
                if (!dataBuffer.containsKey(recSeqNo)) {
                    this.dataBuffer.put(recSeqNo, recData);
                }

                this.latestInOrderSeqNo = updateLatestInOrderSeqNo();

                if (this.latestInOrderSeqNo != this.seqNoOfSYN) {
                    int len = dataBuffer.get(latestInOrderSeqNo).length;
                    replyACK = (short) (this.latestInOrderSeqNo + len);
                } else {
                    replyACK = (short) (this.latestInOrderSeqNo + 1);
                }
                replySegment = Utils.createSTPSegment(Utils.ACK, replyACK, "".getBytes());
                this.debug_replyACK = replyACK;
                break;

            case Utils.SYN:
                this.seqNoOfSYN = recSeqNo;
                this.latestInOrderSeqNo = this.seqNoOfSYN;
                replyACK = (short) (recSeqNo + 1);
                replySegment = Utils.createSTPSegment(Utils.ACK,
                        replyACK, "".getBytes());
                this.connectionIsEstablished = true;
                this.debug_replyACK = replyACK;
                break;

            case Utils.FIN:
                this.seqNoOfFIN = recSeqNo;
                replyACK = (short) (recSeqNo + 1);
                replySegment = Utils.createSTPSegment(Utils.ACK,
                        replyACK, "".getBytes());
                this.debug_replyACK = replyACK;
                this.receiveFIN = true;
                break;

            case Utils.RESET:
                debugMessage = "receive RESET, closing...\n";
                System.out.print(debugMessage);
                logFOS.write(debugMessage.getBytes());
                System.exit(0);
        }

        return createSTPPacket(replySegment);
    }

    private short updateLatestInOrderSeqNo() {
        if (this.latestInOrderSeqNo == this.seqNoOfSYN) {
            short nextSeqNo = (short) (this.latestInOrderSeqNo + 1);
            if (this.dataBuffer.containsKey(nextSeqNo)) {
                this.latestInOrderSeqNo = nextSeqNo;
            } else {
                //otherwise, do not change the latestInOrderSeqNo
                return this.latestInOrderSeqNo;
            }
        }

        while (true) {
            int len = dataBuffer.get(latestInOrderSeqNo).length;
            short nextSeqNo = (short) (latestInOrderSeqNo + len);
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
