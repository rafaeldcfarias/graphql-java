package graphql.schema.diffing;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import graphql.Internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

@Internal
public class EditorialCostForMapping {
    /**
     * @param mapping     the mapping
     * @param sourceGraph the source graph
     * @param targetGraph the target graph
     *
     * @return the editorial cost
     *
     * @see #baseEditorialCostForMapping(Mapping, SchemaGraph, SchemaGraph, List)
     */
    public static int baseEditorialCostForMapping(Mapping mapping, // can be a partial mapping
                                                  SchemaGraph sourceGraph, // the whole graph
                                                  SchemaGraph targetGraph // the whole graph
    ) {
        return baseEditorialCostForMapping(mapping, sourceGraph, targetGraph, new ArrayList<>());
    }

    /**
     * Gets the "editorial cost for mapping" for the base mapping.
     * <p>
     * Use this is as base cost when invoking
     * {@link #editorialCostForMapping(int, Mapping, SchemaGraph, SchemaGraph)}
     * as it heavily speeds up performance.
     *
     * @param mapping              the mapping
     * @param sourceGraph          the source graph
     * @param targetGraph          the target graph
     * @param editOperationsResult the list of edit operations
     *
     * @return the editorial cost
     */
    public static int baseEditorialCostForMapping(Mapping mapping, // can be a partial mapping
                                                  SchemaGraph sourceGraph, // the whole graph
                                                  SchemaGraph targetGraph, // the whole graph
                                                  List<EditOperation> editOperationsResult) {
        int cost = 0;

        for (int i = 0; i < mapping.size(); i++) {
            Vertex sourceVertex = mapping.getSource(i);
            Vertex targetVertex = mapping.getTarget(i);
            // Vertex changing (relabeling)
            boolean equalNodes = sourceVertex.getType().equals(targetVertex.getType()) && sourceVertex.getProperties().equals(targetVertex.getProperties());
            if (!equalNodes) {
                if (sourceVertex.isIsolated()) {
                    editOperationsResult.add(EditOperation.insertVertex("Insert" + targetVertex, sourceVertex, targetVertex));
                } else if (targetVertex.isIsolated()) {
                    editOperationsResult.add(EditOperation.deleteVertex("Delete " + sourceVertex, sourceVertex, targetVertex));
                } else {
                    editOperationsResult.add(EditOperation.changeVertex("Change " + sourceVertex + " to " + targetVertex, sourceVertex, targetVertex));
                }
                cost++;
            }
        }

        // edge deletion or relabeling
        for (Edge sourceEdge : sourceGraph.getEdges()) {
            // only edges relevant to the subgraph
            if (!mapping.containsSource(sourceEdge.getFrom()) || !mapping.containsSource(sourceEdge.getTo())) {
                continue;
            }
            Vertex targetFrom = mapping.getTarget(sourceEdge.getFrom());
            Vertex targetTo = mapping.getTarget(sourceEdge.getTo());
            Edge targetEdge = targetGraph.getEdge(targetFrom, targetTo);
            if (targetEdge == null) {
                editOperationsResult.add(EditOperation.deleteEdge("Delete edge " + sourceEdge, sourceEdge));
                cost++;
            } else if (!sourceEdge.getLabel().equals(targetEdge.getLabel())) {
                editOperationsResult.add(EditOperation.changeEdge("Change " + sourceEdge + " to " + targetEdge, sourceEdge, targetEdge));
                cost++;
            }
        }

        for (Edge targetEdge : targetGraph.getEdges()) {
            // only subgraph edges
            if (!mapping.containsTarget(targetEdge.getFrom()) || !mapping.containsTarget(targetEdge.getTo())) {
                continue;
            }
            Vertex sourceFrom = mapping.getSource(targetEdge.getFrom());
            Vertex sourceTo = mapping.getSource(targetEdge.getTo());
            if (sourceGraph.getEdge(sourceFrom, sourceTo) == null) {
                editOperationsResult.add(EditOperation.insertEdge("Insert edge " + targetEdge, targetEdge));
                cost++;
            }
        }

        return cost;
    }

    /**
     * Calculates the "editorial cost for mapping" for the non-fixed targets in a {@link Mapping}.
     * <p>
     * The {@code baseCost} argument should be the cost for the fixed mapping from
     * {@link #baseEditorialCostForMapping(Mapping, SchemaGraph, SchemaGraph)}.
     * <p>
     * The sum of the non-fixed costs and the fixed costs is total editorial cost for mapping.
     *
     * @param baseCost    the starting base cost
     * @param mapping     the mapping
     * @param sourceGraph the source graph
     * @param targetGraph the target graph
     *
     * @return the editorial cost
     */
    public static int editorialCostForMapping(int baseCost,
                                              Mapping mapping, // can be a partial mapping
                                              SchemaGraph sourceGraph, // the whole graph
                                              SchemaGraph targetGraph // the whole graph
    ) {
        AtomicInteger cost = new AtomicInteger(baseCost);

        Set<Edge> seenEdges = new LinkedHashSet<>();

        // Tells us whether the edge should be visited. We need to avoid counting edges more than once
        Predicate<Edge> visitEdge = (data) -> {
            if (seenEdges.contains(data)) {
                return false;
            } else {
                seenEdges.add(data);
                return true;
            }
        };

        // Look through
        mapping.forEachNonFixedSourceAndTarget((sourceVertex, targetVertex) -> {
            // Vertex changing (relabeling)
            boolean equalNodes = sourceVertex.getType().equals(targetVertex.getType()) && sourceVertex.getProperties().equals(targetVertex.getProperties());

            if (!equalNodes) {
                cost.getAndIncrement();
            }

            for (Edge sourceEdge : sourceGraph.getAdjacentEdgesAndInverseNonCopy(sourceVertex)) {
                if (!visitEdge.test(sourceEdge)) {
                    continue;
                }

                // only edges relevant to the subgraph

                if (!mapping.containsSource(sourceEdge.getFrom()) || !mapping.containsSource(sourceEdge.getTo())) {
                    continue;
                }

                Vertex targetFrom = mapping.getTarget(sourceEdge.getFrom());
                Vertex targetTo = mapping.getTarget(sourceEdge.getTo());
                Edge targetEdge = targetGraph.getEdge(targetFrom, targetTo);

                if (targetEdge == null) {
                    cost.getAndIncrement();
                } else if (!sourceEdge.getLabel().equals(targetEdge.getLabel())) {
                    cost.getAndIncrement();
                }
            }

            for (Edge targetEdge : targetGraph.getAdjacentEdgesAndInverseNonCopy(targetVertex)) {
                if (!visitEdge.test(targetEdge)) {
                    continue;
                }

                // only edges relevant to the subgraph
                if (!mapping.containsTarget(targetEdge.getFrom()) || !mapping.containsTarget(targetEdge.getTo())) {
                    continue;
                }

                Vertex sourceFrom = mapping.getSource(targetEdge.getFrom());
                Vertex sourceTo = mapping.getSource(targetEdge.getTo());
                Edge sourceEdge = sourceGraph.getEdge(sourceFrom, sourceTo);

                if (sourceEdge == null) {
                    cost.getAndIncrement();
                }
            }
        });

        return cost.get();
    }

    public static Map<Vertex, Integer> edcPerVertexBasedOnWholeMapping(
            Mapping fullMapping,
            Mapping partialBaseMapping,
            SchemaGraph sourceGraph, // the whole graph
            SchemaGraph targetGraph // the whole graph
    ) {


        Map<Vertex, Integer> result = new LinkedHashMap<>();
        for (Vertex v : sourceGraph.getVertices()) {

            if (partialBaseMapping.containsSource(v)) {
                continue;
            }
            Vertex u = fullMapping.getTarget(v);
            boolean equalNodes = v.getType().equals(u.getType()) && v.getProperties().equals(u.getProperties());
            result.put(v, equalNodes ? 0 : 1);

            Set<Edge> seenTargetEdges = new LinkedHashSet<>();
            for (Edge edge : sourceGraph.getAdjacentEdgesNonCopy(v)) {
                Vertex to = edge.getTo();
                Edge targetEdge = targetGraph.getEdge(u, fullMapping.getTarget(to));
                if (targetEdge == null) {
                    result.put(v, result.get(v) + 1);
                } else {
                    if (!edge.getLabel().equals(targetEdge.getLabel())) {
                        result.put(v, result.get(v) + 1);
                    }
                    seenTargetEdges.add(targetEdge);
                }
            }

            for (Edge edge : sourceGraph.getAdjacentEdgesInverseNonCopy(v)) {
                Vertex from = edge.getFrom();
                // we ignore inner inverse edges
                if (!partialBaseMapping.containsSource(from)) {
                    continue;
                }
                Edge targetEdge = targetGraph.getEdge(fullMapping.getTarget(from), u);
                if (targetEdge == null) {
                    result.put(v, result.get(v) + 1);
                } else {
                    if (!edge.getLabel().equals(targetEdge.getLabel())) {
                        result.put(v, result.get(v) + 1);
                    }
                    seenTargetEdges.add(targetEdge);
                }
            }
            for (Edge edge : targetGraph.getAdjacentEdgesNonCopy(u)) {
                if (!seenTargetEdges.contains(edge)) {
                    result.put(v, result.get(v) + 1);
                    continue;
                }
            }
            for (Edge edge : targetGraph.getAdjacentEdgesInverseNonCopy(u)) {
                // we ignore inner inverse edges
                if (!partialBaseMapping.containsTarget(edge.getFrom())) {
                    continue;
                }
                if (!seenTargetEdges.contains(edge)) {
                    result.put(v, result.get(v) + 1);
                }
            }
        }

        return result;
    }

    public static int realInnerEdgeMatches(Vertex v, Vertex u, Mapping partialMapping, Mapping fullMapping, SchemaGraph sourceGraph, SchemaGraph targetGraph) {
        int match = 0;
        for (Edge edge : sourceGraph.getAdjacentEdgesNonCopy(v)) {
            if (partialMapping.containsSource(edge.getTo())) {
                continue;
            }
            Vertex to = edge.getTo();
            Edge targetEdge = targetGraph.getEdge(u, fullMapping.getTarget(to));
            if (targetEdge == null) {
                continue;
            }
            if (edge.getLabel().equals(targetEdge.getLabel())) {
                match++;
            }
        }
        return match;
    }

    public static int calcMinimumInnerEdgeCost(Vertex v, Vertex u, Mapping partialMapping, SchemaGraph sourceGraph, SchemaGraph targetGraph) {
        Multiset<String> multisetInnerEdgeLabelsV = HashMultiset.create();
        Multiset<String> multisetInnerEdgeLabelsU = HashMultiset.create();
        for (Edge edge : sourceGraph.getAdjacentEdgesNonCopy(v)) {
            if (!partialMapping.containsSource(edge.getTo())) {
                multisetInnerEdgeLabelsV.add(edge.getLabel());
            }
        }
        for (Edge edge : targetGraph.getAdjacentEdgesNonCopy(u)) {
            if (!partialMapping.containsTarget(edge.getTo())) {
                multisetInnerEdgeLabelsU.add(edge.getLabel());
            }
        }
        Multiset<String> intersection = Multisets.intersection(multisetInnerEdgeLabelsV, multisetInnerEdgeLabelsU);
        int multiSetEditDistance = Math.max(multisetInnerEdgeLabelsV.size(), multisetInnerEdgeLabelsU.size()) - intersection.size();
        return multiSetEditDistance;
    }

    public static int realInnerEdgeCosts(Vertex v, Vertex u, Mapping partialMapping, Mapping fullMapping, SchemaGraph sourceGraph, SchemaGraph targetGraph) {

        // we only want inner and direct (not inverse) edges
        int result = 0;
        Set<Edge> seenTargetEdges = new LinkedHashSet<>();
        for (Edge edge : sourceGraph.getAdjacentEdgesNonCopy(v)) {
            if (partialMapping.containsSource(edge.getTo())) {
                continue;
            }
            Vertex to = edge.getTo();
            Edge targetEdge = targetGraph.getEdge(u, fullMapping.getTarget(to));
            if (targetEdge == null) {
                result++;
            } else {
                seenTargetEdges.add(targetEdge);
                if (!edge.getLabel().equals(targetEdge.getLabel())) {
                    result++;
                }
            }
        }
        for (Edge edge : targetGraph.getAdjacentEdgesNonCopy(u)) {
            if (partialMapping.containsTarget(edge.getTo())) {
                continue;
            }
            if (seenTargetEdges.contains(edge)) {
                continue;
            }
            result++;
        }
        return result;
    }

    public static int anchoredCost(Vertex v, Vertex u, Mapping partialMapping, SchemaGraph completeSourceGraph, SchemaGraph completeTargetGraph) {
        boolean equalNodes = v.getType().equals(u.getType()) && v.getProperties().equals(u.getProperties());
        int anchoredVerticesCost = 0;
        Set<Edge> matchedTargetEdges = new LinkedHashSet<>();
        Set<Edge> matchedTargetEdgesInverse = new LinkedHashSet<>();

        for (Edge edgeV : v.getAdjacentEdges()) {

            Vertex targetTo = partialMapping.getTarget(edgeV.getTo());
            if (targetTo == null) {
                continue;
            }
            Edge matchedTargetEdge = completeTargetGraph.getEdge(u, targetTo);
            if (matchedTargetEdge != null) {
                matchedTargetEdges.add(matchedTargetEdge);
                if (!Objects.equals(edgeV.getLabel(), matchedTargetEdge.getLabel())) {
                    anchoredVerticesCost++;
                }
            } else {
                anchoredVerticesCost++;
            }

        }

        for (Edge edgeV : v.getAdjacentEdgesInverse()) {
            Vertex targetFrom = partialMapping.getTarget(edgeV.getFrom());
            if (targetFrom == null) {
                continue;
            }
            Edge matachedTargetEdge = completeTargetGraph.getEdge(targetFrom, u);
            if (matachedTargetEdge != null) {
                matchedTargetEdgesInverse.add(matachedTargetEdge);
                if (!Objects.equals(edgeV.getLabel(), matachedTargetEdge.getLabel())) {
                    anchoredVerticesCost++;
                }
            } else {
                anchoredVerticesCost++;
            }
        }

        for (Edge edgeU : u.getAdjacentEdges()) {
            if (!partialMapping.containsTarget(edgeU.getTo()) || matchedTargetEdges.contains(edgeU)) {
                continue;
            }
            anchoredVerticesCost++;

        }
        for (Edge edgeU : u.getAdjacentEdgesInverse()) {
            if (!partialMapping.containsTarget(edgeU.getFrom()) || matchedTargetEdgesInverse.contains(edgeU)) {
                continue;
            }
            anchoredVerticesCost++;
        }

        return (equalNodes ? 0 : 1) + anchoredVerticesCost;
    }

    public static int lbcInnerEdgeCost(Vertex v, Vertex u, Mapping partialMapping, SchemaGraph sourceGraph, SchemaGraph targetGraph) {
        Multiset<String> multisetInnerEdgeLabelsV = HashMultiset.create();
        Multiset<String> multisetInnerEdgeLabelsU = HashMultiset.create();
        for (Edge edgeV : v.getAdjacentEdges()) {

            if (!partialMapping.containsSource(edgeV.getTo())) {
                multisetInnerEdgeLabelsV.add(edgeV.getLabel());
            }

        }
        for (Edge edgeU : u.getAdjacentEdges()) {
            // test if this is an inner edge (meaning it not part of the subgraph induced by the partial mapping)
            if (!partialMapping.containsTarget(edgeU.getTo())) {
                multisetInnerEdgeLabelsU.add(edgeU.getLabel());
            }
        }
        Multiset<String> intersection = HashMultiset.create(Multisets.intersection(multisetInnerEdgeLabelsV, multisetInnerEdgeLabelsU));
        return Math.max(multisetInnerEdgeLabelsV.size(), multisetInnerEdgeLabelsU.size()) - intersection.size();
    }
}
