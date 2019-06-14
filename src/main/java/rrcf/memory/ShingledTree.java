package rrcf.memory;

import java.util.Map;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;
import java.util.function.Consumer;

import rrcf.memory.ShingledBranch;
import rrcf.memory.ShingledLeaf;
import rrcf.memory.ShingledNode;

import java.io.Serializable;

/**
 * Robust random cut tree data structure used for anomaly detection on streaming
 * data
 * 
 * Represents a single random cut tree, supporting shingled data points of one dimension
 */
public class ShingledTree implements Serializable {
    // TODO: Test with leaves map / array instead of getting leaves at runtime
    // TODO: Replace with floats?
    private ShingledNode root;
    private int dimension;
    private Random random;

    public ShingledTree(Random r, int shingleSize) {
        random = r;
        dimension = shingleSize;
    }

    public ShingledTree(int shingleSize) {
        this(new Random(), shingleSize);
    }

    @Override
    public String toString() {
        String[] depthAndTreeString = { "", "" };
        printNodeToString(root, depthAndTreeString);
        return depthAndTreeString[1];
    }

    /**
     * Prints a node to provided string
     * Updates the given string array: { depth, tree } strings
     */
    private void printNodeToString(ShingledNode node, String[] depthAndTreeString) {
        Consumer<Character> ppush = (c) -> {
            String branch = String.format(" %c  ", c);
            depthAndTreeString[0] += branch;
        };
        Runnable ppop = () -> {
            depthAndTreeString[0] = depthAndTreeString[0].substring(0, depthAndTreeString[0].length() - 4);
        };
        if (node instanceof ShingledLeaf) {
            depthAndTreeString[1] += String.format("(%s)\n", Arrays.toString(((ShingledLeaf)node).point.toArray()));
        } else if (node instanceof ShingledBranch) {
            depthAndTreeString[1] += String.format("%c+\n", 9472);
            depthAndTreeString[1] += String.format("%s %c%c%c", depthAndTreeString[0], 9500, 9472, 9472);
            ppush.accept((char) 9474);
            printNodeToString(((ShingledBranch) node).left, depthAndTreeString);
            ppop.run();
            depthAndTreeString[1] += String.format("%s %c%c%c", depthAndTreeString[0], 9492, 9472, 9472);
            ppush.accept(' ');
            printNodeToString(((ShingledBranch) node).right, depthAndTreeString);
            ppop.run();
        }
    }

    public void mapLeaves(Consumer<ShingledLeaf> func) {
        mapLeaves(func, root);
    }

    private void mapLeaves(Consumer<ShingledLeaf> func, ShingledNode n) {
        if (n instanceof ShingledLeaf) {
            func.accept((ShingledLeaf) n);
        } else {
            ShingledBranch b = (ShingledBranch) n;
            if (b.left != null) {
                mapLeaves(func, b.left);
            }
            if (b.right != null) {
                mapLeaves(func, b.right);
            }
        }
    }

    public void mapBranches(Consumer<ShingledBranch> func) {
        mapBranches(func, root);
    }

    private void mapBranches(Consumer<ShingledBranch> func, ShingledNode n) {
        if (n instanceof ShingledBranch) {
            ShingledBranch b = (ShingledBranch) n;
            if (b.left != null) {
                mapBranches(func, b.left);
            }
            if (b.right != null) {
                mapBranches(func, b.right);
            }
            func.accept(b);
        }
    }

    /**
     * Delete a leaf (found from index) from the tree and return deleted node
     */
    public ShingledLeaf forgetPoint(ShingledPoint point) {
        ShingledLeaf leaf = findLeaf(point);

        // If duplicate points exist, decrease num for all nodes above
        if (leaf.num > 1) {
            updateLeafCountUpwards(leaf, -1);
            return leaf;
        }

        // If leaf is root
        if (root.equals(leaf)) {
            root = null;
            return leaf;
        }

        // Calculate parent and sibling
        ShingledBranch parent = leaf.parent;
        ShingledNode sibling = getSibling(leaf);

        // If parent is root, set sibling to root and update depths
        if (root.equals(parent)) {
            sibling.parent = null;
            leaf.parent = null; // In case the returned node is used somehow
            root = sibling;
            return leaf;
        }

        // Move sibling up a layer and link nodes
        ShingledBranch grandparent = parent.parent;
        sibling.parent = grandparent;
        if (parent.equals(grandparent.left)) {
            grandparent.left = sibling;
        } else {
            grandparent.right = sibling;
        }
        parent = grandparent;

        // Update leaf counts for each branch
        updateLeafCountUpwards(parent, -1);
        return leaf;
    }

    /**
     * Insert a point into the tree with a given index and create a new leaf
     */
    public ShingledLeaf insertPoint(ShingledPoint point) {
        // If no points, set necessary variables
        if (root == null) {
            ShingledLeaf leaf = new ShingledLeaf(point);
            root = leaf;
            return leaf;
        }

        // Check that dimensions are consistent and index doesn't exist
        assert point.size() == dimension;

        // Check for duplicates and only update counts if it exists
        ShingledLeaf duplicate = findLeaf(point);
        if (duplicate != null) {
            updateLeafCountUpwards(duplicate, 1);
            return duplicate;
        }

        // No duplicates found, continue
        ShingledNode node = root;
        ShingledBranch parent = null;
        ShingledLeaf leaf = null;
        ShingledBranch branch = null;
        boolean useLeftSide = false;
        // Traverse tree until insertion spot found
        while (true) {
            // Update bounding boxes at each step down the tree
            // NOTE: VERY INEFFICIENT, SHOULD ONLY BE TEMPORARY
            float[][] boundingBox = generateBoundingBox(node);
            float[] minPoint = boundingBox[0];
            float[] maxPoint = boundingBox[1];
            Cut c = insertPointCut(point, minPoint, maxPoint);
            if (c.value <= minPoint[c.dim]) {
                leaf = new ShingledLeaf(point);
                branch = new ShingledBranch(c, leaf, node, leaf.num + node.num);
                break;
            } else if (c.value >= maxPoint[c.dim]) {
                leaf = new ShingledLeaf(point);
                branch = new ShingledBranch(c, node, leaf, leaf.num + node.num);
                break;
            } else {
                ShingledBranch b = (ShingledBranch) node;
                parent = b;
                if (point.get(b.cut.dim) <= b.cut.value) {
                    node = b.left;
                    useLeftSide = true;
                } else {
                    node = b.right;
                    useLeftSide = false;
                }
            }
        }

        // Check if cut was found
        assert branch != null;

        node.parent = branch;
        leaf.parent = branch;
        branch.parent = parent;
        if (parent != null) {
            if (useLeftSide) {
                parent.left = branch;
            } else {
                parent.right = branch;
            }
        } else {
            root = branch;
        }

        updateLeafCountUpwards(parent, 1);
        return leaf;
    }

    /**
     * Gets the sibling of a node
     */
    private ShingledNode getSibling(ShingledNode n) {
        ShingledBranch parent = n.parent;
        if (n.equals(parent.left)) {
            return parent.right;
        }
        return parent.left;
    }

    /**
     * Increases the leaf number for all ancestors above a given node by increment
     */
    private void updateLeafCountUpwards(ShingledNode node, int increment) {
        while (node != null) {
            node.num += increment;
            node = node.parent;
        }
    }

    /**
     * Generates a bounding box for use on point insertion
     * WARNING: O(n) operation
     */
    private float[][] generateBoundingBox(ShingledNode n) {
        float[][] box = new float[2][dimension];
        for (int i = 0; i < dimension; i++) {
            box[0][i] = Float.MAX_VALUE;
            box[1][i] = Float.MIN_VALUE;
        }
        mapLeaves((leaf) -> {
            for (int i = 0; i < dimension; i++) {
                if (leaf.point.get(i) < box[0][i]) {
                    box[0][i] = leaf.point.get(i);
                }
                if (leaf.point.get(i) > box[1][i]) {
                    box[1][i] = leaf.point.get(i);
                }
            }
        }, n);
        return box;
    }

    /**
     * Finds the closest leaf to a point under a specified node
     */
    private ShingledLeaf query(ShingledPoint point) {
        ShingledNode n = root;
        while (!(n instanceof ShingledLeaf)) {
            ShingledBranch b = (ShingledBranch) n;
            if (point.get(b.cut.dim) <= b.cut.value) {
                n = b.left;
            } else {
                n = b.right;
            }
        }
        return (ShingledLeaf) n;
    }

    /**
     * The maximum number of nodes displaced by removing any subset of the tree including a leaf 
     * In practice, there are too many subsets to consider so it can be estimated by looking up the tree
     * There is no definitive algorithm to empirically calculate codisp, so the ratio of sibling num to node num is used
     */
    public int getCollusiveDisplacement(ShingledLeaf leaf) {
        if (leaf.equals(root)) {
            return 0;
        }

        ShingledNode node = leaf;
        int maxResult = -1;
        while (node != null) {
            ShingledBranch parent = node.parent;
            if (parent == null)
                break;
            ShingledNode sibling;
            if (node.equals(parent.left)) {
                sibling = parent.right;
            } else {
                sibling = parent.left;
            }
            int deleted = node.num;
            int displacement = sibling.num;
            maxResult = Math.max(maxResult, displacement / deleted);
            node = parent;
        }
        return maxResult;
    }

    /**
     * Returns a leaf containing a point if it exists
     */
    public ShingledLeaf findLeaf(ShingledPoint point) {
        ShingledLeaf nearest = query(point);
        if (nearest.point.equals(point)) {
            return nearest;
        }
        return null;
    }

    /**
     * Generates a random cut from the span of a point and bounding box
     */
    private Cut insertPointCut(ShingledPoint point, float[] minBox, float[] maxBox) {
        float[] newMinBox = new float[minBox.length];
        float[] newMaxBox = new float[maxBox.length];
        float[] span = new float[minBox.length];
        // Cumulative sum of span
        // TODO: Remove newMaxBox?
        float[] spanSum = new float[minBox.length];
        for (int i = 0; i < dimension; i++) {
            newMinBox[i] = Math.min(minBox[i], point.get(i));
            newMaxBox[i] = Math.max(maxBox[i], point.get(i));
            span[i] = newMaxBox[i] - newMinBox[i];
            if (i > 0) {
                spanSum[i] = spanSum[i - 1] + span[i];
            } else {
                spanSum[i] = span[0];
            }
        }
        // Weighted random with each dimension's span
        float range = spanSum[spanSum.length - 1];
        float r = random.nextFloat() * range;
        int cutDim = -1;
        for (int i = 0; i < dimension; i++) {
            // Finds first value greater or equal to chosen
            if (spanSum[i] >= r) {
                cutDim = i;
                break;
            }
        }
        assert cutDim > -1;
        float value = newMinBox[cutDim] + spanSum[cutDim] - r;
        return new Cut(cutDim, value);
    }

    /** 
     * Java doesn't have tuples :(
     */
    public static class Cut {
        // Dimension of cut
        public int dim;
        // Value of cut
        public float value;

        public Cut(int d, float v) {
            dim = d;
            value = v;
        }
    }
}