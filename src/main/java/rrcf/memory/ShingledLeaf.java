package rrcf.memory;

public class ShingledLeaf extends ShingledNode {
    public ShingledPoint point;

    public ShingledLeaf(ShingledPoint p) {
        point = p;
        num = 1;
    }
}