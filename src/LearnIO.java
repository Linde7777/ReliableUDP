import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class LearnIO {
    private static void printANum(BlockingQueue<String> queue) throws InterruptedException {
        while (true) {
            Thread.sleep(1500);
            queue.put("12");
        }
    }

    public static void main(String[] args) throws InterruptedException {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        Thread printThread = new Thread(() -> {
            try {
                printANum(queue);
            } catch (InterruptedException e) {
            }
        });
        printThread.start();
        Scanner scanner = new Scanner(System.in);
        System.out.println("Enter a number: ");
        String num = scanner.next();
        System.out.println("receive number " + num);
        while (true) {
            String output = queue.take();
            System.out.println("Received output: " + output);
        }
    }
}