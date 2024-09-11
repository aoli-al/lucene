/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.backward_codecs.lucene92;

import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.hnsw.DefaultFlatVectorScorer;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.KnnCollector;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.hnsw.HnswGraph;
import org.apache.lucene.util.hnsw.HnswGraphSearcher;
import org.apache.lucene.util.hnsw.OrdinalTranslatedKnnCollector;
import org.apache.lucene.util.hnsw.RandomVectorScorer;
import org.apache.lucene.util.packed.DirectMonotonicReader;

/**
 * Reads vectors from the index segments along with index data structures supporting KNN search.
 *
 * @lucene.experimental
 */
public final class Lucene92HnswVectorsReader extends KnnVectorsReader {

  private final Map<String, FieldEntry> fields = new HashMap<>();
  private final IndexInput vectorData;
  private final IndexInput vectorIndex;
  private final DefaultFlatVectorScorer defaultFlatVectorScorer = new DefaultFlatVectorScorer();

  Lucene92HnswVectorsReader(SegmentReadState state) throws IOException {
    int versionMeta = readMetadata(state);
    boolean success = false;
    try {
      vectorData =
          openDataInput(
              state,
              versionMeta,
              Lucene92HnswVectorsFormat.VECTOR_DATA_EXTENSION,
              Lucene92HnswVectorsFormat.VECTOR_DATA_CODEC_NAME);
      vectorIndex =
          openDataInput(
              state,
              versionMeta,
              Lucene92HnswVectorsFormat.VECTOR_INDEX_EXTENSION,
              Lucene92HnswVectorsFormat.VECTOR_INDEX_CODEC_NAME);
      success = true;
    } finally {
      if (success == false) {
        IOUtils.closeWhileHandlingException(this);
      }
    }
  }

  private int readMetadata(SegmentReadState state) throws IOException {
    String metaFileName =
        IndexFileNames.segmentFileName(
            state.segmentInfo.name, state.segmentSuffix, Lucene92HnswVectorsFormat.META_EXTENSION);
    int versionMeta = -1;
    try (ChecksumIndexInput meta = state.directory.openChecksumInput(metaFileName)) {
      Throwable priorE = null;
      try {
        versionMeta =
            CodecUtil.checkIndexHeader(
                meta,
                Lucene92HnswVectorsFormat.META_CODEC_NAME,
                Lucene92HnswVectorsFormat.VERSION_START,
                Lucene92HnswVectorsFormat.VERSION_CURRENT,
                state.segmentInfo.getId(),
                state.segmentSuffix);
        readFields(meta, state.fieldInfos);
      } catch (Throwable exception) {
        priorE = exception;
      } finally {
        CodecUtil.checkFooter(meta, priorE);
      }
    }
    return versionMeta;
  }

  private static IndexInput openDataInput(
      SegmentReadState state, int versionMeta, String fileExtension, String codecName)
      throws IOException {
    String fileName =
        IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, fileExtension);
    IndexInput in = state.directory.openInput(fileName, state.context);
    boolean success = false;
    try {
      int versionVectorData =
          CodecUtil.checkIndexHeader(
              in,
              codecName,
              Lucene92HnswVectorsFormat.VERSION_START,
              Lucene92HnswVectorsFormat.VERSION_CURRENT,
              state.segmentInfo.getId(),
              state.segmentSuffix);
      if (versionMeta != versionVectorData) {
        throw new CorruptIndexException(
            "Format versions mismatch: meta="
                + versionMeta
                + ", "
                + codecName
                + "="
                + versionVectorData,
            in);
      }
      CodecUtil.retrieveChecksum(in);
      success = true;
      return in;
    } finally {
      if (success == false) {
        IOUtils.closeWhileHandlingException(in);
      }
    }
  }

  private void readFields(ChecksumIndexInput meta, FieldInfos infos) throws IOException {
    for (int fieldNumber = meta.readInt(); fieldNumber != -1; fieldNumber = meta.readInt()) {
      FieldInfo info = infos.fieldInfo(fieldNumber);
      if (info == null) {
        throw new CorruptIndexException("Invalid field number: " + fieldNumber, meta);
      }
      FieldEntry fieldEntry = readField(meta, info);
      validateFieldEntry(info, fieldEntry);
      fields.put(info.name, fieldEntry);
    }
  }

  private void validateFieldEntry(FieldInfo info, FieldEntry fieldEntry) {
    int dimension = info.getVectorDimension();
    if (dimension != fieldEntry.dimension) {
      throw new IllegalStateException(
          "Inconsistent vector dimension for field=\""
              + info.name
              + "\"; "
              + dimension
              + " != "
              + fieldEntry.dimension);
    }

    long numBytes = (long) fieldEntry.size() * dimension * Float.BYTES;
    if (numBytes != fieldEntry.vectorDataLength) {
      throw new IllegalStateException(
          "Vector data length "
              + fieldEntry.vectorDataLength
              + " not matching size="
              + fieldEntry.size()
              + " * dim="
              + dimension
              + " * 4 = "
              + numBytes);
    }
  }

  private VectorSimilarityFunction readSimilarityFunction(DataInput input) throws IOException {
    int similarityFunctionId = input.readInt();
    if (similarityFunctionId < 0
        || similarityFunctionId >= VectorSimilarityFunction.values().length) {
      throw new CorruptIndexException(
          "Invalid similarity function id: " + similarityFunctionId, input);
    }
    return VectorSimilarityFunction.values()[similarityFunctionId];
  }

  private FieldEntry readField(IndexInput input, FieldInfo info) throws IOException {
    VectorSimilarityFunction similarityFunction = readSimilarityFunction(input);
    if (similarityFunction != info.getVectorSimilarityFunction()) {
      throw new IllegalStateException(
          "Inconsistent vector similarity function for field=\""
              + info.name
              + "\"; "
              + similarityFunction
              + " != "
              + info.getVectorSimilarityFunction());
    }
    return FieldEntry.create(input, info.getVectorSimilarityFunction());
  }

  @Override
  public void checkIntegrity() throws IOException {
    CodecUtil.checksumEntireFile(vectorData);
    CodecUtil.checksumEntireFile(vectorIndex);
  }

  @Override
  public FloatVectorValues getFloatVectorValues(String field) throws IOException {
    FieldEntry fieldEntry = fields.get(field);
    if (fieldEntry == null) {
      throw new IllegalArgumentException("field=\"" + field + "\" not found");
    }
    return OffHeapFloatVectorValues.load(fieldEntry, vectorData);
  }

  @Override
  public ByteVectorValues getByteVectorValues(String field) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void search(String field, float[] target, KnnCollector knnCollector, Bits acceptDocs)
      throws IOException {
    FieldEntry fieldEntry = fields.get(field);

    if (fieldEntry.size() == 0) {
      return;
    }

    OffHeapFloatVectorValues vectorValues = OffHeapFloatVectorValues.load(fieldEntry, vectorData);
    RandomVectorScorer scorer =
        defaultFlatVectorScorer.getRandomVectorScorer(
            fieldEntry.similarityFunction, vectorValues, target);
    HnswGraphSearcher.search(
        scorer,
        new OrdinalTranslatedKnnCollector(knnCollector, vectorValues::ordToDoc),
        getGraph(fieldEntry),
        vectorValues.getAcceptOrds(acceptDocs));
  }

  @Override
  public void search(String field, byte[] target, KnnCollector knnCollector, Bits acceptDocs)
      throws IOException {
    throw new UnsupportedOperationException();
  }

  private HnswGraph getGraph(FieldEntry entry) throws IOException {
    IndexInput bytesSlice =
        vectorIndex.slice("graph-data", entry.vectorIndexOffset, entry.vectorIndexLength);
    return new OffHeapHnswGraph(entry, bytesSlice);
  }

  @Override
  public void close() throws IOException {
    IOUtils.close(vectorData, vectorIndex);
  }

  static record FieldEntry(
      VectorSimilarityFunction similarityFunction,
      long vectorDataOffset,
      long vectorDataLength,
      long vectorIndexOffset,
      long vectorIndexLength,
      int M,
      int numLevels,
      int dimension,
      int size,
      int[][] nodesByLevel,
      // for each level the start offsets in vectorIndex file from where to read neighbours
      long[] graphOffsetsByLevel,

      // the following four variables used to read docIds encoded by IndexDISI
      // special values of docsWithFieldOffset are -1 and -2
      // -1 : dense
      // -2 : empty
      // other: sparse
      long docsWithFieldOffset,
      long docsWithFieldLength,
      short jumpTableEntryCount,
      byte denseRankPower,

      // the following four variables used to read ordToDoc encoded by DirectMonotonicWriter
      // note that only spare case needs to store ordToDoc
      long addressesOffset,
      int blockShift,
      DirectMonotonicReader.Meta meta,
      long addressesLength) {
    static FieldEntry create(IndexInput input, VectorSimilarityFunction similarityFunction)
        throws IOException {
      final var vectorDataOffset = input.readVLong();
      final var vectorDataLength = input.readVLong();
      final var vectorIndexOffset = input.readVLong();
      final var vectorIndexLength = input.readVLong();
      final var dimension = input.readInt();
      final var size = input.readInt();

      final var docsWithFieldOffset = input.readLong();
      final var docsWithFieldLength = input.readLong();
      final var jumpTableEntryCount = input.readShort();
      final var denseRankPower = input.readByte();

      final long addressesOffset;
      final int blockShift;
      final DirectMonotonicReader.Meta meta;
      final long addressesLength;
      // dense or empty
      if (docsWithFieldOffset == -1 || docsWithFieldOffset == -2) {
        addressesOffset = 0;
        blockShift = 0;
        meta = null;
        addressesLength = 0;
      } else {
        // sparse
        addressesOffset = input.readLong();
        blockShift = input.readVInt();
        meta = DirectMonotonicReader.loadMeta(input, size, blockShift);
        addressesLength = input.readLong();
      }

      // read nodes by level
      final var M = input.readInt();
      final var numLevels = input.readInt();
      final var nodesByLevel = new int[numLevels][];
      for (int level = 0; level < numLevels; level++) {
        int numNodesOnLevel = input.readInt();
        if (level == 0) {
          // we don't store nodes for level 0th, as this level contains all nodes
          assert numNodesOnLevel == size;
          nodesByLevel[0] = null;
        } else {
          nodesByLevel[level] = new int[numNodesOnLevel];
          for (int i = 0; i < numNodesOnLevel; i++) {
            nodesByLevel[level][i] = input.readInt();
          }
        }
      }

      // calculate for each level the start offsets in vectorIndex file from where to read
      // neighbours
      final var graphOffsetsByLevel = new long[numLevels];
      final long connectionsAndSizeLevel0Bytes =
          Math.multiplyExact(Math.addExact(1, Math.multiplyExact(M, 2L)), Integer.BYTES);
      final long connectionsAndSizeBytes = Math.multiplyExact(Math.addExact(1L, M), Integer.BYTES);
      for (int level = 0; level < numLevels; level++) {
        if (level == 0) {
          graphOffsetsByLevel[level] = 0;
        } else if (level == 1) {
          graphOffsetsByLevel[level] = Math.multiplyExact(connectionsAndSizeLevel0Bytes, size);
        } else {
          int numNodesOnPrevLevel = nodesByLevel[level - 1].length;
          graphOffsetsByLevel[level] =
              Math.addExact(
                  graphOffsetsByLevel[level - 1],
                  Math.multiplyExact(connectionsAndSizeBytes, numNodesOnPrevLevel));
        }
      }
      return new FieldEntry(
          similarityFunction,
          vectorDataOffset,
          vectorDataLength,
          vectorIndexOffset,
          vectorIndexLength,
          M,
          numLevels,
          dimension,
          size,
          nodesByLevel,
          graphOffsetsByLevel,
          docsWithFieldOffset,
          docsWithFieldLength,
          jumpTableEntryCount,
          denseRankPower,
          addressesOffset,
          blockShift,
          meta,
          addressesLength);
    }
  }

  /** Read the nearest-neighbors graph from the index input */
  private static final class OffHeapHnswGraph extends HnswGraph {

    final IndexInput dataIn;
    final int[][] nodesByLevel;
    final long[] graphOffsetsByLevel;
    final int numLevels;
    final int entryNode;
    final int size;
    final long bytesForConns;
    final long bytesForConns0;

    int arcCount;
    int arcUpTo;
    int arc;

    OffHeapHnswGraph(FieldEntry entry, IndexInput dataIn) {
      this.dataIn = dataIn;
      this.nodesByLevel = entry.nodesByLevel;
      this.numLevels = entry.numLevels;
      this.entryNode = numLevels > 1 ? nodesByLevel[numLevels - 1][0] : 0;
      this.size = entry.size();
      this.graphOffsetsByLevel = entry.graphOffsetsByLevel;
      this.bytesForConns = Math.multiplyExact(Math.addExact(entry.M, 1L), Integer.BYTES);
      this.bytesForConns0 =
          Math.multiplyExact(Math.addExact(Math.multiplyExact(entry.M, 2L), 1), Integer.BYTES);
    }

    @Override
    public void seek(int level, int targetOrd) throws IOException {
      int targetIndex =
          level == 0
              ? targetOrd
              : Arrays.binarySearch(nodesByLevel[level], 0, nodesByLevel[level].length, targetOrd);
      assert targetIndex >= 0;
      long graphDataOffset =
          graphOffsetsByLevel[level] + targetIndex * (level == 0 ? bytesForConns0 : bytesForConns);
      // unsafe; no bounds checking
      dataIn.seek(graphDataOffset);
      arcCount = dataIn.readInt();
      arc = -1;
      arcUpTo = 0;
    }

    @Override
    public int size() {
      return size;
    }

    @Override
    public int nextNeighbor() throws IOException {
      if (arcUpTo >= arcCount) {
        return NO_MORE_DOCS;
      }
      ++arcUpTo;
      arc = dataIn.readInt();
      return arc;
    }

    @Override
    public int numLevels() {
      return numLevels;
    }

    @Override
    public int entryNode() {
      return entryNode;
    }

    @Override
    public NodesIterator getNodesOnLevel(int level) {
      if (level == 0) {
        return new ArrayNodesIterator(size());
      } else {
        return new ArrayNodesIterator(nodesByLevel[level], nodesByLevel[level].length);
      }
    }
  }
}
