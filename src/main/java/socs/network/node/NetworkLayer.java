package socs.network.node;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

class NetworkLayer {
  private final Router router;
  private ServerSocket serverSocket;
  private Thread acceptThread;
  private volatile boolean running;

  NetworkLayer(Router router) {
    this.router = router;
  }

  // Start the network layer by opening a server socket and starting the accept loop
  void start() throws IOException {
    if (running) {
      return;
    }
    // Open a server socket on the router's process port
    int port = router.getProcessPort();
    serverSocket = new ServerSocket(port);
    serverSocket.setReuseAddress(true);
    int boundPort = serverSocket.getLocalPort();
    if (boundPort != port) {
      router.setProcessPort((short) boundPort);
    }

    // Start the accept loop in a separate thread
    running = true;
    acceptThread = new Thread(this::acceptLoop, "router-accept-" + boundPort);
    acceptThread.setDaemon(true);
    acceptThread.start();

    // Print the listening port for debugging
    System.out.println("Listening on port " + boundPort);
  }

  // Stop the network layer by closing the server socket and stopping the accept loop
  void stop() {
    running = false;
    if (serverSocket != null && !serverSocket.isClosed()) {
      try {
        serverSocket.close();
      } catch (IOException ignored) {
      }
    }
  }

  // The accept loop that continuously accepts incoming connections and spawns handler threads
  private void acceptLoop() {
    while (running) {
      try {
        // Accept an incoming connection (blocking call)
        Socket socket = serverSocket.accept();
        // Spawn a new thread to handle the connection using the router's request handler
        Thread handler = new Thread(() -> router.requestHandler(socket),
          "router-conn-" + socket.getPort());
        // Set the handler thread as a daemon so it doesn't prevent JVM shutdown
        // Basically, the handler thread will run in the background and will not block the application from exiting if the main thread finishes execution
        handler.setDaemon(true);
        handler.start();
      } catch (IOException e) {
        if (running) {
          System.err.println("Accept loop error: " + e.getMessage());
        }
      }
    }
  }
}
