package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;

import java.util.Map;

class WeighedGraph {

  // String is the node ID, int is the weight of the edge. 
  String[] nodeIds;
  int[][] edges;

  // Contructor that builds the graph from the given LSA store. 
  // It extracts all unique node IDs and initializes the edges based on the link descriptions in the LSAs.
  WeighedGraph() {
    
  }
}
