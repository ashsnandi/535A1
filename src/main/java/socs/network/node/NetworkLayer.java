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

  void start() throws IOException {
    if (running) {
      return;
    }
    int port = router.getProcessPort();
    serverSocket = new ServerSocket(port);
    serverSocket.setReuseAddress(true);
    int boundPort = serverSocket.getLocalPort();
    if (boundPort != port) {
      router.setProcessPort((short) boundPort);
    }

    running = true;
    acceptThread = new Thread(this::acceptLoop, "router-accept-" + boundPort);
    acceptThread.setDaemon(true);
    acceptThread.start();

    System.out.println("Listening on port " + boundPort);
  }

  void stop() {
    running = false;
    if (serverSocket != null && !serverSocket.isClosed()) {
      try {
        serverSocket.close();
      } catch (IOException ignored) {
      }
    }
  }

  private void acceptLoop() {
    while (running) {
      try {
        Socket socket = serverSocket.accept();
        Thread handler = new Thread(() -> router.requestHandler(socket),
            "router-conn-" + socket.getPort());
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
