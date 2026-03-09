package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;
import socs.network.message.SOSPFPacket;
import socs.network.util.Configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;


public class Router {

  protected LinkStateDatabase lsd;

  RouterDescription rd = new RouterDescription();
  private final NetworkLayer networkLayer;
  private final int defaultLinkWeight = 1;

  Link[] ports = new Link[4];

  private BufferedReader inputReader = new BufferedReader(new InputStreamReader(System.in));

  // Background threads enqueue attach requests here; terminal thread prompts Y/N to avoid stdin races
  private ConcurrentLinkedQueue<PendingRequest> pendingRequests = new ConcurrentLinkedQueue<>();

  private static class PendingRequest {
    SOSPFPacket helloMsg;
    Socket socket;
    ObjectOutputStream out;
    boolean approved;
    CountDownLatch latch = new CountDownLatch(1); 

    PendingRequest(SOSPFPacket helloMsg, Socket socket, ObjectOutputStream out) {
      this.helloMsg = helloMsg;
      this.socket = socket;
      this.out = out;
    }
  }

  public Router(Configuration config) {
    rd.simulatedIPAddress = config.getString("socs.network.router.ip");
    rd.processIPAddress = "127.0.0.1";

    try {
      rd.processIPAddress = config.getString("socs.network.router.processIP");
    } catch (Exception ignored) {
    }

    rd.processPortNumber = 0;
    try {
      rd.processPortNumber = config.getShort("socs.network.router.port");
    } catch (Exception numberReadError) {
      try {
        rd.processPortNumber = Short.parseShort(config.getString("socs.network.router.port"));
      } catch (Exception stringReadError) {
        System.err.println("Invalid or missing config key socs.network.router.port; defaulting to ephemeral port");
      }
    }

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
    String path = lsd.getShortestPathForDisplay(destinationIP);
    if (path == null) {
      System.out.println("Path not found");
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
  private void processDisconnect(int portNumber) {
      if (portNumber < 0 || portNumber >= ports.length) {
        System.out.println("Invalid port number. Must be between 0 and " + (ports.length - 1));
        return;
      }
      Link link = ports[portNumber];
      if (link == null) {
        System.out.println("No link attached at port " + portNumber);
        return;
      }
      String neighborIp = link.router2 != null ? link.router2.simulatedIPAddress : null;
      sendDisconnectNotification(link);
      ports[portNumber] = null;
      boolean removed = removeNeighborFromSelfLsa(neighborIp, portNumber);
      if (removed) {
        floodLsaUpdate(neighborIp);
      }
      
  }

  /**
   * attach the link to the remote router, which is identified by the given simulated ip;
   * to establish the connection via socket, you need to indentify the process IP and process Port;
   * additionally, weight is the cost to transmitting data through the link
   * <p/>
   * NOTE: this command should not trigger link database synchronization
   */
  private void processAttach(String processIP, short processPort, String simulatedIP, short weight) {   
    if (weight <= 0) {
      System.out.println("Invalid weight (must be > 0): " + weight);
      return;
    }

    if (findLinkBySimulatedIP(simulatedIP) != null) {
      System.out.println("Attachment to " + simulatedIP + " already exists");
      return;
    }

    int port_slot = -1;
    for (int i = 0; i < ports.length; i++) {
      if (ports[i] == null) {
        port_slot = i;
        break;
      }
    }
    if (port_slot == -1) {
      System.err.println("All ports are full.");
      return;
    }

    try (Socket socket = new Socket(processIP, processPort)) {
      ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
      out.flush();
      ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

      SOSPFPacket hello = buildHelloPacket(simulatedIP);
      hello.linkWeight = weight;

      out.writeObject(hello);
      out.flush();

      SOSPFPacket response = (SOSPFPacket) in.readObject();

      if (response.sospfType == 0) {
        RouterDescription rd1 = new RouterDescription();
        rd1.processIPAddress = processIP;
        rd1.processPortNumber = processPort;
        rd1.simulatedIPAddress = simulatedIP;
        rd1.status = RouterStatus.INIT;
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
      ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
      out.flush();
      ObjectInputStream in = new ObjectInputStream(s.getInputStream());

      SOSPFPacket packet = (SOSPFPacket) in.readObject();
      if (packet.sospfType == 1) {
        handleLsaUpdate(packet);
        return;
      }
      if (packet.sospfType == 2) {
        handleDisconnectPacket(packet);
        return;
      }
      if (packet.sospfType == 4) {
        handleApplicationMessage(packet, out);
        return;
      }
      if (packet.sospfType != 0) {
        return;
      }

      SOSPFPacket hello = packet;
      System.out.println("received HELLO from " + hello.srcIP + ";");

      Link existingLink = findLinkBySimulatedIP(hello.srcIP);
      if (existingLink != null) {
        handleHelloForExistingLink(existingLink, out);
        return;
      }

      PendingRequest pRequest = new PendingRequest(hello, s, out);
      pendingRequests.add(pRequest);
      pRequest.latch.await();

      if (pRequest.approved) {
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

        RouterDescription rd2 = new RouterDescription();
        rd2.processIPAddress = hello.srcProcessIP;
        rd2.simulatedIPAddress = hello.srcIP;
        rd2.processPortNumber = hello.srcProcessPort;
        rd2.status = RouterStatus.INIT;
        System.out.println("set " + hello.srcIP + " STATE to INIT;");

        int inboundWeight = pRequest.helloMsg.linkWeight > 0 ? pRequest.helloMsg.linkWeight : defaultLinkWeight;
        Link newLink = new Link(rd, rd2, portSlot, inboundWeight);

        synchronized (ports) {
          ports[portSlot] = newLink;
        }

        SOSPFPacket accept = buildHelloPacket(hello.srcIP);
        out.writeObject(accept);
        out.flush();
        System.out.println("accepted attach request from " + hello.srcIP);
      } else {
        sendReject(out);
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
    for (Link link : ports) {
      if (link != null) {
        try (Socket socket = new Socket(link.router2.processIPAddress, link.router2.processPortNumber)) {
          ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
          out.flush();
          ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

          SOSPFPacket hello = buildHelloPacket(link.router2.simulatedIPAddress);
          out.writeObject(hello);
          out.flush();

          SOSPFPacket response = (SOSPFPacket) in.readObject();

          if (response.sospfType == 0) {
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
    floodLsaUpdate(null);
  }

  private SOSPFPacket buildHelloPacket(String dstIP) {
    SOSPFPacket hello = new SOSPFPacket();
    hello.sospfType = 0;
    hello.srcProcessIP = rd.processIPAddress;
    hello.srcProcessPort = rd.processPortNumber;
    hello.srcIP = rd.simulatedIPAddress;
    hello.dstIP = dstIP;
    hello.neighborID = rd.simulatedIPAddress;
    return hello;
  }

  private void sendReject(ObjectOutputStream out) throws IOException {
    SOSPFPacket reject = new SOSPFPacket();
    reject.sospfType = -1;
    out.writeObject(reject);
    out.flush();
  }

  /**
   * Build and send a HELLO packet to the given neighbor, then transition it to
   * TWO_WAY if it was previously INIT.  Called during {@link #processStart()}.
   *
   * @param nbr the neighbor's description (destination of the HELLO)
   * @param out the already-open output stream on the socket to {@code nbr}
   */
  private void sendHelloToNeighbor(RouterDescription nbr, ObjectOutputStream out) {
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
    processAttach(processIP, processPort, simulatedIP, weight);
    processStart();
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

  private void processQuit() {
    for (int i = 0; i < ports.length; i++) {
      if (ports[i] != null) {
        processDisconnect(i);
      }
    }
    networkLayer.stop();
  }

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
    System.out.println("Sending message to " + destinationIP);

    if (destinationIP.equals(rd.simulatedIPAddress)) {
      System.out.println("Received message from " + rd.simulatedIPAddress + ";");
      System.out.println("Message: " + message);
      return;
    }

    RouterDescription nextHop = getNextHop(destinationIP);
    if (nextHop == null) {
      System.out.println("Path not found");
      return;
    }

    SOSPFPacket pkt = new SOSPFPacket();
    pkt.sospfType = 4;
    pkt.srcProcessIP = rd.processIPAddress;
    pkt.srcProcessPort = rd.processPortNumber;
    pkt.srcIP = rd.simulatedIPAddress;
    pkt.dstIP = destinationIP;
    pkt.message = message;

    try (Socket socket = new Socket(nextHop.processIPAddress, nextHop.processPortNumber);
      ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
      sendPacket(pkt, nextHop, out);
    } catch (IOException e) {
      System.err.println("Failed to send application message: " + e.getMessage());
    }
  }

  /**
   * handle incoming application message packet.
   * This method should be called when a router receives a SOSPFPacket with sospfType = 4 (Application Message).
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
      System.out.println("Received message from " + packet.srcIP + ";");
      System.out.println("Message: " + packet.message);
      return;
    }

    System.out.println("Forwarding message from " + packet.srcIP + " to " + packet.dstIP);
    RouterDescription nextHop = getNextHop(packet.dstIP);
    if (nextHop == null) {
      System.out.println("Path not found");
      return;
    }
    System.out.println("Next hop: " + nextHop.simulatedIPAddress);

    try (Socket fwdSocket = new Socket(nextHop.processIPAddress, nextHop.processPortNumber);
      ObjectOutputStream fwdOut = new ObjectOutputStream(fwdSocket.getOutputStream())) {
      sendPacket(packet, nextHop, fwdOut);
    } catch (IOException e) {
      System.err.println("Failed to forward message: " + e.getMessage());
    }
  }

  public void terminal() {
    try {
      BufferedReader bReader = inputReader;
      System.out.println("========================================");
      System.out.println("Process IP : " + rd.processIPAddress);
      System.out.println("Process Port : " + rd.processPortNumber);
      System.out.println("Simulated IP : " + rd.simulatedIPAddress);
      System.out.println("========================================");

      while (true) {
        processPendingRequests(bReader);
        if (bReader.ready()) {

          String command = bReader.readLine();
          if (command == null) break;
          command = command.trim();
          if (command.isEmpty()) {
            continue;
          }

          if (command.equals("quit")) {
            processQuit();
            break;
          } else if (command.equals("help")) {
            printSupportedCommands();
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
            if (weight <= 0) {
              System.out.println("Invalid weight (must be > 0): " + weight);
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
            if (weight <= 0) {
              System.out.println("Invalid weight (must be > 0): " + weight);
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
            printSupportedCommands();
          }
        } else {

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

  private void printSupportedCommands() {
    System.out.println("Supported commands:");
    System.out.println("  help");
    System.out.println("  start");
    System.out.println("  neighbors");
    System.out.println("  detect [Destination IP]");
    System.out.println("  disconnect [port_number]");
    System.out.println("  attach [Process IP] [Process Port] [Simulated IP] [Weight]");
    System.out.println("  connect [Process IP] [Process Port] [Simulated IP] [Weight]");
    System.out.println("  send [Destination IP] [Message]");
    System.out.println("  quit");
  }

  private void processPendingRequests(BufferedReader bReader) {
    if (pendingRequests.isEmpty()) {
      return;
    }
    PendingRequest pRequest;
    while ((pRequest = pendingRequests.poll()) != null) {
      try {
        System.out.println("Do you accept this request from " + pRequest.helloMsg.srcIP + "? (Y/N)");
        String answer = bReader.readLine();
        while (answer != null && !(answer.equalsIgnoreCase("Y") || answer.equalsIgnoreCase("N"))) {
          System.out.println("Answer not accepted/invalid.");
          System.out.println("Do you accept this request? (Y/N)");
          answer = bReader.readLine();
        }
        if (answer.equalsIgnoreCase("Y")) {
          pRequest.approved = true;
        } else {
          pRequest.approved = false;
        }
      } catch (IOException e) {
        pRequest.approved = false;
      }
      pRequest.latch.countDown();
    }
  }

  short getProcessPort() {
    return rd.processPortNumber;
  }

  void setProcessPort(short port) {
    rd.processPortNumber = port;
  }

  // Look up the Link object in this router's ports that connects to the neighbor with the given simulated IP
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

    try {
      SOSPFPacket helloResponse = buildHelloPacket(link.router2.simulatedIPAddress);
      out.writeObject(helloResponse);
      out.flush();
    } catch (IOException e) {
      System.err.println("Failed to send HELLO reply to " + link.router2.simulatedIPAddress + ": " + e.getMessage());
    }
  }

  private void updateLocalLsaForLink(Link link) {
    socs.network.message.LSA self = lsd._store.get(rd.simulatedIPAddress);
    if (self == null) {
      return;
    }

    boolean found = false;
    for (socs.network.message.LinkDescription ld : self.links) {
      if (link.router2.simulatedIPAddress.equals(ld.linkID)) {
        ld.portNum = link.portNum;
        ld.weight = link.weight;
        found = true;
        break;
      }
    }

    if (!found) {
      socs.network.message.LinkDescription ld = new socs.network.message.LinkDescription();
      ld.linkID = link.router2.simulatedIPAddress;
      ld.portNum = link.portNum;
      ld.tosMetrics = 0;
      ld.weight = link.weight;
      self.links.add(ld);
    }

    self.lsaSeqNumber++;
    floodLsaUpdate(null);
  }

  // Helper function -> next hop router description on shortest path to the destination IP. uses Link State Database
  private RouterDescription getNextHop(String destinationIP) {
    String path = lsd.getShortestPath(destinationIP);
    if (path == null) {
      return null;
    }
    String[] hops = path.split("\\s*->\\s*");
    if (hops.length < 2) {
      return null;
    }
    String nextHopIP = extractIPAddress(hops[1]);
    if (nextHopIP == null) {
      return null;
    }
    Link link = findLinkBySimulatedIP(nextHopIP);
    if (link == null) {
      return null;
    }
    return link.router2;
  }

  private String extractIPAddress(String hopToken) {
    if (hopToken == null) {
      return null;
    }
    String trimmed = hopToken.trim();
    if (trimmed.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
      return trimmed;
    }
    String[] parts = trimmed.split("\\s+");
    for (String p : parts) {
      if (p.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
        return p;
      }
    }
    return null;
  }

  private void floodLsaUpdate(String excludedNeighborIp) {
    for (Link link : ports) {
      if (link == null || link.router2.status != RouterStatus.TWO_WAY) {
        continue;
      }
      if (link.router2.simulatedIPAddress == null) {
        continue;
      }
      if (excludedNeighborIp != null
          && excludedNeighborIp.equals(link.router2.simulatedIPAddress)) {
        continue;
      }

      try (Socket socket = new Socket(link.router2.processIPAddress, link.router2.processPortNumber);
        ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
        SOSPFPacket lsaUpdate = new SOSPFPacket();
        lsaUpdate.lsaArray = new Vector<>(lsd._store.values());
        lsaUpdate.sospfType = 1;
        lsaUpdate.srcProcessIP = rd.processIPAddress;
        lsaUpdate.srcProcessPort = rd.processPortNumber;
        lsaUpdate.srcIP = rd.simulatedIPAddress;
        lsaUpdate.dstIP = link.router2.simulatedIPAddress;

        if (lsaUpdate.lsaArray.isEmpty()) {
          continue;
        }

        out.writeObject(lsaUpdate);
        out.flush();
      } catch (IOException e) {
        System.err.println("Failure sending LSA update to " + link.router2.simulatedIPAddress);
      }
    }
  }

  private void handleLsaUpdate(SOSPFPacket packet) {
    if (packet == null || packet.lsaArray == null || packet.srcIP == null) {
      return;
    }

    boolean needToFlood = false;
    boolean sourceNeighborDroppedUs = false;

    Vector<socs.network.message.LSA> incomingLsaArray = packet.lsaArray;
    for (socs.network.message.LSA newLsa : incomingLsaArray) {
      if (newLsa == null || newLsa.linkStateID == null) {
        continue;
      }

      socs.network.message.LSA current = lsd._store.get(newLsa.linkStateID);

      if (current == null || newLsa.lsaSeqNumber > current.lsaSeqNumber) {
        lsd._store.put(newLsa.linkStateID, newLsa);
        needToFlood = true;

        if (packet.srcIP.equals(newLsa.linkStateID)
            && !neighborListsMe(newLsa, rd.simulatedIPAddress)) {
          sourceNeighborDroppedUs = true;
        }
      }
    }

    if (sourceNeighborDroppedUs) {
      boolean mirrored = mirrorNeighborDisconnect(packet.srcIP);
      if (mirrored) {
        needToFlood = true;
      }
    }

    if (needToFlood) {
      floodLsaUpdate(packet.srcIP);
    }
  }

  private boolean neighborListsMe(socs.network.message.LSA neighborLsa, String selfIp) {
    if (selfIp == null) {
      return false;
    }

    for (socs.network.message.LinkDescription link : neighborLsa.links) {
      if (link != null && selfIp.equals(link.linkID)) {
        return true;
      }
    }
    return false;
  }

  private boolean mirrorNeighborDisconnect(String neighborIP) {
    if (neighborIP == null) {
      return false;
    }

    Link link = findLinkBySimulatedIP(neighborIP);
    if (link == null || link.router2 == null || link.router2.status != RouterStatus.TWO_WAY) {
      return false;
    }

    socs.network.message.LSA self = lsd._store.get(rd.simulatedIPAddress);
    if (self == null) {
      return false;
    }

    System.out.println(neighborIP + " removed us so we're going to remove them too");

    for (int i = 0; i < ports.length; i++) {
      Link p = ports[i];
      if (p != null && p.router2 != null && neighborIP.equals(p.router2.simulatedIPAddress)) {
        ports[i] = null;
        break;
      }
    }

    boolean removedFromSelfLsa = false;
    for (int i = 0; i < self.links.size(); i++) {
      socs.network.message.LinkDescription ld = self.links.get(i);
      if (neighborIP.equals(ld.linkID)) {
        self.links.remove(i);
        i--;
        removedFromSelfLsa = true;
      }
    }

    if (removedFromSelfLsa) {
      self.lsaSeqNumber++;
    }
    return removedFromSelfLsa;
  }

  private boolean removeNeighborFromSelfLsa(String neighborIp, int portNumber) {
    socs.network.message.LSA self = lsd._store.get(rd.simulatedIPAddress);
    if (self == null) {
      return false;
    }

    boolean removed = false;
    for (int i = 0; i < self.links.size(); i++) {
      socs.network.message.LinkDescription ld = self.links.get(i);
      if (ld == null) {
        continue;
      }
      boolean sameNeighbor = neighborIp != null && neighborIp.equals(ld.linkID);
      boolean samePort = portNumber >= 0 && ld.portNum == portNumber;
      if (sameNeighbor || samePort) {
        self.links.remove(i);
        i--;
        removed = true;
      }
    }

    if (removed) {
      self.lsaSeqNumber++;
    }
    return removed;
  }

  private void sendDisconnectNotification(Link link) {
    if (link == null || link.router2 == null) {
      return;
    }

    try (Socket socket = new Socket(link.router2.processIPAddress, link.router2.processPortNumber);
         ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
      SOSPFPacket disconnect = new SOSPFPacket();
      disconnect.sospfType = 2;
      disconnect.srcProcessIP = rd.processIPAddress;
      disconnect.srcProcessPort = rd.processPortNumber;
      disconnect.srcIP = rd.simulatedIPAddress;
      disconnect.dstIP = link.router2.simulatedIPAddress;
      out.writeObject(disconnect);
      out.flush();
    } catch (IOException e) {
      System.err.println("Failed to notify " + link.router2.simulatedIPAddress + " about disconnect");
    }
  }

  private void handleDisconnectPacket(SOSPFPacket packet) {
    if (packet == null || packet.srcIP == null) {
      return;
    }

    String neighborIp = packet.srcIP;
    boolean removedPort = false;
    int removedPortNumber = -1;

    for (int i = 0; i < ports.length; i++) {
      Link existing = ports[i];
      if (existing != null && existing.router2 != null
          && neighborIp.equals(existing.router2.simulatedIPAddress)) {
        ports[i] = null;
        removedPort = true;
        removedPortNumber = i;
        break;
      }
    }

    boolean removedFromLsa = removeNeighborFromSelfLsa(neighborIp, removedPortNumber);
    if (removedPort || removedFromLsa) {
      floodLsaUpdate(neighborIp);
    }
  }

}
