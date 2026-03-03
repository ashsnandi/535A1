package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;

import java.util.Map;

class WeighedGraph {

  // String is the node ID, int is the weight of the edge. 
  String[] nodeIds;
  int[][] edges;

  // Contructor that builds the graph from the given LSA store. 
  WeighedGraph() {
    nodeIds = new String[0];
    edges = new int[0][0];
  }

  void addEdge(String fromNode, String toNode, int weight) {
    // adds an edge from 'fromNode' to 'toNode' with the given weight.
    // Check if nodes exist. Throw error if not. Update the edges matrix accordingly.
    if (!nodePresent(fromNode) || !nodePresent(toNode)) {
      throw new IllegalArgumentException("One or both nodes do not exist in the graph.");
    }

    // Actual implementation
    int fromNodeIndex = getNodeIndex(fromNode);
    int toNodeIndex = getNodeIndex(toNode);
    edges[fromNodeIndex][toNodeIndex] = weight;
  }

  void addNode(String nodeId) {

    // adds a node with the given ID to the graph.
    // Check if node already exists. Throw error if it does. 
    if (nodePresent(nodeId)) {
      throw new IllegalArgumentException("Node with ID " + nodeId + " already exists in the graph.");
    }

    // Actual implementation
    nodeIds = java.util.Arrays.copyOf(nodeIds, nodeIds.length + 1);
    nodeIds[nodeIds.length - 1] = nodeId;
    // Resize the edges matrix to accommodate the new node
    int[][] updatedEdges = new int[nodeIds.length][nodeIds.length];
    for (int i = 0; i < edges.length; i++) {
      System.arraycopy(edges[i], 0, updatedEdges[i], 0, edges[i].length);
    }
    edges = updatedEdges;
  }

  boolean nodePresent(String nodeId) {
    // Checking if node with given ID is already present in the graph
    for (String id : nodeIds) {
      if (id.equals(nodeId)) {
        return true;
      }
    }
    return false;
  }

  int getNodeIndex(String nodeId) {
    // Scan array for the given node ID and return index
    for (int i = 0; i < nodeIds.length; i++) {
      if (nodeIds[i].equals(nodeId)) {
        return i;
      }
    }
    throw new IllegalArgumentException(nodeId + " node does not exist in the graph.");
  }
}
