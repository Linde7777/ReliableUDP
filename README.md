# ReliableUDP
Mimicking some features of TCP.  
- Implemented cumulative acknowledgment
- Implemented sliding window
- On the sender side, ran a thread for sending packets and a thread for receiving ACKs, used semaphores to solve race condition
- When the receiving thread detected 3 duplicate ACKs and the sending thread was sleeping, it woke up the sending thread, enabling fast re-transmission
- On the receiving side, used a HashMap to receive packets that might be out of order, and only wrote them into memory once a sorted set of packets forming a complete message was received
- Set only one timer and re-transmitted only one packet that did not receive ACK at a time

# Warning
- When I wrote this program, I hadn’t read Code Complete 2, and I didn’t know that declaring too many global variables would hurt readability, don’t learn this from me!
- At the time I don't know that Java has a log API, so I use print and file.write
