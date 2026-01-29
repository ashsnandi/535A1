package socs.network.node;

public class State {
    String nodeIP;
    int distance;
    
    State(String nodeIP, int distance) {
        this.nodeIP = nodeIP;
        this.distance = distance;
    }
}
