package socs.network.node;

public class Link {

  RouterDescription router1;
  RouterDescription router2;
  int portNum;
  int weight;

  public Link(RouterDescription r1, RouterDescription r2, int portNum, int weight) {
    router1 = r1;
    router2 = r2;
    this.portNum = portNum;
    this.weight = weight;
  }
}
