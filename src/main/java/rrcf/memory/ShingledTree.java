package rrcf.memory;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Arrays;
import java.util.BitSet;
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
    // TODO: Do we have to considern the tri state thing?
    // TODO: Replace min/max determined with single array and bitset
    // TODO: Collapse unnecessary classes (shingledpoint) into nodes, don't store unnecessary references
    // TODO: Replace with floats again, find a way around imprecision
    private ShingledNode root;
    private int dimension;
    private Random random;
    private double[] rootMinPoint;
    private double[] rootMaxPoint;

    public ShingledTree(Random r, int shingleSize) {
        random = r;
        dimension = shingleSize;
        rootMinPoint = null;
        rootMaxPoint = null;
    }

    public ShingledTree(int shingleSize) {
        this(new Random(), shingleSize);
    }

    @Override
    public String toString() {
        String[] depthAndTreeString = { "", "" };
        if (root == null) return "";
        double[] currentMinBox = rootMinPoint.clone();
        double[] currentMaxBox = rootMaxPoint.clone();
        printNodeToString(root, depthAndTreeString, currentMinBox, currentMaxBox);
        return depthAndTreeString[1];
    }

    /**
     * Prints a node to provided string
     * Updates the given string array: { depth, tree } strings
     */
    private void printNodeToString(ShingledNode node, String[] depthAndTreeString, double[] currentMinBox, double[] currentMaxBox) {
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
            ShingledBranch b = (ShingledBranch)node;
            double[] leftMinBox = currentMinBox.clone();
            double[] leftMaxBox = currentMaxBox.clone();
            double[] rightMinBox = currentMinBox.clone();
            double[] rightMaxBox = currentMaxBox.clone();
            for (int i = 0; i < dimension; i++) {
                // TODO: Extract to function
                if (b.childMinPointDirections.get(i)) {
                    leftMinBox[i] = b.childMinPointValues[i];
                } else {
                    rightMinBox[i] = b.childMinPointValues[i];
                }

                if (b.childMaxPointDirections.get(i)) {
                    leftMaxBox[i] = b.childMaxPointValues[i];
                } else {
                    rightMaxBox[i] = b.childMaxPointValues[i];
                }
            }
            depthAndTreeString[1] += String.format("%c+ cut: (%d, %f), box: (%s, %s)\n", 9472, b.cut.dim, b.cut.value, Arrays.toString(currentMinBox), Arrays.toString(currentMaxBox));
            depthAndTreeString[1] += String.format("%s %c%c%c", depthAndTreeString[0], 9500, 9472, 9472);
            ppush.accept((char) 9474);
            printNodeToString(b.left, depthAndTreeString, leftMinBox, leftMaxBox);
            ppop.run();
            depthAndTreeString[1] += String.format("%s %c%c%c", depthAndTreeString[0], 9492, 9472, 9472);
            ppush.accept(' ');
            printNodeToString(b.right, depthAndTreeString, rightMinBox, rightMaxBox);
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

    public double[] getMinBox() {
        return rootMinPoint.clone();
    }

    public double[] getMaxBox() {
        return rootMaxPoint.clone();
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
    public ShingledLeaf forgetPoint(ShingledPoint point) throws NoSuchElementException {
        ShingledLeaf leaf = findLeaf(point);
        
        if (leaf == null) {
            throw new NoSuchElementException(String.format("Point not found: %s", Arrays.toString(point.toArray())));
        }

        // If duplicate points exist, decrease num for all nodes above
        if (leaf.num > 1) {
            updateLeafCountUpwards(leaf, -1);
            return leaf;
        }

        // If leaf is root
        if (root.equals(leaf)) {
            root = null;
            rootMinPoint = null;
            rootMaxPoint = null;
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
            // TODO: Handle bounding boxes here? <-- Don't think this is necessary
            shrinkBoxUpwards(leaf);
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
        updateLeafCountUpwards(grandparent, -1);
        // Shrink bounding box
        shrinkBoxUpwards(leaf);
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
            rootMinPoint = leaf.point.toArray().clone();
            rootMaxPoint = leaf.point.toArray().clone();
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
        double[] minPoint = rootMinPoint.clone();
        double[] maxPoint = rootMaxPoint.clone();

        // Update main bounding box
        for (int i = 0; i < dimension; i++) {
            if (point.get(i) < rootMinPoint[i]) {
                rootMinPoint[i] = point.get(i);
            }
            if (point.get(i) > rootMaxPoint[i]) {
                rootMaxPoint[i] = point.get(i);
            }
        }

        // Traverse tree until insertion spot found
        while (true) {
            Cut c = insertPointCut(point, minPoint, maxPoint);
            // Has to be less than because less than or equal goes to the left
            // Equal would make node go to the right, excluding some points from query
            if (c.value < minPoint[c.dim]) {
                leaf = new ShingledLeaf(point);
                branch = new ShingledBranch(c, dimension, leaf, node, leaf.num + node.num);
                break;
            // Shouldn't result in going down too far because dimensions with 0 variance have a 0 probability of being chosen?
            } else if (c.value >= maxPoint[c.dim] && point.get(c.dim) > c.value) {
                leaf = new ShingledLeaf(point);
                branch = new ShingledBranch(c, dimension, node, leaf, leaf.num + node.num);
                break;
            } else {
                ShingledBranch b = (ShingledBranch) node;
                parent = b;
                BitSet minSet = (BitSet)b.childMinPointDirections.clone();
                BitSet maxSet = (BitSet)b.childMaxPointDirections.clone();
                if (point.get(b.cut.dim) <= b.cut.value) {
                    node = b.left;
                    useLeftSide = true;
                } else {
                    node = b.right;
                    useLeftSide = false;
                    minSet.flip(0, dimension);
                    maxSet.flip(0, dimension);
                }
                // Update bounding boxes at each step down the tree
                // Through b and not node since values are stored in the parent
                for (int i = minSet.nextSetBit(0); i != -1; i = minSet.nextSetBit(i + 1)) {
                    minPoint[i] = b.childMinPointValues[i];
                }
                for (int i = maxSet.nextSetBit(0); i != -1; i = maxSet.nextSetBit(i + 1)) {
                    maxPoint[i] = b.childMaxPointValues[i];
                }

                for (int i = 0; i < dimension; i++) {
                    // If the path lies on the same side as the min point direction
                    // Update the value and direction
                    // Otherwise nothing needs to be done
                    if (point.get(i) < minPoint[i]) {
                        if (useLeftSide == b.childMinPointDirections.get(i)) {
                            b.childMinPointDirections.flip(i);
                            b.childMinPointValues[i] = minPoint[i];
                        }
                    }
                    // Same for max box
                    if (point.get(i) > maxPoint[i]) {
                        if (useLeftSide == b.childMaxPointDirections.get(i)) {
                            b.childMaxPointDirections.flip(i);
                            b.childMaxPointValues[i] = maxPoint[i];
                        }
                    }
                }

            }
        }

        // Check if cut was found
        assert branch != null;

        // Sets values for newly created branch
        for (int i = 0; i < dimension; i++) {
            branch.childMinPointDirections.set(i, (leaf.point.get(i) < minPoint[i]) != leaf.equals(branch.left));
            branch.childMaxPointDirections.set(i, (leaf.point.get(i) > minPoint[i]) != leaf.equals(branch.left));
            branch.childMinPointValues[i] = Math.max(leaf.point.get(i), minPoint[i]);
            branch.childMaxPointValues[i] = Math.min(leaf.point.get(i), minPoint[i]);
        }

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
        // Increase leaf counts
        updateLeafCountUpwards(parent, 1);
        return leaf;
    }

    /**
     * Shrinks the box up the tree, starting from a node
     * Expected to be called on removal with the removed leaf
     * WARNING: Worst case linear
     */
    private void shrinkBoxUpwards(ShingledLeaf leaf) {
        // TEMPORARY
        TEMPUPDATEBOXES();
        if (true) return;
        // TODO: Merge into insertPoint, saves time because that's already top down
        // The bits of parent's bounding box which are determined by the child
        // --> Whether or not the leaf forms the edge of the bounding box 
        BitSet minDetermined = (BitSet)leaf.parent.childMinPointDirections.clone();
        BitSet maxDetermined = (BitSet)leaf.parent.childMaxPointDirections.clone();
        // {x}Determined is the inverse of {x}PointDirections
        // If left, flip
        // If right, it's already flipped
        if (leaf.equals(leaf.parent.left)) {
            minDetermined.flip(0, dimension);
            maxDetermined.flip(0, dimension);
        }
        ShingledNode node = leaf;
        double[] altMins = new double[dimension];
        double[] altMaxes = new double[dimension];
		while (!minDetermined.isEmpty() || !maxDetermined.isEmpty()) {
            // For the bits that are determined, get their values from the other child
            // TODO: Replace with more efficient algorithm
            // Can go up to root or down to leaves?
            ShingledNode tempN = node;
            mapLeaves((l) -> {
                for (int i = minDetermined.nextSetBit(0); i != -1; i = minDetermined.nextSetBit(i + 1)) {
                    if (l.point.get(i) < altMins[i]) {
                        if (tempN.equals(tempN.parent.left)) {
                            tempN.parent.childMinPointDirections.set(i);
                        } else {
                            tempN.parent.childMinPointDirections.clear(i);
                        }
                        tempN.parent.childMinPointValues[i] = altMins[i];
                        altMins[i] = l.point.get(i);
                    }
                }
                for (int i = maxDetermined.nextSetBit(0); i != -1; i = maxDetermined.nextSetBit(i + 1)) {
                    if (l.point.get(i) > altMaxes[i]) {
                        if (tempN.equals(tempN.parent.left)) {
                            tempN.parent.childMaxPointDirections.set(i);
                        } else {
                            tempN.parent.childMaxPointDirections.clear(i);
                        }
                        tempN.parent.childMaxPointValues[i] = altMaxes[i];
                        altMaxes[i] = l.point.get(i);
                    }
                }
            }, getSibling(node));

            // Update the determined bits with parent
            if (node.equals(node.parent.left)) {
                minDetermined.andNot(node.parent.childMinPointDirections);
                maxDetermined.andNot(node.parent.childMaxPointDirections);
            } else {
                minDetermined.and(node.parent.childMinPointDirections);
                maxDetermined.and(node.parent.childMaxPointDirections);
            }
            node = node.parent;
        }
    }

    private void TEMPUPDATEBOXES() {
        TEMPPOPULATEBB(root);
        rootMinPoint = root.mbb;
        rootMaxPoint = root.ubb;

        mapBranches((branch) -> {
            for (int i = 0; i < dimension; i++) {
                if (branch.left.mbb[i] == branch.mbb[i]) {
                    branch.childMinPointDirections.clear(i);
                    branch.childMinPointValues[i] = branch.right.mbb[i];
                } else {
                    branch.childMinPointDirections.set(i);
                    branch.childMinPointValues[i] = branch.left.mbb[i];
                }

                if (branch.left.ubb[i] == branch.ubb[i]) {
                    branch.childMaxPointDirections.clear(i);
                    branch.childMaxPointValues[i] = branch.right.ubb[i];
                } else {
                    branch.childMaxPointDirections.set(i);
                    branch.childMaxPointValues[i] = branch.left.ubb[i];
                }
            }
        });
    }

    private void TEMPPOPULATEBB(ShingledNode n) {
        n.mbb = new double[dimension];
        n.ubb = new double[dimension];
        if (n instanceof ShingledLeaf) {
            for (int i = 0; i < dimension; i++) {
                n.mbb[i] = ((ShingledLeaf)n).point.get(i);
                n.ubb[i] = ((ShingledLeaf)n).point.get(i);
            }
        } else {
            ShingledBranch b = (ShingledBranch) n;
            TEMPPOPULATEBB(b.left);
            TEMPPOPULATEBB(b.right);
            for (int i = 0; i < dimension; i++) {
                b.mbb[i] = Math.min(b.left.mbb[i], b.right.mbb[i]);
                b.ubb[i] = Math.max(b.left.ubb[i], b.right.ubb[i]);
            }
        }
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
    private Cut insertPointCut(ShingledPoint point, double[] minPoint, double[] maxPoint) {
        double[] newMinBox = new double[minPoint.length];
        double[] span = new double[minPoint.length];
        // Cumulative sum of span
        double[] spanSum = new double[minPoint.length];
        for (int i = 0; i < dimension; i++) {
            newMinBox[i] = Math.min(minPoint[i], point.get(i));
            double maxI = Math.max(maxPoint[i], point.get(i));
            span[i] = maxI - newMinBox[i];
            if (i > 0) {
                spanSum[i] = spanSum[i - 1] + span[i];
            } else {
                spanSum[i] = span[0];
            }
        }
        // Weighted random with each dimension's span
        double range = spanSum[spanSum.length - 1];
        double r = random.nextDouble() * range;
        int cutDim = -1;
        for (int i = 0; i < dimension; i++) {
            // Finds first value greater or equal to chosen
            if (spanSum[i] >= r) {
                cutDim = i;
                break;
            }
        }
        assert cutDim > -1;
        double value = newMinBox[cutDim] + spanSum[cutDim] - r;
        return new Cut(cutDim, value);
    }

    /** 
     * Java doesn't have tuples :(
     */
    public static class Cut {
        // Dimension of cut
        public int dim;
        // Value of cut
        public double value;

        public Cut(int d, double v) {
            dim = d;
            value = v;
        }
    }
}