package socs.network.node;

import socs.network.message.SOSPFPacket;
import socs.network.util.Configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;


public class Router {

  protected LinkStateDatabase lsd;

  RouterDescription rd = new RouterDescription();
  private final NetworkLayer networkLayer; // new class - for threading
  private final int defaultLinkWeight = 1;

  //assuming that all routers are with 4 ports
  Link[] ports = new Link[4];

  // deadlock fix -- A single BufferedReader wraps System.in. Only the main terminal
  // thread should call readLine() on this because if a background thread also reads from
  // System.in, the two threads race and steal each other's input.
  private BufferedReader inputReader = new BufferedReader(new InputStreamReader(System.in));

  // deadlock fix -- When a background requestHandler thread receives an attach HELLO
  // from a new neighbor, it cannot prompt Y/N itself (stdin race). Instead it
  // enqueues a PendingRequest here and blocks. The main terminal thread polls this
  // queue, prompts Y/N safely, and signals the result back via the latch.
  // The latch is a blocking synchronization primitive 
  // This prevents a race condition where multiple threads would try to read from System.in simultaneously,
  // which would cause input theft and deadlock.
  private ConcurrentLinkedQueue<PendingRequest> pRequests = new ConcurrentLinkedQueue<>();

  // Holds a pending attach request so the main thread can prompt Y/N while the socket stays open
  private static class PendingRequest {
    SOSPFPacket helloMsg;
    Socket socket;
    ObjectOutputStream out;
    // The 'approved' field is set by the main thread after prompting Y/N, and read by the background thread to decide whether to accept or reject the attach request
    boolean approved;
    // The latch starts at 1, meaning the background thread will block on latch.await() until the main thread calls latch.countDown() after setting 'approved'
    // So basically the user decides
    CountDownLatch cdlatch = new CountDownLatch(1); 

    PendingRequest(SOSPFPacket helloMsg, Socket socket, ObjectOutputStream out) {
      this.helloMsg = helloMsg;
      this.socket = socket;
      this.out = out;
    }
  }

  public Router(Configuration config) {
    // Initialize RouterDescription from configuration
    rd.simulatedIPAddress = config.getString("socs.network.router.ip");
    // Default to localhost if not specified since we will be testing all routers on local machine
    rd.processIPAddress = "127.0.0.1";

    // Override process IP if specified in config
    try {
      rd.processIPAddress = config.getString("socs.network.router.processIP");
    } catch (Exception ignored) {
    }

    // Default port number to 0 (invalid) if not specified, will be set to config value when starting server
    rd.processPortNumber = 0;
    try {
      rd.processPortNumber = config.getShort("socs.network.router.port");
    } catch (Exception ignored) {
    }

    // Initialize Link State Database and Network Layer
    lsd = new LinkStateDatabase(rd);
    networkLayer = new NetworkLayer(this);
    try {
      networkLayer.start();
    } catch (IOException e) {
      System.err.println("Failed to start network listener: " + e.getMessage());
    }
  }

  /**
   * output the shortest path to the given destination ip
   * <p/>
   * format: source ip address  -> ip address -> ... -> destination ip
   *
   * @param destinationIP the ip adderss of the destination simulated router
   */
  private void processDetect(String destinationIP) {
    String path = lsd.getShortestPath(destinationIP);
    if (path == null) {
      System.out.println("No path found");
      return;
    }
    System.out.println(path);
  }

  /**
   * disconnect with the router identified by the given destination ip address
   * Notice: this command should trigger the synchronization of database
   *
   * @param portNumber the port number which the link attaches at
   */
  private void processDisconnect(short portNumber) {

  }

  /**
   * attach the link to the remote router, which is identified by the given simulated ip;
   * to establish the connection via socket, you need to indentify the process IP and process Port;
   * additionally, weight is the cost to transmitting data through the link
   * <p/>
   * NOTE: this command should not trigger link database synchronization
   */
  private void processAttach(String processIP, short processPort, String simulatedIP, short weight) {         
    // figure out process port
    int port_slot = -1;
    for (int i = 0; i < ports.length; i++) {
      if (ports[i] == null) {
        port_slot = i;
        break;
      } // find an open port
    }
    if (port_slot == -1) {
      System.err.println("All ports are full.");
      return;
    }

    // Establish the socket connection to the remote router and perform HELLO handshake
    try (Socket socket = new Socket(processIP, processPort)) {
      ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
      out.flush(); // flush OOS header to avoid deadlock
      ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
      
      // create hello packet
      SOSPFPacket hello = new SOSPFPacket();
      hello.sospfType = 0; // HELLO
      hello.srcProcessIP = rd.processIPAddress;
      hello.srcProcessPort = rd.processPortNumber;
      hello.srcIP = rd.simulatedIPAddress;
      hello.dstIP = simulatedIP;
      hello.neighborID = rd.simulatedIPAddress;
      
      out.writeObject(hello); // write to socket stream
      out.flush();
      
      // Block waiting for accept/reject response
      // Handles on the same outgoing socket since the remote router will reply on the same socket connection
      SOSPFPacket response = (SOSPFPacket) in.readObject();
      
      if (response.sospfType == 0) { // Accepted (HELLO response)
        // null status for link
        RouterDescription rd1 = new RouterDescription();
        rd1.processIPAddress = processIP;
        rd1.processPortNumber = processPort;
        rd1.simulatedIPAddress = simulatedIP;
        rd1.status = RouterStatus.INIT; // Set neighbor status to INIT upon attachment
        System.out.println("set " + simulatedIP + " STATE to INIT;");
        Link newLink = new Link(rd, rd1, port_slot, weight);
        ports[port_slot] = newLink;
        System.out.println("successfully attached to " + simulatedIP);
      } else {
        System.out.println("Connection rejected by " + simulatedIP);
      }
      
    } catch (IOException | ClassNotFoundException e) {
      System.err.println("Failed to attach to " + simulatedIP + ": " + e.getMessage());
    }
  }


  /**
   * process request from the remote router. 
   * For example: when router2 tries to attach router1. Router1 can decide whether it will accept this request. 
   * The intuition is that if router2 is an unknown/anomaly router, it is always safe to reject the attached request from router2.
   */
  void requestHandler(Socket socket) {
    try (Socket s = socket) {
      // Creating ObjectOutputStream before ObjectInputStream to avoid deadlock (both sides waiting for OOS header)
      ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
      out.flush(); // flush OOS header BEFORE creating OIS to avoid deadlock
      ObjectInputStream in = new ObjectInputStream(s.getInputStream());

      // Block waiting for incoming HELLO packet
      SOSPFPacket packet = (SOSPFPacket) in.readObject();
      if (packet.sospfType == 2) {
        handleApplicationMessage(packet, out);
        return;
      }
      if (packet.sospfType != 0) {
        return;
      }

      // Deserialize incoming HELLO packet
      SOSPFPacket hello = packet;
      System.out.println("received HELLO from " + hello.srcIP + ";");

      // Check if the incoming HELLO is from an existing neighbor
      Link existingLink = findLinkBySimulatedIP(hello.srcIP);
      if (existingLink != null) {
        handleHelloForExistingLink(existingLink, out);
        // Send back HELLO response to acknowledge the existing neighbor  
        return;
      }

      // New neighbor will queue the request for the main terminal thread to prompt Y/N
      // We keep the socket open; the background thread blocks until the main thread decides
      PendingRequest pRequest = new PendingRequest(hello, s, out);
      pRequests.add(pRequest);

      // We then block this thread until the terminal thread processes the Y/N
      pRequest.cdlatch.await();

      if (pRequest.approved) {
        // Find available port
        int portSlot = -1;
        synchronized (ports) {
          for (int i = 0; i < ports.length; i++) {
            if (ports[i] == null) {
              portSlot = i;
              break;
            }
          }
        }

        if (portSlot == -1) {
          System.out.println("No available ports. Rejecting request.");
          SOSPFPacket reject = new SOSPFPacket();
          reject.sospfType = -1;
          out.writeObject(reject);
          out.flush();
          return;
        }

        // Create Link for the new neighbor
        RouterDescription rd2 = new RouterDescription();
        rd2.processIPAddress = hello.srcProcessIP;
        rd2.simulatedIPAddress = hello.srcIP;
        rd2.processPortNumber = hello.srcProcessPort;
        rd2.status = RouterStatus.INIT;
        System.out.println("set " + hello.srcIP + " STATE to INIT;");

        Link newLink = new Link(rd, rd2, portSlot, defaultLinkWeight);

        synchronized (ports) {
          ports[portSlot] = newLink;
        }

        // Send acceptance
        SOSPFPacket accept = new SOSPFPacket();
        accept.sospfType = 0;
        accept.srcIP = rd.simulatedIPAddress;
        accept.srcProcessIP = rd.processIPAddress;
        accept.srcProcessPort = rd.processPortNumber;
        accept.dstIP = hello.srcIP;
        out.writeObject(accept);
        out.flush();
        System.out.println("accepted attach request from " + hello.srcIP);
      } else {
        // Send rejection
        SOSPFPacket reject = new SOSPFPacket();
        reject.sospfType = -1;
        out.writeObject(reject);
        out.flush();
        System.out.println("rejected attach request from " + hello.srcIP);
      }
    } catch (IOException | ClassNotFoundException | InterruptedException e) {
      System.err.println("Error handling request: " + e.getMessage());
      try {
        socket.close();
      } catch (IOException ignored) {
      }
    }
  }

  private void processStart() {
    
    // Send Hello packet to all attached links
    for (Link link : ports) {
      if (link != null) {
        try (Socket socket = new Socket(link.router2.processIPAddress, link.router2.processPortNumber)) {
          ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
          out.flush(); // flush OOS header to avoid deadlock
          ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
          
          // Send HELLO packet
          SOSPFPacket hello = new SOSPFPacket();
          hello.sospfType = 0; // HELLO
          hello.srcProcessIP = rd.processIPAddress;
          hello.srcProcessPort = rd.processPortNumber;
          hello.srcIP = rd.simulatedIPAddress;
          hello.dstIP = link.router2.simulatedIPAddress;
          hello.neighborID = rd.simulatedIPAddress;
          
          out.writeObject(hello);
          out.flush();
          
          // Wait for HELLO response
          SOSPFPacket response = (SOSPFPacket) in.readObject();
          
          if (response.sospfType == 0) { // HELLO response received
            if (link.router2.status != RouterStatus.TWO_WAY) {
              link.router2.status = RouterStatus.TWO_WAY;
              System.out.println("set " + link.router2.simulatedIPAddress + " state to TWO_WAY");
              updateLocalLsaForLink(link);
            }
          }
        } catch (Exception e) {
          System.err.println("Failed to send HELLO to " + link.router2.simulatedIPAddress + ": " + e.getMessage());
        }
      }
    }

    // Configure router status // currenlty done in sendHelloToNeighbor

    // Update own LSD

    // Multicast LSA update packets to neighbors

  }

  // Helper function to create and send a HELLO packet on process start
  private void sendHelloToNeighbor(RouterDescription nbr, ObjectOutputStream out) {
      
    // Create HELLO packet

    SOSPFPacket hello = new SOSPFPacket();
    hello.sospfType = 0; // HELLO

    // inter-process addressing (real socket endpoints)
    hello.srcProcessIP = rd.processIPAddress;
    hello.srcProcessPort = rd.processPortNumber;

    // simulated addressing (router IDs)
    hello.srcIP = rd.simulatedIPAddress;
    hello.dstIP = nbr.simulatedIPAddress;

    // HELLO semantic: "I am <src simulated IP>"
    hello.neighborID = rd.simulatedIPAddress;

    sendPacket(hello, nbr, out);

    // Set neighbor status to TWOWAY
    if (nbr.status == RouterStatus.INIT) {
      nbr.status = RouterStatus.TWO_WAY;
      System.out.println("set " + nbr.simulatedIPAddress + " state to TWO_WAY");
      // Finally update local LSD for this neighbor and trigger synchronization by sending LSA update to all neighbors
      Link link = findLinkBySimulatedIP(nbr.simulatedIPAddress);
      if (link != null) {
        updateLocalLsaForLink(link);
      }
    }
  }

  // Helper function to send the packet to the neighbor as an Object
  private void sendPacket(SOSPFPacket pakt, RouterDescription nbr, ObjectOutputStream out) {
    try {

      out.writeObject(pakt);
      out.flush();

    } catch (IOException e) {
      e.printStackTrace();
    }
  }


  /**
   * attach the link to the remote router, which is identified by the given simulated ip;
   * to establish the connection via socket, you need to indentify the process IP and process Port;
   * additionally, weight is the cost to transmitting data through the link
   * <p/>
   * This command does trigger the link database synchronization
   */
  private void processConnect(String processIP, short processPort,
                              String simulatedIP, short weight) {

  }

  /**
   * output the neighbors of the routers
   */
  private void processNeighbors() {
    socs.network.message.LSA self = lsd._store.get(rd.simulatedIPAddress);
    if (self == null) {
      return;
    }
    for (socs.network.message.LinkDescription ld : self.links) {
      if (!rd.simulatedIPAddress.equals(ld.linkID)) {
        System.out.println(ld.linkID);
      }
    }
  }

  /**
   * disconnect with all neighbors and quit the program
   */
  private void processQuit() {
    networkLayer.stop(); // stop the main router
  }

  /**
   * update the weight of an attached link
   */
  private void updateWeight(String processIP, short processPort,
                             String simulatedIP, short weight){

  }

  /**
   * update the weight of a specific port.
   * This change should trigger synchronization of the Link State Database by sending 
   * a Link State Advertisement (LSA) update to all neighboring routers in the topology.
   *
   * @param portNumber the port number (0-3) to update
   * @param newWeight the new weight/cost for the link attached to this port
   */
  private void processUpdate(short portNumber, short newWeight) {

  }

  /**
   * send an application-level message from this router to the destination router.
   * The message must be forwarded hop-by-hop according to the current shortest path.
   * <p/>
   * When you run send, the window of the router where you run the command should print:
   * "Sending message to <Destination IP>"
   * <p/>
   * For each intermediate router on the shortest path (excluding the source and destination), 
   * the router window should print:
   * "Forwarding packet from <Source IP> to <Destination IP>"
   * <p/>
   * When the destination router receives the message, the router window should print:
   * "Received message from <Source IP>:"
   * "<Message>"
   *
   * @param destinationIP the simulated IP address of the destination router
   * @param message the message content to send
   */
  private void processSend(String destinationIP, String message) {
    // Print the sending message log
    System.out.println("Sending message to " + destinationIP);
    // If the destination is this router itself, print the received message log and return
    if (destinationIP.equals(rd.simulatedIPAddress)) {
      System.out.println("Received message from " + rd.simulatedIPAddress + ":");
      System.out.println(message);
      return;
    }

    // Find the next hop on the shortest path to the destination using the Link State Database
    RouterDescription nextHop = getNextHop(destinationIP);
    if (nextHop == null) {
      System.out.println("No path found");
      return;
    }

    // Create an application message packet to send to the next hop
    SOSPFPacket pkt = new SOSPFPacket();
    pkt.sospfType = 2;
    pkt.srcProcessIP = rd.processIPAddress;
    pkt.srcProcessPort = rd.processPortNumber;
    pkt.srcIP = rd.simulatedIPAddress;
    pkt.dstIP = destinationIP;
    pkt.message = message;

    // Open a new socket to the next hop for sending (not the incoming socket)
    try (Socket socket = new Socket(nextHop.processIPAddress, nextHop.processPortNumber);
      ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
      sendPacket(pkt, nextHop, out);
    } catch (IOException e) {
      System.err.println("Failed to send application message: " + e.getMessage());
    }
  }

  /**
   * handle incoming application message packet.
   * This method should be called when a router receives a SOSPFPacket with sospfType = 2 (Application Message).
   * <p/>
   * If this router is the destination (packet.dstIP equals this router's simulatedIPAddress):
   * - Print "Received message from <Source IP>:"
   * - Print the message content
   * <p/>
   * If this router is an intermediate router:
   * - Print "Forwarding packet from <Source IP> to <Destination IP>"
   * - Forward the packet to the next hop on the shortest path to the destination
   * - Do NOT print or inspect the message payload
   *
   * @param packet the received application message packet
   */
  private void handleApplicationMessage(socs.network.message.SOSPFPacket packet, ObjectOutputStream out) {
    if (packet.dstIP == null || packet.srcIP == null) {
      return;
    }
    if (packet.dstIP.equals(rd.simulatedIPAddress)) {
      System.out.println("Received message from " + packet.srcIP + ":");
      System.out.println(packet.message);
      return;
    }

    System.out.println("Forwarding packet from " + packet.srcIP + " to " + packet.dstIP);
    RouterDescription nextHop = getNextHop(packet.dstIP);
    if (nextHop == null) {
      System.out.println("No path found");
      return;
    }

    // Open a new socket to the next hop for forwarding (not the incoming socket)
    try (Socket fwdSocket = new Socket(nextHop.processIPAddress, nextHop.processPortNumber);
      ObjectOutputStream fwdOut = new ObjectOutputStream(fwdSocket.getOutputStream())) {
      sendPacket(packet, nextHop, fwdOut);
    } catch (IOException e) {
      System.err.println("Failed to forward message: " + e.getMessage());
    }
  }

  public void terminal() {
    try {
      // Use the dedicated stdinReader for the terminal thread to avoid conflicts with background threads that also need to read from System.in (e.g. for attach requests)
      BufferedReader bReader = inputReader;
      System.out.println("========================================");
      System.out.println("Process IP : " + rd.processIPAddress);
      System.out.println("Process Port : " + rd.processPortNumber);
      System.out.println("Simulated IP : " + rd.simulatedIPAddress);
      System.out.println("========================================");

      while (true) {
        // Poll: check for pending attach requests OR user input
        processPendingRequests(bReader); 
        if (bReader.ready()) {

          // User input ready, read the command and process it
          String command = bReader.readLine();
          if (command == null) break;
          command = command.trim();
          if (command.isEmpty()) {
            continue;
          }

          if (command.equals("quit")) {
            processQuit();
            break;
          } else if (command.equals("start")) {
            processStart();
          } else if (command.equals("neighbors")) {
            processNeighbors();
          } else if (command.startsWith("detect ")) {
            String[] cmdLine = command.split("\\s+");
            if (cmdLine.length != 2) {
              System.out.println("Usage: detect [Destination IP]");
              continue;
            }
            processDetect(cmdLine[1]);
          } else if (command.startsWith("disconnect ")) {
            String[] cmdLine = command.split("\\s+");
            if (cmdLine.length != 2) {
              System.out.println("Usage: disconnect [port_number]");
              continue;
            }
            Short port = parseShortArg(cmdLine[1], "port_number");
            if (port == null) {
              continue;
            }
            processDisconnect(port);
          } else if (command.startsWith("attach ")) {
            String[] cmdLine = command.split("\\s+");
            if (cmdLine.length != 5) {
              System.out.println("Usage: attach [Process IP] [Process Port] [Simulated IP] [Weight]");
              continue;
            }
            Short processPort = parseShortArg(cmdLine[2], "process_port");
            Short weight = parseShortArg(cmdLine[4], "weight");
            if (processPort == null || weight == null) {
              continue;
            }
            processAttach(cmdLine[1], processPort, cmdLine[3], weight);
          } else if (command.startsWith("connect ")) {
            String[] cmdLine = command.split("\\s+");
            if (cmdLine.length != 5) {
              System.out.println("Usage: connect [Process IP] [Process Port] [Simulated IP] [Weight]");
              continue;
            }
            Short processPort = parseShortArg(cmdLine[2], "process_port");
            Short weight = parseShortArg(cmdLine[4], "weight");
            if (processPort == null || weight == null) {
              continue;
            }
            processConnect(cmdLine[1], processPort, cmdLine[3], weight);
          } else if (command.startsWith("send ")) {
            String[] cmdLine = command.split(" ", 3);
            if (cmdLine.length >= 3) {
              processSend(cmdLine[1], cmdLine[2]);
            } else {
              System.out.println("Usage: send [Destination IP] [Message]");
            }
          } else if (command.startsWith("update ")) {
            String[] cmdLine = command.split("\\s+");
            if (cmdLine.length != 3) {
              System.out.println("Usage: update [port_number] [new_weight]");
              continue;
            }
            Short port = parseShortArg(cmdLine[1], "port_number");
            Short newWeight = parseShortArg(cmdLine[2], "new_weight");
            if (port == null || newWeight == null) {
              continue;
            }
            processUpdate(port, newWeight);
          } else {
            System.out.println("Unknown command: " + command);
            System.out.println("Try: detect, disconnect, attach, connect, start, neighbors, send, update, quit");
          }
        } else {
          // No input ready — sleep briefly to avoid busy-waiting
          // console environment, stdin isn’t easily multiplexed with other events, so polling is a simple solution. 
          Thread.sleep(100);
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private Short parseShortArg(String value, String name) {
    try {
      return Short.parseShort(value);
    } catch (NumberFormatException e) {
      System.out.println("Invalid " + name + " (expected short): " + value);
      return null;
    }
  }

  // Drain pending attach requests, prompting Y/N for each on the main thread
  private void processPendingRequests(BufferedReader bReader) {
    PendingRequest pRequest;

    // No point polling if queue is empty
    if (pRequests.isEmpty()) {
      return;
    }
    // We process pending requests sequentially on the main thread to safely prompt Y/N without racing on System.in. 
    // Each request will block the background thread until the main thread processes it and signals via the latch.
    while ((pRequest = pRequests.poll()) != null) { 
      try {
        System.out.println("Do you accept this request from " + pRequest.helloMsg.srcIP + "? (Y/N)");
        // bReader.readline() is a blocking call
        String answer = bReader.readLine();
        while (answer != null && !(answer.equalsIgnoreCase("Y") || answer.equalsIgnoreCase("N"))) {
          System.out.println("Answer not accepted/invalid.");
          System.out.println("Do you accept this request? (Y/N)");
          answer = bReader.readLine();
        }
        // Check the user's answer and set the 'accepted' field of the PendingRequest accordingly, which will be read by the background thread after latch is released
        if (answer.equalsIgnoreCase("Y")) {
          pRequest.approved = true;
        } else {
          pRequest.approved = false;
        }
      } catch (IOException e) {
        pRequest.approved = false;
      }
      pRequest.cdlatch.countDown(); // unblock the background thread by counting down from 1 to 0, allowing it to proceed
    }
  }

  short getProcessPort() {
    return rd.processPortNumber;
  }

  void setProcessPort(short port) {
    rd.processPortNumber = port;
  }

  private Link findLinkBySimulatedIP(String simulatedIP) {
    if (simulatedIP == null) {
      return null;
    }
    for (Link link : ports) {
      if (link != null && link.router2 != null
          && simulatedIP.equals(link.router2.simulatedIPAddress)) {
        return link;
      }
    }
    return null;
  }

  // Handle incoming HELLO from an existing neighbor
  private void handleHelloForExistingLink(Link link, ObjectOutputStream out) {
    RouterStatus current = link.router2.status;
    if (current == null) {
      System.out.println("Received HELLO from existing neighbor " + link.router2.simulatedIPAddress + " with null status. Something is wrong.");
      return;
    }
    if (current != RouterStatus.TWO_WAY) {
      link.router2.status = RouterStatus.TWO_WAY;
      System.out.println("set " + link.router2.simulatedIPAddress + " state to TWO_WAY");
      updateLocalLsaForLink(link);
    }
    
    // Send HELLO response on the SAME socket
    try {
      SOSPFPacket helloResponse = new SOSPFPacket();
      helloResponse.sospfType = 0; // HELLO
      helloResponse.srcProcessIP = rd.processIPAddress;
      helloResponse.srcProcessPort = rd.processPortNumber;
      helloResponse.srcIP = rd.simulatedIPAddress;
      helloResponse.dstIP = link.router2.simulatedIPAddress;
      helloResponse.neighborID = rd.simulatedIPAddress;
      
      out.writeObject(helloResponse);
      out.flush();
    } catch (IOException e) {
      System.err.println("Failed to send HELLO reply to " + link.router2.simulatedIPAddress + ": " + e.getMessage());
    }
  }

  // Helper function to update local LSA for a specific link and trigger synchronization by sending LSA update to all neighbors
  private void updateLocalLsaForLink(Link link) {
    // Update local LSA for the link
    socs.network.message.LSA self = lsd._store.get(rd.simulatedIPAddress);
    if (self == null) {
      return;
    }

    // Check if the link already exists in the LSA, if so update it; if not, add a new LinkDescription for this link
    boolean found = false;
    for (socs.network.message.LinkDescription ld : self.links) {
      if (link.router2.simulatedIPAddress.equals(ld.linkID)) {
        ld.portNum = link.portNum;
        ld.weight = link.weight;
        found = true;
        break;
      }
    }

    // If not found, add new LinkDescription for this link
    if (!found) {
      socs.network.message.LinkDescription ld = new socs.network.message.LinkDescription();
      ld.linkID = link.router2.simulatedIPAddress;
      ld.portNum = link.portNum;
      ld.tosMetrics = 0;
      ld.weight = link.weight;
      self.links.add(ld);
    }

    self.lsaSeqNumber++;
  }

  private RouterDescription getNextHop(String destinationIP) {
    String path = lsd.getShortestPath(destinationIP);
    if (path == null) {
      return null;
    }
    String[] hops = path.split(" -> ");
    if (hops.length < 2) {
      return null;
    }
    String nextHopIP = hops[1].trim();
    Link link = findLinkBySimulatedIP(nextHopIP);
    if (link == null) {
      return null;
    }
    return link.router2;
  }

}
