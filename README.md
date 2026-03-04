Usage

Build the jar with dependencies:
mvn clean package assembly:single

Run a router with a config file:
java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router1.conf

Windows convenience:
run-routers.bat (after building) opens 4 PowerShell windows using conf/router1.conf through conf/router4.conf.

Command reference

attach [Process IP] [Process Port] [Simulated IP] [Weight]
	- Sends a HELLO to the remote router and creates a local link on success.
	- Incoming attach requests prompt: "Do you accept this request from <IP>? (Y/N)".

start
	- Sends HELLO to all attached neighbors to reach TWO_WAY state.

neighbors
	- Prints current neighbor simulated IPs from the local LSA.

detect [Destination IP]
	- Prints the shortest path based on the Link State Database.

send [Destination IP] [Message]
	- Sends an application message using shortest-path forwarding.
	- Message text can include spaces.
	- Usage shown by CLI when malformed: `Usage: send [Destination IP] [Message]`.

Using `send`

1) Make sure links are established (via `attach`/`connect`) and routers are in neighbor state (`start`).
2) From the source router terminal, run:

   send 192.168.0.4 hello from router1

3) Expected behavior:
	- Source prints: `Sending message to <Destination IP>`
	- Intermediate routers print a forwarding line.
	- Destination prints sender + message content.

If no route exists in the current Link State Database, the source prints `Path not found`.

connect [Process IP] [Process Port] [Simulated IP] [Weight]
disconnect [port_number]
update [port_number] [new_weight]
	- These commands are wired in the CLI but the implementations are currently stubs.

quit
	- Stops the listener and exits.

Implementation Details

- NetworkLayer opens a ServerSocket on the router process port, spawns a handler thread per connection, and logs the bound port.
- A pending-request queue avoids System.in race conditions: background threads enqueue attach requests, the terminal thread prompts Y/N, then releases the handler.
- attach performs a HELLO handshake on the outgoing socket and creates a Link with neighbor status INIT on success.
- start sends HELLO to each attached link, promotes neighbors to TWO_WAY, and updates the local LSA entry for the link.
- send forwards application messages hop-by-hop: intermediate routers log forwarding, and the destination logs the received message.