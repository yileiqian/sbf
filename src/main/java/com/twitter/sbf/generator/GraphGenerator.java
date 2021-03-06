/**
 * Copyright 2020 Twitter. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.sbf.generator;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;

import org.apache.commons.math3.distribution.GeometricDistribution;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.random.JDKRandomGenerator;
import org.apache.commons.math3.random.RandomAdaptor;

import com.twitter.sbf.graph.Graph;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

public class GraphGenerator {
  public int numClusters;
  public double fractionGlobalEdges;
  private double globalP = 0;
  public double minProbInsideCluster;
  public double maxProbInsideCluster;
  public int minClusterSize;
  public int maxClusterSize;
  private RandomAdaptor rng;
  private boolean isWeighted;

  // weights are assumed to follow a bimodal distribution, a simple mixture of two normals.
  // std.dev is assumed to be 1/4 of the mean
  private float lowerWeightMode; // mean of normal to the left.
  private float fractionHigherWeight; // fraction of samples to draw from the higher weight normal
  private static double minWeight = 0.01; // Math.max(sampledWeight, minWeight)

  private NormalDistribution higherWeightDist;
  private NormalDistribution lowerWeightDist;


  public GraphGenerator(int numClusters,
                        double fractionGlobalEdges,
                        double minProbInsideCluster,
                        double maxProbInsideCluster,
                        int minClusterSize,
                        int maxClusterSize,
                        RandomAdaptor r) {
    this(numClusters, fractionGlobalEdges, minProbInsideCluster, maxProbInsideCluster,
        minClusterSize, maxClusterSize, r, false, 0,
        0, 0);
  }

  public GraphGenerator(
      int numClusters,
      double fractionGlobalEdges,
      double minProbInsideCluster,
      double maxProbInsideCluster,
      int minClusterSize,
      int maxClusterSize,
      RandomAdaptor r,
      boolean isWeighted,
      float higherWeightMode,
      float lowerWeightMode,
      float fractionHigherWeight) {
    assert minClusterSize > 2;
    assert maxClusterSize > 2;
    assert maxClusterSize > minClusterSize;
    assert minProbInsideCluster > 0 && minProbInsideCluster < 1;
    assert maxProbInsideCluster > 0 && maxProbInsideCluster < 1;
    assert maxProbInsideCluster > minProbInsideCluster;

    this.numClusters = numClusters;
    this.fractionGlobalEdges = fractionGlobalEdges;
    this.minProbInsideCluster = minProbInsideCluster;
    this.maxProbInsideCluster = maxProbInsideCluster;
    this.minClusterSize = minClusterSize;
    this.maxClusterSize = maxClusterSize;
    this.rng = r;
    this.isWeighted = isWeighted;
    this.lowerWeightMode = lowerWeightMode;
    if (isWeighted) {
      assert lowerWeightMode > 0;
      assert higherWeightMode > 0;
      assert fractionHigherWeight > 0;
      assert higherWeightMode > lowerWeightMode;
      higherWeightDist = new NormalDistribution(this.rng, higherWeightMode,
          higherWeightMode / 4);
      lowerWeightDist = new NormalDistribution(this.rng, lowerWeightMode, lowerWeightMode / 4);
      this.fractionHigherWeight = fractionHigherWeight;
    }
  }

  private double getGlobalProbability() {
    if (globalP == 0) {
      double n = expectedVertices();
      globalP = fractionGlobalEdges * expectedIntraClusterEdges() * 2
          / ((1.0 - fractionGlobalEdges) * n * (n - 1));
    }
    return globalP;
  }

  private double sampleEdgeWeight() {
    double ret = 0;
    if (this.rng.nextDouble() < this.fractionHigherWeight) {
      ret = higherWeightDist.sample();
    } else {
      ret = lowerWeightDist.sample();
    }
    if (ret <= 0) {
      ret = GraphGenerator.minWeight;
    }
    return ret;
  }

  public double expectedVertices() {
    return numClusters * (maxClusterSize + minClusterSize) / 2;
  }

  /**
   * Complicated integral that evaluates expected number of intra-cluster edges.
   * <p>
   * Unfortunately this doesn't work very well for overlapping clusters, due to edges
   * that get generated by more than one cluster, which get overcounted here.
   */
  public double expectedIntraClusterEdges() {
    double deltaP = maxProbInsideCluster - minProbInsideCluster;
    double deltaC = maxClusterSize - minClusterSize;
    double cMax = maxClusterSize;
    double cMin = minClusterSize;
    double pMin = minProbInsideCluster;
    double cMaxValue = (pMin * deltaC + (cMax + 1) * deltaP) * Math.pow(cMax, 3) / 3
        - deltaP * Math.pow(cMax, 4) / 4 - (pMin * deltaC + cMax * deltaP) * cMax * cMax / 2;
    double cMinValue = (pMin * deltaC + (cMax + 1) * deltaP) * Math.pow(cMin, 3) / 3
        - deltaP * Math.pow(cMin, 4) / 4 - (pMin * deltaC + cMax * deltaP) * cMin * cMin / 2;
    assert cMaxValue - cMinValue > 0;
    return numClusters * (cMaxValue - cMinValue) / (deltaC * deltaC) / 2;
  }


  /**
   * Smallest possible clusters get max probability, biggest clusters get least probability.
   * Everything in between gets a linear interpolation between the two probabilities.
   *
   * @param clusterSize size of cluster for which to get probability
   * @return probability
   */
  private double getProbabilityInsideCluster(int clusterSize) {
    assert clusterSize >= minClusterSize && clusterSize <= maxClusterSize;
    return minProbInsideCluster
        + ((maxClusterSize - clusterSize) * 1.0 / (maxClusterSize - minClusterSize))
        * (maxProbInsideCluster - minProbInsideCluster);
  }

  /**
   * Mixed membership block stochastic model
   */
  public GraphAndGroundTruth generateWithOverlappingClusters(
      int maxClustersForAVertex, double averageNumberOfClustersPerVertex) {
    Random r = this.rng;

    assert averageNumberOfClustersPerVertex > 1.0;

    int sumOfClusterSizes = 0;
    int[][] clusterToVertices = new int[numClusters][];
    for (int clusterId = 0; clusterId < numClusters; clusterId++) {
      int sampledClusterSize = minClusterSize + r.nextInt(maxClusterSize - minClusterSize);
      clusterToVertices[clusterId] = new int[sampledClusterSize];
      sumOfClusterSizes += sampledClusterSize;
    }
    int numNodes = (int) Math.ceil(sumOfClusterSizes / averageNumberOfClustersPerVertex);

    IntSet[] vertexToClusterIds = new IntOpenHashSet[numNodes];
    for (int vertexId = 0; vertexId < numNodes; vertexId++) {
      vertexToClusterIds[vertexId] = new IntOpenHashSet(maxClustersForAVertex);
    }

    for (int clusterId = 0; clusterId < numClusters; clusterId++) {
      for (int j = 0; j < clusterToVertices[clusterId].length; j++) {
        int sampledNode;
        IntSet clustersForSampledNode;
        while (true) {
          sampledNode = r.nextInt(numNodes);
          clustersForSampledNode = vertexToClusterIds[sampledNode];
          if (!clustersForSampledNode.contains(clusterId)
              && clustersForSampledNode.size() < maxClustersForAVertex) {
            break;
          }
        }
        clusterToVertices[clusterId][j] = sampledNode;
        clustersForSampledNode.add(clusterId);
      }
    }

    ArrayList<HashSet<Integer>> adjLists = new ArrayList<>(numNodes);
    ArrayList<HashMap<Integer, Integer>> intersectionSizesForEachEdge = new ArrayList<>(numNodes);
    for (int i = 0; i < numNodes; i++) {
      adjLists.add(new HashSet<>());
      intersectionSizesForEachEdge.add(new HashMap<>());
    }

    int numEdges = 0;
    int numIntraClusterEdges = 0;
    int numRepeatedIntraClusterEdges = 0;
    for (int clusterId = 0; clusterId < numClusters; clusterId++) {
      int sizeOfThisCluster = clusterToVertices[clusterId].length;
      double probForThisCluster = getProbabilityInsideCluster(clusterToVertices[clusterId].length);
      GeometricDistribution gd = new GeometricDistribution(rng, probForThisCluster);

      int numEdgesInsideThisCluster = 0;
      for (int nodeIdInsideCluster = 0; nodeIdInsideCluster < sizeOfThisCluster;
           nodeIdInsideCluster++) {
        // Starting from nodeId after current node, repeat
        for (int neighborNode = nodeIdInsideCluster + 1; neighborNode < sizeOfThisCluster;) {
          int numNodesToSkip = gd.sample();
          neighborNode += numNodesToSkip;
          if (neighborNode < sizeOfThisCluster) {
            int nId1 = clusterToVertices[clusterId][nodeIdInsideCluster];
            int nId2 = clusterToVertices[clusterId][neighborNode];
            neighborNode++;
            numEdgesInsideThisCluster++;
            numIntraClusterEdges++;
            if (!adjLists.get(nId1).contains(nId2)) {
              adjLists.get(nId1).add(nId2);
              adjLists.get(nId2).add(nId1);
              int smaller = Math.min(nId1, nId2);
              int bigger = Math.max(nId1, nId2);
              intersectionSizesForEachEdge.get(smaller).put(bigger, 1);
              numEdges++;
            } else {
              numRepeatedIntraClusterEdges++;
              int smaller = Math.min(nId1, nId2);
              int bigger = Math.max(nId1, nId2);
              int current = intersectionSizesForEachEdge.get(smaller).get(bigger);
              intersectionSizesForEachEdge.get(smaller).put(bigger, current + 1);
            }
          }
        }
      }

      float actualProbInsideCluster =
          numEdgesInsideThisCluster * 1.0f / ((sizeOfThisCluster * (sizeOfThisCluster - 1)) / 2);
      assert Math.abs(actualProbInsideCluster - probForThisCluster) < 0.1
          : String.format("cluster %d: size %d, assigned prob. %f, actual prob. %f\n",
            clusterId + 1, sizeOfThisCluster, probForThisCluster, actualProbInsideCluster);
    }

    int numGlobalEdges = 0;
    GeometricDistribution gd = new GeometricDistribution(rng, getGlobalProbability());
    for (int nodeId = 0; nodeId < numNodes; nodeId++) {
      for (int neighborNodePosition = nodeId + 1; neighborNodePosition < numNodes;) {
        int numNodesToSkip = gd.sample();
        neighborNodePosition += numNodesToSkip;
        if (neighborNodePosition < numNodes) {
          if (!adjLists.get(nodeId).contains(neighborNodePosition)) {
            adjLists.get(nodeId).add(neighborNodePosition);
            adjLists.get(neighborNodePosition).add(nodeId);
            neighborNodePosition++;
            numEdges++;
            numGlobalEdges++;
          }
        }
      }
    }

    int[][] nbrs = new int[numNodes][];
    int numEdges2 = 0;
    for (int i = 0; i < numNodes; i++) {
      nbrs[i] = new int[adjLists.get(i).size()];
      int j = 0;
      HashSet<Integer> s = adjLists.get(i);
      for (int neighbor : s) {
        nbrs[i][j] = neighbor;
        j++;
        numEdges2++;
      }
      Arrays.sort(nbrs[i]);
    }

    float[][] wts = null;
    int[] histogramOfIntersectionSizes = new int[maxClustersForAVertex + 1];
    if (this.isWeighted) {

      wts = new float[numNodes][];
      for (int i = 0; i < numNodes; i++) {
        wts[i] = new float[nbrs[i].length];
        for (int j = 0; j < wts[i].length; j++) {
          int intersectionCount = 0;
          if (i < nbrs[i][j]) {
            intersectionCount = intersectionSizesForEachEdge.get(i)
                .getOrDefault(nbrs[i][j], 0);
          } else {
            intersectionCount = intersectionSizesForEachEdge.get(nbrs[i][j])
                .getOrDefault(i, 0);
          }
          histogramOfIntersectionSizes[intersectionCount]++;

          // Consider only cells in the lower-triangular part of the adjacency matrix, and then
          // replicate the weight to the corresponding cell in the upper-triangular part.
          if (nbrs[i][j] < i) {
            float newWt = 0;
            if (intersectionCount == 0) {
              newWt = (float) (lowerWeightMode - 1.5 * lowerWeightDist.getStandardDeviation());
            } else {
              newWt = 0;
              for (int sampleCount = 0; sampleCount < intersectionCount; sampleCount++) {
                newWt += (float) sampleEdgeWeight();
              }
            }
            wts[i][j] = newWt;
            int indexOfIInJ = Arrays.binarySearch(nbrs[nbrs[i][j]], i);
            wts[nbrs[i][j]][indexOfIInJ] = newWt;
          }
        }
      }
    }

    System.err.print("Histogram of intersection sizes: ");
    for (int i = 0; i <= maxClustersForAVertex; i++) {
      System.err.format("%d -> %d, ", i, histogramOfIntersectionSizes[i]);
    }
    System.err.println();
    System.err.format("Num repeated intra-cluster edges: %d\n", numRepeatedIntraClusterEdges);
    System.err.format(
        "Num intra-cluster edges: %d, num global edges: %d, fraction global edges: %f\n",
        numIntraClusterEdges, numGlobalEdges, numGlobalEdges * 1.0f / numEdges);

    assert numEdges * 2 == numEdges2
        : String.format("numEdges %d, numEdges2 %d", numEdges, numEdges2);

    IntSet[] clustersAsSets = new IntSet[numClusters];
    for (int i = 0; i < numClusters; i++) {
      clustersAsSets[i] = new IntOpenHashSet(clusterToVertices[i]);
    }

    return new GraphAndGroundTruth(new Graph(numNodes, numEdges, nbrs, wts), clustersAsSets);
  }

  /**
   * Single membership block stochastic model
   */
  public GraphAndGroundTruth generateWithDisjointClusters() {
    int[] firstIdsInEachCluster = new int[numClusters];
    int[] lastIdsInEachCluster = new int[numClusters];
    IntSet[] groundTruthClusters = new IntSet[numClusters];

    Random r = this.rng;
    int numNodes = 0;
    for (int i = 0; i < numClusters; i++) {
      int sampledClusterSize = minClusterSize + r.nextInt(maxClusterSize - minClusterSize);
      firstIdsInEachCluster[i] = numNodes;
      lastIdsInEachCluster[i] = numNodes + sampledClusterSize - 1;
      numNodes += sampledClusterSize;
    }

    int[] vertexToClusterId = new int[numNodes];
    groundTruthClusters[0] = new IntOpenHashSet();
    for (int i = 0, clusterId = 0; i < numNodes; i++) {
      if (i > lastIdsInEachCluster[clusterId]) {
        clusterId++;
        groundTruthClusters[clusterId] = new IntOpenHashSet();
      }
      vertexToClusterId[i] = clusterId;
      groundTruthClusters[clusterId].add(i);
      if (i == numNodes - 1) {
        assert clusterId == numClusters - 1;
      }
    }

    ArrayList<HashSet<Integer>> adjLists = new ArrayList<>(numNodes);
    for (int i = 0; i < numNodes; i++) {
      adjLists.add(new HashSet<>());
    }

    int numEdges = 0;
    int numIntraClusterEdges = 0;
    for (int clusterId = 0; clusterId < numClusters; clusterId++) {
      int sizeOfThisCluster =
          lastIdsInEachCluster[clusterId] - firstIdsInEachCluster[clusterId] + 1;
      double probForThisCluster = getProbabilityInsideCluster(sizeOfThisCluster);
      assert probForThisCluster > 0 && probForThisCluster < 1;
      GeometricDistribution gd = new GeometricDistribution(rng, probForThisCluster);

      int numEdgesInsideThisCluster = 0;
      for (int nodeId = firstIdsInEachCluster[clusterId];
           nodeId <= lastIdsInEachCluster[clusterId]; nodeId++) {
        // Starting from nodeId after current node, repeat
        for (int neighborNodePosition = nodeId + 1;
             neighborNodePosition <= lastIdsInEachCluster[clusterId];) {
          int numNodesToSkip = gd.sample();
          neighborNodePosition += numNodesToSkip;
          if (neighborNodePosition <= lastIdsInEachCluster[clusterId]) {
            assert !adjLists.get(nodeId).contains(neighborNodePosition);
            assert !adjLists.get(neighborNodePosition).contains(nodeId);
            adjLists.get(nodeId).add(neighborNodePosition);
            adjLists.get(neighborNodePosition).add(nodeId);
            neighborNodePosition++;
            numEdges++;
            numEdgesInsideThisCluster++;
            numIntraClusterEdges++;
          }
        }
      }

      float actualProbInsideCluster =
          numEdgesInsideThisCluster * 1.0f / ((sizeOfThisCluster * (sizeOfThisCluster - 1)) / 2);
      assert Math.abs(actualProbInsideCluster - probForThisCluster) < 0.1
          : String.format("cluster %d: size %d, assigned prob. %f, actual prob. %f\n",
            clusterId + 1, sizeOfThisCluster, probForThisCluster, actualProbInsideCluster);
    }

    int numGlobalEdges = 0;
    GeometricDistribution gd = new GeometricDistribution(rng, getGlobalProbability());
    for (int nodeId = 0; nodeId < numNodes; nodeId++) {
      for (int neighborNodePosition = nodeId + 1; neighborNodePosition < numNodes;) {
        int numNodesToSkip = gd.sample();
        neighborNodePosition += numNodesToSkip;
        if (neighborNodePosition < numNodes) {
          if (!adjLists.get(nodeId).contains(neighborNodePosition)) {
            adjLists.get(nodeId).add(neighborNodePosition);
            adjLists.get(neighborNodePosition).add(nodeId);
            neighborNodePosition++;
            numEdges++;
            numGlobalEdges++;
          }
        }
      }
    }

    int[][] nbrs = new int[numNodes][];
    int numEdges2 = 0;
    for (int i = 0; i < numNodes; i++) {
      nbrs[i] = new int[adjLists.get(i).size()];
      int j = 0;
      HashSet<Integer> s = adjLists.get(i);
      for (int neighbor : s) {
        nbrs[i][j] = neighbor;
        j++;
        numEdges2++;
      }
      Arrays.sort(nbrs[i]);
    }

    float[][] wts = null;
    if (this.isWeighted) {
      wts = new float[numNodes][];
      for (int i = 0; i < numNodes; i++) {
        wts[i] = new float[nbrs[i].length];
        for (int j = 0; j < wts[i].length; j++) {
          // Consider only cells in the lower-triangular part of the adjacency matrix, and then
          // replicate the weight to the corresponding cell in the upper-triangular part.
          if (nbrs[i][j] < i) {
            // for inter-cluster edges, set their weight to be low
            float newWt = 0;
            if (vertexToClusterId[i] != vertexToClusterId[nbrs[i][j]]) {
              newWt = (float) (lowerWeightMode - 1.5 * lowerWeightDist.getStandardDeviation());
            } else {
              newWt = (float) sampleEdgeWeight();
            }
            wts[i][j] = newWt;
            int indexOfIInJ = Arrays.binarySearch(nbrs[nbrs[i][j]], i);
            wts[nbrs[i][j]][indexOfIInJ] = newWt;
          }
        }
      }
    }

    System.err.format("Num intra-cluster edges: %d, num global edges: %d, "
            + "fraction global edges: %f\n",
        numIntraClusterEdges, numGlobalEdges, numGlobalEdges * 1.0f / numEdges);

    assert numEdges * 2 == numEdges2
        : String.format("numEdges %d, numEdges2 %d", numEdges, numEdges2);

    return new GraphAndGroundTruth(new Graph(numNodes, numEdges, nbrs, wts), groundTruthClusters);
  }

  /**
   * Main to write the generated graph to disk
   * @param args command-line args
   */
  public static void main(String[] args) {
    if (args.length < 6) {
      throw new IllegalStateException(
          "Usage: command <numClusters> <fractionGlobalEdges> <minProbInsideCluster> "
              + "<maxProbInsideCluster> <minClusterSize> <maxClusterSize> "
              + "(metis format output on stdout)"
      );

    } else {
      RandomAdaptor r = new RandomAdaptor(new JDKRandomGenerator(1));
      GraphGenerator gg = new GraphGenerator(Integer.parseInt(args[0]), Double.parseDouble(args[1]),
          Double.parseDouble(args[2]), Double.parseDouble(args[3]), Integer.parseInt(args[4]),
          Integer.parseInt(args[5]), r, true, 0.2f,
          0.05f, 0.2f
      );
      Graph g = gg.generateWithDisjointClusters().getGraph();
      Iterator<String> graphLines =
          g.iterableStringRepresentation(new DecimalFormat("#.###")).iterator();
      while (graphLines.hasNext()) {
        System.out.println(graphLines.next());
      }
    }
  }
}
