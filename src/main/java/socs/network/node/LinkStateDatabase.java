package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;



public class LinkStateDatabase {

  //linkID => LSAInstance
  HashMap<String, LSA> _store = new HashMap<String, LSA>();

  WeighedGraph graph = new WeighedGraph();

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
    List<String> path = computeShortestPathNodes(destinationIP);
    if (path == null) {
      return null;
    }

    StringBuilder result = new StringBuilder();
    for (int i = 0; i < path.size(); i++) {
      result.append(path.get(i));
      if (i < path.size() - 1) {
        result.append(" -> ");
      }
    }
    return result.toString();
  }

  String getShortestPathForDisplay(String destinationIP) {
    List<String> path = computeShortestPathNodes(destinationIP);
    if (path == null) {
      return null;
    }

    if (path.size() == 1) {
      return "Path found: " + path.get(0);
    }

    StringBuilder result = new StringBuilder("Path found: ");
    result.append(path.get(0));
    for (int i = 1; i < path.size(); i++) {
      Integer weight = getEdgeWeight(path.get(i - 1), path.get(i));
      if (weight == null) {
        return null;
      }
      result.append(" -> (").append(weight).append(") ").append(path.get(i));
    }
    return result.toString();
  }

  private List<String> computeShortestPathNodes(String destinationIP) {
    String sourceIP = rd.simulatedIPAddress;

    if (destinationIP == null || destinationIP.isEmpty() || !_store.containsKey(destinationIP)) {
      return null;
    }

    if (sourceIP.equals(destinationIP)) {
      return Collections.singletonList(sourceIP);
    }

    PriorityQueue<State> pq = new PriorityQueue<>((a, b) -> Integer.compare(a.distance, b.distance));
    HashMap<String, Integer> distance = new HashMap<>();
    HashMap<String, String> parent = new HashMap<>();

    for (String id : _store.keySet()) {
      distance.put(id, Integer.MAX_VALUE);
      parent.put(id, null);
    }

    distance.put(sourceIP, 0);
    pq.offer(new State(sourceIP, 0));

    while (!pq.isEmpty()) {
      State top = pq.poll();
      String id = top.nodeIP;
      int dist = top.distance;

      Integer known = distance.get(id);
      if (known == null || dist > known) {
        continue;
      }

      LSA currentNode = _store.get(id);
      if (currentNode == null || currentNode.links == null) {
        continue;
      }

      for (LinkDescription link : currentNode.links) {
        String nextNode = link.linkID;
        if (!_store.containsKey(nextNode) || !distance.containsKey(nextNode)) {
          continue;
        }

        int nextDist = distance.get(id) + link.weight;
        if (nextDist < distance.get(nextNode)) {
          distance.put(nextNode, nextDist);
          parent.put(nextNode, id);
          pq.offer(new State(nextNode, nextDist));
        }
      }
    }

    Integer destinationDistance = distance.get(destinationIP);
    if (destinationDistance == null || destinationDistance == Integer.MAX_VALUE) {
      return null;
    }

    LinkedList<String> path = new LinkedList<>();
    String current = destinationIP;
    while (current != null) {
      path.addFirst(current);
      current = parent.get(current);
    }

    return new ArrayList<>(path);
  }

  private Integer getEdgeWeight(String from, String to) {
    LSA lsa = _store.get(from);
    if (lsa == null || lsa.links == null) {
      return null;
    }

    for (LinkDescription link : lsa.links) {
      if (to.equals(link.linkID)) {
        return link.weight;
      }
    }
    return null;
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
