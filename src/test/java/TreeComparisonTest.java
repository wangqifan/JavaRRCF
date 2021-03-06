import static org.junit.Assert.assertEquals;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

import rrcf.general.Tree;
import rrcf.general.ShingledForest;
import rrcf.memory.SmallShingledForest;
import rrcf.memory.SmallTree;

public class TreeComparisonTest {
    @Test
    public void ComparisonTestForest() {
        Random rTest = new Random(1);
        testWithNum(rTest, 1, 1, 10, 2, 300);
        for (int i = 0; i < 100; i++) {
            long randomSeed = rTest.nextLong();
            int numTrees = rTest.nextInt(10) + 1;
            int treeSize = rTest.nextInt(100) + 5;
            int shingleSize = rTest.nextInt(20) + 2;
            int numInserts = rTest.nextInt(treeSize * 3) + treeSize;
            testWithNum(rTest, randomSeed, numTrees, treeSize, shingleSize, numInserts);
        }
    }

    private void testWithNum(Random rTest, long randomSeed, int numTrees, int treeSize, int shingleSize, int numInserts) {
        System.out.printf("Testing with (seed %d, trees %d, treeSize %d, shingleSize %d)\n", randomSeed, numTrees,
                treeSize, shingleSize);
        SmallShingledForest testUnknown = new SmallShingledForest(new Random(randomSeed), shingleSize, numTrees, treeSize);
        ShingledForest testVerify = new ShingledForest(new Random(randomSeed), shingleSize, numTrees, treeSize);
        for (int e = 0; e < numInserts; e++) {
            double val = rTest.nextDouble() * 1000;
            System.out.printf("Inserting %f\n", val);
            double v2 = testVerify.addPoint(val);
            double v1 = testUnknown.addPoint(val);
            System.out.println("Expected:");
            System.out.println(testUnknown.toString());
            System.out.println("Actual:");
            System.out.println(testVerify.toString());
            assertEquals(v2, v1, 0.000001);
            assertEquals(testVerify.toString(), testUnknown.toString());
        }
    }

    @Test
    public void ComparisonTestIndividualTree() {
        Random rTest = new Random(1);
        long randomSeed = rTest.nextLong();
        int shingleSize = rTest.nextInt(5) + 2;
        int iters = rTest.nextInt(1000) + 100;
        int maxTreeSize = rTest.nextInt(10) + 10;
        SmallTree testUnknown = new SmallTree(new Random(randomSeed), shingleSize);
        Tree testVerify = new Tree(new Random(randomSeed));
        Map<Integer, double[]> points = new HashMap<>();
        // Technically not using shingling for points
        for (int i = 0; i < iters; i++) {
            System.out.printf("Iteration %d\n", i);
            if (!points.isEmpty() && (rTest.nextDouble() > 0.8 || testVerify.size() > maxTreeSize)) {
                Object[] keys = points.keySet().toArray();
                Integer k = (Integer)keys[rTest.nextInt(keys.length)];
                System.out.printf("Removing %s\n", Arrays.toString(points.get(k)));
                testUnknown.forgetPoint(points.get(k));
                testVerify.forgetPoint(k);
                points.remove(k);
            } else {
                double[] b = new double[shingleSize];
                for (int d = 0; d < shingleSize; d++) {
                    b[d] = rTest.nextInt(10000);
                }
                System.out.printf("Inserting %s\n", Arrays.toString(b));
                points.put(i, b);
                testVerify.insertPoint(b, i);
                testUnknown.insertPoint(b);
            }
            System.out.println("Expected:");
            System.out.println(testVerify.toString());
            System.out.println("Verify:");
            System.out.println(testUnknown.toString());
            System.out.println("\n");
            assertEquals(testVerify.toString(), testUnknown.toString());
        }
    }
}