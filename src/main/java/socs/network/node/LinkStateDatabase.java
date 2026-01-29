package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.PriorityQueue;



public class LinkStateDatabase {

  //linkID => LSAInstance
  HashMap<String, LSA> _store = new HashMap<String, LSA>();

  private RouterDescription rd = null;

  public LinkStateDatabase(RouterDescription routerDescription) {
    rd = routerDescription;
    LSA l = initLinkStateDatabase();
    _store.put(l.linkStateID, l);
  }

  /**
   * output the shortest path from this router to the destination with the given IP address
   * <p/>
   * This method should use Dijkstra's algorithm with link weights (not hop count) to find the shortest path.
   * The weights are stored in the weight field of LinkDescription objects in the LSA entries.
   * <p/>
   * format: source ip address -> ip address -> ... -> destination ip
   *
   * @param destinationIP the simulated IP address of the destination router
   * @return the shortest path as a string, or null if no path exists
   */
  String getShortestPath(String destinationIP) {

    // this is my implemnetation based off of 
    // https://www.geeksforgeeks.org/dsa/dijkstras-shortest-path-algorithm-greedy-algo-7/
    //TODO: fill the implementation here
    String sourceIP = rd.simulatedIPAddress;
    
    // Check if destination exists
    if (!_store.containsKey(destinationIP)) {
      return null;
    }
    
    // Check if source and destination are the same
    if (sourceIP.equals(destinationIP)) {
      return sourceIP;
    }
    
    int V = _store.size();
    String[] nodes = (String[]) _store.keySet().toArray();
  
    PriorityQueue<State> pq = new PriorityQueue<State>((a,b) -> Integer.compare(a.distance, b.distance));

    HashMap<String, Integer> distance = new HashMap<>();
    HashMap<String, String> parent = new HashMap<>(); // Track previous node for path reconstruction
    
    for (String id : nodes){
      distance.put(id, Integer.MAX_VALUE);
      parent.put(id, null);
    }

    distance.put(sourceIP, 0);
    pq.offer(new State(sourceIP, 0));

    while(!pq.isEmpty()){
      State top = pq.poll();
      String id = top.nodeIP;
      int dist = top.distance;
      LSA cur_node = _store.get(id);
      if (dist > distance.get(id)){
        continue;
      }

      // explore all adjacent vertices through the linked list
      LinkedList<LinkDescription> adj = cur_node.links;
      for (LinkDescription l : adj){
        String nextNode = l.linkID;
        int nextDist = l.weight;
        if (distance.get(id) + nextDist < distance.get(nextNode)){
          distance.put(nextNode, distance.get(id) + nextDist);
          parent.put(nextNode, id);
          pq.offer(new State(nextNode, distance.get(nextNode)));
        }
      }
    }
    
    // Reconstruct path by backtracking from destination to source
    if (distance.get(destinationIP) == Integer.MAX_VALUE) {
      return null; 
    }
    
    LinkedList<String> path = new LinkedList<>();
    String current = destinationIP;
    while (current != null) {
      path.addFirst(current);
      current = parent.get(current);
    }
    
    // concatenate!!
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < path.size(); i++) {
      result.append(path.get(i));
      if (i < path.size() - 1) {
        result.append(" -> ");
      }
    }
    
    return result.toString();
  }

  //initialize the linkstate database by adding an entry about the router itself
  private LSA initLinkStateDatabase() {
    LSA lsa = new LSA();
    lsa.linkStateID = rd.simulatedIPAddress;
    lsa.lsaSeqNumber = Integer.MIN_VALUE;
    LinkDescription ld = new LinkDescription();
    ld.linkID = rd.simulatedIPAddress;
    ld.portNum = -1;
    ld.tosMetrics = 0;
    ld.weight = 0; //self-link has weight 0
    lsa.links.add(ld);
    return lsa;
  }


  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (LSA lsa: _store.values()) {
      sb.append(lsa.linkStateID).append("(" + lsa.lsaSeqNumber + ")").append(":\t");
      for (LinkDescription ld : lsa.links) {
        sb.append(ld.linkID).append(",").append(ld.portNum).append(",").
                append(ld.weight).append("\t");
      }
      sb.append("\n");
    }
    return sb.toString();
  }

}
