package com.self.help.context;

import com.self.help.input.MappingSpec;
import com.self.help.input.NodeSpec;
import com.self.help.legacy.IntegerColumnarStore;
import com.self.help.legacy.RawDataStore;
import com.self.help.storage.BiDirectionalDictionary;
import com.self.help.storage.InvertedIndexColumn;
import lombok.AccessLevel;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Owns the per-column contexts used by the graph ingestion engine.
 * The context groups node id, node label, node attributes, and relation columns,
 * then exposes flattened views of their dictionaries, inverted indexes, integer
 * column stores, and source-column mappings in the same order expected by
 * engine-level arrays.
 */
@Getter(onMethod_ = {@NotNull})
public class GraphEngineContext {
    /**
     * Raw source store used to resolve source column names to data-cube indexes.
     */
    @NotNull
    @Getter(AccessLevel.PRIVATE)
    private final RawDataStore dataCube;

    /**
     * Mapping specification used to size flattened context arrays.
     */
    @NotNull
    @Getter(AccessLevel.PRIVATE)
    private final MappingSpec spec;

    /**
     * Shared context for from-node and to-node id columns.
     */
    private final NodeContext idContext;

    /**
     * Shared context for from-node and to-node label columns.
     */
    private final NodeContext labelContext;

    /**
     * Contexts for paired from-node and to-node attribute columns, ordered by
     * their position in the mapping specification.
     */
    private final NodeContext[] attributesContext;

    /**
     * Contexts for relation columns, ordered by their position in the mapping
     * specification.
     */
    private final RelationContext[] relationContext;

    /**
     * Builds all node and relation contexts for the supplied graph mapping.
     * Node id and label columns are represented as paired from/to contexts,
     * attributes are paired by list position, and each relation column receives
     * its own relation context.
     *
     * @param dataCube raw source store that owns the mapped columns
     * @param spec graph mapping that identifies node and relation columns
     */
    public GraphEngineContext(@NotNull RawDataStore dataCube, @NotNull MappingSpec spec) {
        this.dataCube = dataCube;
        this.spec = spec;
        NodeSpec fromNodeSpec = spec.getFromNodeSpec();
        NodeSpec toNodeSpec = spec.getToNodeSpec();

        this.idContext = new NodeContext(dataCube, fromNodeSpec.getIdColumnName(), toNodeSpec.getIdColumnName());
        this.labelContext = new NodeContext(dataCube, fromNodeSpec.getLabelColumnName(), toNodeSpec.getLabelColumnName());

        List<String> fromAttributes = fromNodeSpec.getNodeAttributeNames();
        List<String> toAttributes = toNodeSpec.getNodeAttributeNames();
        this.attributesContext = new NodeContext[fromAttributes.size()];
        for (int index = 0; index < fromAttributes.size(); index++) {
            this.attributesContext[index] = new NodeContext(dataCube, fromAttributes.get(index), toAttributes.get(index));
        }

        List<String> relationNames = spec.getRelationColumnNames();
        this.relationContext = new RelationContext[relationNames.size()];
        for (int index = 0; index < relationNames.size(); index++) {
            this.relationContext[index] = new RelationContext(dataCube, relationNames.get(index));
        }
    }

    /**
     * Flattens all dictionary references into the engine array order:
     * from id, from label, from attributes, to id, to label, to attributes, and
     * relation dictionaries.
     *
     * @return dictionary references in engine column order
     */
    public BiDirectionalDictionary[] flatMapBiDirectionalDictionaries() {
        final BiDirectionalDictionary[] biDirectionalDictionaries = new BiDirectionalDictionary[spec.getNumberOfTotalColumns()];

        int index = 0;
        index = copyNodeDictionaries(biDirectionalDictionaries, index);
        index = copyNodeDictionaries(biDirectionalDictionaries, index);
        copyRelationDictionaries(biDirectionalDictionaries, index);
        return biDirectionalDictionaries;
    }

    /**
     * Copies one node-side group of dictionary references into the supplied
     * output array.
     *
     * @param biDirectionalDictionaries output dictionary array
     * @param index first position to write
     * @return next writable position after the copied node dictionaries
     */
    private int copyNodeDictionaries(BiDirectionalDictionary[] biDirectionalDictionaries, int index) {
        biDirectionalDictionaries[index++] = idContext.getBiDirectionalDictionary();
        biDirectionalDictionaries[index++] = labelContext.getBiDirectionalDictionary();
        for (NodeContext attributeContext : attributesContext) {
            biDirectionalDictionaries[index++] = attributeContext.getBiDirectionalDictionary();
        }
        return index;
    }

    /**
     * Copies relation dictionary references into the supplied output array.
     *
     * @param biDirectionalDictionaries output dictionary array
     * @param index first position to write
     */
    private void copyRelationDictionaries(BiDirectionalDictionary[] biDirectionalDictionaries, int index) {
        for (RelationContext relationContext : relationContext) {
            biDirectionalDictionaries[index++] = relationContext.getBiDirectionalDictionary();
        }
    }

    /**
     * Flattens all inverted-index references into the engine array order:
     * from id, from label, from attributes, to id, to label, to attributes, and
     * relation indexes.
     *
     * @return inverted-index references in engine column order
     */
    public InvertedIndexColumn[] flatMapInvertedIndexColumns() {
        final InvertedIndexColumn[] invertedIndexColumns = new InvertedIndexColumn[spec.getNumberOfTotalColumns()];

        int index = 0;
        index = copyFromNodeInvertedIndexColumns(invertedIndexColumns, index);
        index = copyToNodeInvertedIndexColumns(invertedIndexColumns, index);
        copyRelationInvertedIndexColumns(invertedIndexColumns, index);
        return invertedIndexColumns;
    }

    /**
     * Copies from-node inverted-index references into the supplied output array.
     *
     * @param invertedIndexColumns output inverted-index array
     * @param index first position to write
     * @return next writable position after the copied from-node indexes
     */
    private int copyFromNodeInvertedIndexColumns(InvertedIndexColumn[] invertedIndexColumns, int index) {
        invertedIndexColumns[index++] = idContext.getFromInvertedIndexColumn();
        invertedIndexColumns[index++] = labelContext.getFromInvertedIndexColumn();
        for (NodeContext attributeContext : attributesContext) {
            invertedIndexColumns[index++] = attributeContext.getFromInvertedIndexColumn();
        }
        return index;
    }

    /**
     * Copies to-node inverted-index references into the supplied output array.
     *
     * @param invertedIndexColumns output inverted-index array
     * @param index first position to write
     * @return next writable position after the copied to-node indexes
     */
    private int copyToNodeInvertedIndexColumns(InvertedIndexColumn[] invertedIndexColumns, int index) {
        invertedIndexColumns[index++] = idContext.getToInvertedIndexColumn();
        invertedIndexColumns[index++] = labelContext.getToInvertedIndexColumn();
        for (NodeContext attributeContext : attributesContext) {
            invertedIndexColumns[index++] = attributeContext.getToInvertedIndexColumn();
        }
        return index;
    }

    /**
     * Copies relation inverted-index references into the supplied output array.
     *
     * @param invertedIndexColumns output inverted-index array
     * @param index first position to write
     */
    private void copyRelationInvertedIndexColumns(InvertedIndexColumn[] invertedIndexColumns, int index) {
        for (RelationContext relationContext : relationContext) {
            invertedIndexColumns[index++] = relationContext.getInvertedIndexColumn();
        }
    }

    /**
     * Builds a map from flattened graph target index to raw data-cube column
     * index. The target order is from id, from label, from attributes, to id,
     * to label, to attributes, then relations.
     *
     * @return data-cube column index for each flattened graph target index
     */
    public int[] flatMapTargetIndexToDataCubeIndex() {
        int[] array = new int[spec.getNumberOfTotalColumns()];
        int targetIndex = 0;
        targetIndex = copyFromNodeTargetIndexes(array, targetIndex);
        targetIndex = copyToNodeTargetIndexes(array, targetIndex);
        copyRelationTargetIndexes(array, targetIndex);
        return array;
    }

    /**
     * Copies one node-side group of source data-cube indexes into the supplied
     * target-index mapping.
     *
     * @param targetIndexToDataCubeIndex output array indexed by flattened target index
     * @param targetIndex first target position to write
     * @return next writable target position after the copied from-node indexes
     */
    private int copyFromNodeTargetIndexes(int[] targetIndexToDataCubeIndex, int targetIndex) {
        targetIndexToDataCubeIndex[targetIndex++] = idContext.getFromDataCubeIndex();
        targetIndexToDataCubeIndex[targetIndex++] = labelContext.getFromDataCubeIndex();
        for (NodeContext attributeContext : attributesContext) {
            targetIndexToDataCubeIndex[targetIndex++] = attributeContext.getFromDataCubeIndex();
        }
        return targetIndex;
    }

    /**
     * Copies one to-node group of source data-cube indexes into the supplied
     * target-index mapping.
     *
     * @param targetIndexToDataCubeIndex output array indexed by flattened target index
     * @param targetIndex first target position to write
     * @return next writable target position after the copied to-node indexes
     */
    private int copyToNodeTargetIndexes(int[] targetIndexToDataCubeIndex, int targetIndex) {
        targetIndexToDataCubeIndex[targetIndex++] = idContext.getToDataCubeIndex();
        targetIndexToDataCubeIndex[targetIndex++] = labelContext.getToDataCubeIndex();
        for (NodeContext attributeContext : attributesContext) {
            targetIndexToDataCubeIndex[targetIndex++] = attributeContext.getToDataCubeIndex();
        }
        return targetIndex;
    }

    /**
     * Copies relation source data-cube indexes into the supplied target-index
     * mapping.
     *
     * @param targetIndexToDataCubeIndex output array indexed by flattened target index
     * @param targetIndex first target position to write
     */
    private void copyRelationTargetIndexes(int[] targetIndexToDataCubeIndex, int targetIndex) {
        for (RelationContext relationContext : relationContext) {
            targetIndexToDataCubeIndex[targetIndex++] = relationContext.getIndexFromDataCube();
        }
    }

    /**
     * Flattens all integer columnar-store references into the engine array
     * order: from id, from label, from attributes, to id, to label,
     * to attributes, and relation stores.
     *
     * @return integer columnar-store references in engine column order
     */
    public IntegerColumnarStore[] flatMapIntegerColumnarStores() {
        final IntegerColumnarStore[] columnarStores = new IntegerColumnarStore[spec.getNumberOfTotalColumns()];

        int index = 0;
        index = copyFromNodeIntegerColumnarStores(columnarStores, index);
        index = copyToNodeIntegerColumnarStores(columnarStores, index);
        copyRelationIntegerColumnarStores(columnarStores, index);
        return columnarStores;
    }

    /**
     * Copies from-node integer column stores into the supplied output array.
     *
     * @param columnarStores output integer columnar-store array
     * @param index first position to write
     * @return next writable position after the copied from-node stores
     */
    private int copyFromNodeIntegerColumnarStores(IntegerColumnarStore[] columnarStores, int index) {
        columnarStores[index++] = idContext.getFromIntegerColumnarStore();
        columnarStores[index++] = labelContext.getFromIntegerColumnarStore();
        for (NodeContext attributeContext : attributesContext) {
            columnarStores[index++] = attributeContext.getFromIntegerColumnarStore();
        }
        return index;
    }

    /**
     * Copies to-node integer column stores into the supplied output array.
     *
     * @param columnarStores output integer columnar-store array
     * @param index first position to write
     * @return next writable position after the copied to-node stores
     */
    private int copyToNodeIntegerColumnarStores(IntegerColumnarStore[] columnarStores, int index) {
        columnarStores[index++] = idContext.getToIntegerColumnarStore();
        columnarStores[index++] = labelContext.getToIntegerColumnarStore();
        for (NodeContext attributeContext : attributesContext) {
            columnarStores[index++] = attributeContext.getToIntegerColumnarStore();
        }
        return index;
    }

    /**
     * Copies relation integer column stores into the supplied output array.
     *
     * @param columnarStores output integer columnar-store array
     * @param index first position to write
     */
    private void copyRelationIntegerColumnarStores(IntegerColumnarStore[] columnarStores, int index) {
        for (RelationContext relationContext : relationContext) {
            columnarStores[index++] = relationContext.getRelationIntegerColumnarStore();
        }
    }
}
