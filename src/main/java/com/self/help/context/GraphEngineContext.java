package com.self.help.context;

import com.self.help.input.GraphMappingSpec;
import com.self.help.input.NodePropertyMappingSpec;
import com.self.help.input.RelationPropertyMappingSpec;
import com.self.help.legacy.IntegerColumnarStore;
import com.self.help.legacy.RawDataStore;
import com.self.help.storage.BiDirectionalDictionary;
import com.self.help.storage.InvertedIndexColumn;
import lombok.AccessLevel;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.roaringbitmap.RoaringBitmap;

import java.util.List;

/**
 * Owns the per-column contexts used by the graph ingestion engine.
 * The context keeps the logical graph mapping readable by grouping id, label,
 * attribute, relation, and tombstone bitmap state under explicit fields.
 */
@Getter(onMethod_ = {@NotNull})
public class GraphEngineContext {

    /**
     * Raw source store used to resolve source column names to data-cube indexes.
     */
    @Getter(AccessLevel.PRIVATE)
    private final RawDataStore dataCube;

    /**
     * Context for the mapped node id pair.
     */
    private final NodePropertyPairContext idContext;

    /**
     * Context for the mapped node label pair. When no explicit label pair is
     * configured, this maps the id pair so labels fall back to ids.
     */
    private final NodePropertyPairContext labelContext;

    /**
     * Contexts for mapped node attributes, in mapping-spec order.
     */
    private final NodePropertyPairContext[] attributesContext;

    /**
     * Contexts for mapped relation columns, in mapping-spec order.
     */
    private final RelationPropertyContext[] relations;

    /**
     * Bitmap tracking ingested row indices where the FROM-node id was null.
     */
    private final RoaringBitmap fromDeleted = new RoaringBitmap();

    /**
     * Bitmap tracking ingested row indices where the TO-node id was null.
     */
    private final RoaringBitmap toDeleted = new RoaringBitmap();

    /**
     * Zero-based source column index for the FROM-node id.
     */
    @Getter
    private final int fromIdColIndex;

    /**
     * Zero-based source column index for the TO-node id.
     */
    @Getter
    private final int toIdColIndex;

    /**
     * Target-column mask used when the FROM-node id is null.
     */
    private final RoaringBitmap fromNullReadMask = new RoaringBitmap();

    /**
     * Target-column mask used when the TO-node id is null.
     */
    private final RoaringBitmap toNullReadMask = new RoaringBitmap();

    public GraphEngineContext(@NotNull RawDataStore dataCube, @NotNull GraphMappingSpec schema) {
        this.dataCube = dataCube;

        var idPair = schema.idPair();
        var labelPair = schema.labelPair() != null ? schema.labelPair() : idPair;

        this.idContext = new NodePropertyPairContext(dataCube, idPair.fromColumnName(), idPair.toColumnName());
        this.labelContext = new NodePropertyPairContext(dataCube, labelPair.fromColumnName(), labelPair.toColumnName());

        this.fromIdColIndex = idContext.getFromDataCubeIndex();
        this.toIdColIndex = idContext.getToDataCubeIndex();

        List<NodePropertyMappingSpec> nodeAttributes = schema.nodeAttributes();
        this.attributesContext = new NodePropertyPairContext[nodeAttributes.size()];
        for (int index = 0; index < nodeAttributes.size(); index++) {
            NodePropertyMappingSpec attribute = nodeAttributes.get(index);
            this.attributesContext[index] = new NodePropertyPairContext(
                    dataCube,
                    attribute.fromColumnName(),
                    attribute.toColumnName());
        }

        List<RelationPropertyMappingSpec> relationSpecs = schema.relations();
        this.relations = new RelationPropertyContext[relationSpecs.size()];
        for (int index = 0; index < relationSpecs.size(); index++) {
            this.relations[index] = new RelationPropertyContext(dataCube, relationSpecs.get(index).columnName());
        }

        int nodeColumnCount = nodeColumnCount();
        fromNullReadMask.add((long) nodeColumnCount, (long) nodeColumnCount * 2);
        toNullReadMask.add(0L, (long) nodeColumnCount);
    }

    /**
     * Flattens all dictionary references into engine array order:
     * from-side columns, to-side columns, then relation columns.
     *
     * @return dictionary references in engine column order
     */
    @NotNull
    public BiDirectionalDictionary[] flatMapBiDirectionalDictionaries() {
        BiDirectionalDictionary[] result = new BiDirectionalDictionary[totalColumnCount()];
        int index = 0;

        index = copyNodeDictionaries(result, index);
        index = copyNodeDictionaries(result, index);
        copyRelationDictionaries(result, index);

        return result;
    }

    private int copyNodeDictionaries(BiDirectionalDictionary[] result, int index) {
        result[index++] = idContext.getBiDirectionalDictionary();
        result[index++] = labelContext.getBiDirectionalDictionary();
        for (NodePropertyPairContext attributeContext : attributesContext) {
            result[index++] = attributeContext.getBiDirectionalDictionary();
        }
        return index;
    }

    private void copyRelationDictionaries(BiDirectionalDictionary[] result, int index) {
        for (RelationPropertyContext relation : relations) {
            result[index++] = relation.getBiDirectionalDictionary();
        }
    }

    /**
     * Flattens all inverted-index references into engine array order:
     * from-side columns, to-side columns, then relation columns.
     *
     * @return inverted-index references in engine column order
     */
    @NotNull
    public InvertedIndexColumn[] flatMapInvertedIndexColumns() {
        InvertedIndexColumn[] result = new InvertedIndexColumn[totalColumnCount()];
        int index = 0;

        index = copyFromNodeInvertedIndexes(result, index);
        index = copyToNodeInvertedIndexes(result, index);
        copyRelationInvertedIndexes(result, index);

        return result;
    }

    private int copyFromNodeInvertedIndexes(InvertedIndexColumn[] result, int index) {
        result[index++] = idContext.getFromInvertedIndexColumn();
        result[index++] = labelContext.getFromInvertedIndexColumn();
        for (NodePropertyPairContext attributeContext : attributesContext) {
            result[index++] = attributeContext.getFromInvertedIndexColumn();
        }
        return index;
    }

    private int copyToNodeInvertedIndexes(InvertedIndexColumn[] result, int index) {
        result[index++] = idContext.getToInvertedIndexColumn();
        result[index++] = labelContext.getToInvertedIndexColumn();
        for (NodePropertyPairContext attributeContext : attributesContext) {
            result[index++] = attributeContext.getToInvertedIndexColumn();
        }
        return index;
    }

    private void copyRelationInvertedIndexes(InvertedIndexColumn[] result, int index) {
        for (RelationPropertyContext relation : relations) {
            result[index++] = relation.getInvertedIndexColumn();
        }
    }

    /**
     * Builds a map from flattened target index to raw data-cube column index.
     * Order: from-side columns, to-side columns, then relation columns.
     *
     * @return data-cube column index for each flattened graph target index
     */
    @NotNull
    public int[] flatMapTargetIndexToDataCubeIndex() {
        int[] result = new int[totalColumnCount()];
        int index = 0;

        index = copyFromNodeDataCubeIndexes(result, index);
        index = copyToNodeDataCubeIndexes(result, index);
        copyRelationDataCubeIndexes(result, index);

        return result;
    }

    private int copyFromNodeDataCubeIndexes(int[] result, int index) {
        result[index++] = idContext.getFromDataCubeIndex();
        result[index++] = labelContext.getFromDataCubeIndex();
        for (NodePropertyPairContext attributeContext : attributesContext) {
            result[index++] = attributeContext.getFromDataCubeIndex();
        }
        return index;
    }

    private int copyToNodeDataCubeIndexes(int[] result, int index) {
        result[index++] = idContext.getToDataCubeIndex();
        result[index++] = labelContext.getToDataCubeIndex();
        for (NodePropertyPairContext attributeContext : attributesContext) {
            result[index++] = attributeContext.getToDataCubeIndex();
        }
        return index;
    }

    private void copyRelationDataCubeIndexes(int[] result, int index) {
        for (RelationPropertyContext relation : relations) {
            result[index++] = relation.getIndexFromDataCube();
        }
    }

    /**
     * Flattens all integer columnar-store references into engine array order:
     * from-side columns, to-side columns, then relation columns.
     *
     * @return integer columnar-store references in engine column order
     */
    @NotNull
    public IntegerColumnarStore[] flatMapIntegerColumnarStores() {
        IntegerColumnarStore[] result = new IntegerColumnarStore[totalColumnCount()];
        int index = 0;

        index = copyFromNodeStores(result, index);
        index = copyToNodeStores(result, index);
        copyRelationStores(result, index);

        return result;
    }

    private int copyFromNodeStores(IntegerColumnarStore[] result, int index) {
        result[index++] = idContext.getFromIntegerColumnarStore();
        result[index++] = labelContext.getFromIntegerColumnarStore();
        for (NodePropertyPairContext attributeContext : attributesContext) {
            result[index++] = attributeContext.getFromIntegerColumnarStore();
        }
        return index;
    }

    private int copyToNodeStores(IntegerColumnarStore[] result, int index) {
        result[index++] = idContext.getToIntegerColumnarStore();
        result[index++] = labelContext.getToIntegerColumnarStore();
        for (NodePropertyPairContext attributeContext : attributesContext) {
            result[index++] = attributeContext.getToIntegerColumnarStore();
        }
        return index;
    }

    private void copyRelationStores(IntegerColumnarStore[] result, int index) {
        for (RelationPropertyContext relation : relations) {
            result[index++] = relation.getRelationIntegerColumnarStore();
        }
    }

    private int nodeColumnCount() {
        return 2 + attributesContext.length;
    }

    private int totalColumnCount() {
        return (nodeColumnCount() * 2) + relations.length;
    }
}
