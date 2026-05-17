package com.self.help;

import com.self.help.input.MappingSpec;
import com.self.help.legacy.RawDataStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GraphQueryService {
    private final RawDataStore dataStore;
    private final int fromNodeIdColumnIndex;
    private final int fromNodeLabelColumnIndex;
    private final int toNodeIdColumnIndex;
    private final int toNodeLabelColumnIndex;
    private final int relationColumnIndex;

    public GraphQueryService(RawDataStore dataStore, MappingSpec mappingSpec) {
        this.dataStore = dataStore;
        this.fromNodeIdColumnIndex = dataStore.getColumnIndex(mappingSpec.getFromNodeSpec().getIdColumnName());
        this.fromNodeLabelColumnIndex = dataStore.getColumnIndex(mappingSpec.getFromNodeSpec().getLabelColumnName());
        this.toNodeIdColumnIndex = dataStore.getColumnIndex(mappingSpec.getToNodeSpec().getIdColumnName());
        this.toNodeLabelColumnIndex = dataStore.getColumnIndex(mappingSpec.getToNodeSpec().getLabelColumnName());
        this.relationColumnIndex = mappingSpec.getRelationColumnNames().isEmpty()
                ? -1
                : dataStore.getColumnIndex(mappingSpec.getRelationColumnNames().getFirst());
    }

    public Map<String, String> getVertices() {
        Map<String, String> vertices = new LinkedHashMap<>();

        for (int rowId = 0; rowId < dataStore.getSize(); rowId++) {
            addVertex(vertices, rowId, fromNodeIdColumnIndex, fromNodeLabelColumnIndex);
            addVertex(vertices, rowId, toNodeIdColumnIndex, toNodeLabelColumnIndex);
        }

        return new LinkedHashMap<>(vertices);
    }

    public List<String> getEdges() {
        List<String> edges = new ArrayList<>();

        for (int rowId = 0; rowId < dataStore.getSize(); rowId++) {
            String fromNodeId = dataStore.getString(rowId, fromNodeIdColumnIndex);
            String toNodeId = dataStore.getString(rowId, toNodeIdColumnIndex);
            edges.add(formatEdge(rowId, fromNodeId, toNodeId));
        }

        return List.copyOf(edges);
    }

    private String formatEdge(int rowId, String fromNodeId, String toNodeId) {
        if (relationColumnIndex == -1) {
            return fromNodeId + " -> " + toNodeId;
        }

        String relation = dataStore.getString(rowId, relationColumnIndex);
        return fromNodeId + " -[" + relation + "]-> " + toNodeId;
    }

    private void addVertex(Map<String, String> vertices, int rowId, int idColumnIndex, int labelColumnIndex) {
        String nodeId = dataStore.getString(rowId, idColumnIndex);
        String nodeLabel = dataStore.getString(rowId, labelColumnIndex);
        vertices.putIfAbsent(nodeId, nodeLabel);
    }
}
