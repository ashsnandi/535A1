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
	- Floods LSAUPDATE information so Link State Databases synchronize (PA2).

connect [Process IP] [Process Port] [Simulated IP] [Weight]
	- Behaves like `attach`, but is only allowed after `start` has been executed.
	- Establishes/updates the link and synchronizes weight via CONNECT control packet (type 3).
	- On success, updates local LSDB, increments local LSA sequence number, and broadcasts LSAUPDATE.

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

disconnect [port_number]
- sends disconnect packet, removes local port/LSA entry, floods update, handleDisconnectPacket() applies mirrored teardown on neighbor's end. Additionally, handles disconnects implicitly during LSAUPDATE processing in handleLsaUpdate(). if neighbor’s newest LSA no longer lists this router, local router mirrors removal, updates own LSA, floods the change.


update [port_number] [new_weight] 
- changes weight of link attached at the selected port. validates the port exists and new weight is positive, updates link cost, updates router’s local Link State Advertisement with the new weight, and floods LSAUPDATE so other routers in network get updated weight.

quit
	- Sends DISCONNECT packets for all active links (same behavior as running `disconnect` on each occupied port), then stops the listener and exits.

Implementation Details

- NetworkLayer opens a ServerSocket on the router process port, spawns a handler thread per connection, and logs the bound port.
- A pending-request queue avoids System.in race conditions: background threads enqueue attach requests, the terminal thread prompts Y/N, then releases the handler.
- attach performs a HELLO handshake on the outgoing socket and creates a Link with neighbor status INIT on success.
- start sends HELLO to each attached link, promotes neighbors to TWO_WAY, and updates the local LSA entry for the link.
- LSA flooding: each type-1 LSAUPDATE packet carries the router’s full local LSD (`lsaArray = all LSAs in store`) and is sent to all TWO_WAY neighbors.
- LSA merge policy: on receive, LSAs are accepted only if sequence number is newer than the local copy; if any entry changes, updates are re-flooded to all TWO_WAY neighbors except the sender.
- Disconnect propagation: if a TWO_WAY neighbor’s latest LSA no longer lists this router, the local port/link is removed, local LSA is incremented, and the change is flooded.
- Explicit disconnect signaling: `disconnect` and `quit` use type-2 DISCONNECT packets so peers tear down mirrored links promptly.
- Dijkstra (for `detect` and `send` next-hop): shortest path is computed over weighted links using a priority queue, distance map, and parent map (weight-based, not hop-count based).
- Path rendering for `detect`: output includes per-edge weights in the displayed path (e.g., `A -> (w) B -> (w) C`).
- send forwards application messages hop-by-hop: intermediate routers log forwarding, and the destination logs the received message.
- Weight updates change the local link cost, updates the local LSA to reflect, and then floods the new cost so shortest-path calculations reflect the new weight on other routers

Teamwork Note:
- Ash and Charlie discussed assignments after every class, and otherwise coordinated over text. Work was split evenly across PA1/2/3. Occasionally one of us would assign ourselves a certain function, but it was largely mixed work even within the implementation of a single function. We both tested everything for every PA, and if we found errors would take it upon ourselves to fix them. Overarching design decisions were largely formulated through texting with each other, especially when debugging required a review of design decisions. This note is signed off on by both Ash and Charlie