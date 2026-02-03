package socs.network.node;

import socs.network.message.SOSPFPacket;
import socs.network.util.Configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Scanner;


public class Router {

  protected LinkStateDatabase lsd;

  RouterDescription rd = new RouterDescription();

  //assuming that all routers are with 4 ports
  Link[] ports = new Link[4];

  public Router(Configuration config) {
    rd.simulatedIPAddress = config.getString("socs.network.router.ip");
    lsd = new LinkStateDatabase(rd);
  }

  /**
   * output the shortest path to the given destination ip
   * <p/>
   * format: source ip address  -> ip address -> ... -> destination ip
   *
   * @param destinationIP the ip adderss of the destination simulated router
   */
  private void processDetect(String destinationIP) {

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
  private void processAttach(String processIP, short processPort,
                             String simulatedIP, short weight) {
                              //find an available port
                              int port_slot = -1;
                              for (int i = 0; i < ports.length; i++){
                                if (ports[i] == null){
                                  port_slot = i;
                                  break;
                                }
                              }
                              if (port_slot == -1){ // no ports available
                                // error out  
                                System.err.println("All ports are full.");
                                return;
                              }
                              // if port is created
                              RouterDescription rd1 = new RouterDescription();
                              rd1.processIPAddress = processIP;
                              rd1.processPortNumber = processPort;
                              rd1.simulatedIPAddress = simulatedIP;
                              rd1.status = RouterStatus.INIT; // intitialize, theress not handshake
                              Link newLink = new Link(rd, rd1);
                              ports[port_slot] = newLink;
                              System.out.println("Your connection is initiated");
                              

  }


  /**
   * process request from the remote router. 
   * For example: when router2 tries to attach router1. Router1 can decide whether it will accept this request. 
   * The intuition is that if router2 is an unknown/anomaly router, it is always safe to reject the attached request from router2.
   */
  private boolean requestHandler(SOSPFPacket hello) {
    System.out.println("received HELLO from " + hello.srcIP + ";");
    System.out.println("Do you accept this request? (Y/N)");
    Scanner sc = new Scanner(System.in);
    String answer = sc.nextLine();
    while(answer != "Y" || answer != "N"){
      System.out.println("Answer not accepted/invalid.");
      System.out.println("Do you accept this request? (Y/N)");
    }
    if (answer == "Y"){
      //create the lin in the port
      // creat eoutcomign rd
      RouterDescription rd2 = new RouterDescription();
      rd2.processIPAddress = hello.dstIP;
      rd2.simulatedIPAddress = hello.srcIP;
      rd2.processPortNumber = hello.srcProcessPort;
      rd2.status = RouterStatus.TWO_WAY; // accepted
      ports[hello.srcProcessPort] = new Link(rd, rd2);
      return true;
    }
    return false; // send rejection & handle it
  }

  /**
   * broadcast Hello to neighbors
   */
  private void processStart() {
    
    // Send Hello packet to all attached links
    for (Link link : ports) {
      if (link != null) {
        sendProcessStartHello(link, link.router2);
      }
    }

    // Configure router status

    // Update own LSD

    // Multicast LSA update packets to neighbors

  }

  private void sendProcessStartHello(Link link, RouterDescription nbr) {
    SOSPFPacket ProcessStartHello = new SOSPFPacket();
    ProcessStartHello.sospfType = 0; // HELLO

    // inter-process addressing (real socket endpoints)
    ProcessStartHello.srcProcessIP = rd.processIPAddress;
    ProcessStartHello.srcProcessPort = rd.processPortNumber;

    // simulated addressing (router IDs)
    ProcessStartHello.srcIP = rd.simulatedIPAddress;
    ProcessStartHello.dstIP = nbr.simulatedIPAddress;

    // HELLO semantic: "I am <src simulated IP>"
    ProcessStartHello.neighborID = rd.simulatedIPAddress;

    sendPacket(ProcessStartHello, nbr);
  }

  private void sendPacket(SOSPFPacket pkt, RouterDescription nbr) {
    try (Socket s = new Socket(nbr.processIPAddress, nbr.processPortNumber);
    ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream())) {
      out.writeObject(pkt);
    } catch (IOException e) {
      System.err.println("Send failed to " + nbr.simulatedIPAddress);
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

  }

  /**
   * disconnect with all neighbors and quit the program
   */
  private void processQuit() {

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
  private void handleApplicationMessage(socs.network.message.SOSPFPacket packet) {

  }

  public void terminal() {
    try {
      InputStreamReader isReader = new InputStreamReader(System.in);
      BufferedReader br = new BufferedReader(isReader);
      System.out.print(">> ");
      String command = br.readLine();
      while (true) {
        if (command.startsWith("detect ")) {
          String[] cmdLine = command.split(" ");
          processDetect(cmdLine[1]);
        } else if (command.startsWith("disconnect ")) {
          String[] cmdLine = command.split(" ");
          processDisconnect(Short.parseShort(cmdLine[1]));
        } else if (command.startsWith("quit")) {
          processQuit();
        } else if (command.startsWith("attach ")) {
          String[] cmdLine = command.split(" ");
          processAttach(cmdLine[1], Short.parseShort(cmdLine[2]),
                  cmdLine[3], Short.parseShort(cmdLine[4]));
        } else if (command.equals("start")) {
          processStart();
        } else if (command.equals("connect ")) {
          String[] cmdLine = command.split(" ");
          processConnect(cmdLine[1], Short.parseShort(cmdLine[2]),
                  cmdLine[3], Short.parseShort(cmdLine[4]));
        } else if (command.equals("neighbors")) {
          //output neighbors
          processNeighbors();
        } else if (command.startsWith("send ")) {
          //send [Destination IP] [Message]
          String[] cmdLine = command.split(" ", 3);
          if (cmdLine.length >= 3) {
            processSend(cmdLine[1], cmdLine[2]);
          } else {
            System.out.println("Usage: send [Destination IP] [Message]");
          }
        } else if (command.startsWith("update ")) {
          //update [port_number] [new_weight]
          String[] cmdLine = command.split(" ");
          if (cmdLine.length >= 3) {
            processUpdate(Short.parseShort(cmdLine[1]), Short.parseShort(cmdLine[2]));
          } else {
            System.out.println("Usage: update [port_number] [new_weight]");
          }
        } else {
          //invalid command
          break;
        }
        System.out.print(">> ");
        command = br.readLine();
      }
      isReader.close();
      br.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

}
