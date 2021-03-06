/**
 * Copyright (C) 2014-2018 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.query.aggregation.groupby;

import com.linkedin.pinot.common.data.DimensionFieldSpec;
import com.linkedin.pinot.common.data.FieldSpec;
import com.linkedin.pinot.common.data.Schema;
import com.linkedin.pinot.common.request.transform.TransformExpressionTree;
import com.linkedin.pinot.common.segment.ReadMode;
import com.linkedin.pinot.core.data.GenericRow;
import com.linkedin.pinot.core.data.readers.GenericRowRecordReader;
import com.linkedin.pinot.core.indexsegment.IndexSegment;
import com.linkedin.pinot.core.indexsegment.generator.SegmentGeneratorConfig;
import com.linkedin.pinot.core.indexsegment.immutable.ImmutableSegmentLoader;
import com.linkedin.pinot.core.operator.blocks.TransformBlock;
import com.linkedin.pinot.core.operator.transform.TransformOperator;
import com.linkedin.pinot.core.plan.TransformPlanNode;
import com.linkedin.pinot.core.query.aggregation.groupby.DictionaryBasedGroupKeyGenerator;
import com.linkedin.pinot.core.query.aggregation.groupby.GroupKeyGenerator;
import com.linkedin.pinot.core.segment.creator.impl.SegmentIndexCreationDriverImpl;
import com.linkedin.pinot.pql.parsers.Pql2Compiler;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


public class DictionaryBasedGroupKeyGeneratorTest {
  private static final String SEGMENT_NAME = "testSegment";
  private static final String INDEX_DIR_PATH = FileUtils.getTempDirectoryPath() + File.separator + SEGMENT_NAME;
  private static final int ARRAY_BASED_THRESHOLD = 10_000;
  private static final int NUM_ROWS = 1000;
  private static final int UNIQUE_ROWS = 100;
  private static final int MAX_STEP_LENGTH = 1000;
  private static final int MAX_NUM_MULTI_VALUES = 10;
  private static final int[] SV_GROUP_KEY_BUFFER = new int[NUM_ROWS];
  private static final int[][] MV_GROUP_KEY_BUFFER = new int[NUM_ROWS][];
  private static final String FILTER_COLUMN = "docId";
  private static final String[] SV_COLUMNS = {"s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10"};
  private static final String[] MV_COLUMNS = {"m1", "m2"};

  private final long _randomSeed = System.currentTimeMillis();
  private final Random _random = new Random(_randomSeed);
  private final String _errorMessage = "Random seed is: " + _randomSeed;

  private TransformOperator _transformOperator;
  private TransformBlock _transformBlock;

  @BeforeClass
  private void setup() throws Exception {
    FileUtils.deleteQuietly(new File(INDEX_DIR_PATH));

    List<GenericRow> rows = new ArrayList<>(NUM_ROWS);
    int value = _random.nextInt(MAX_STEP_LENGTH);

    // Generate random values for the segment.
    for (int i = 0; i < UNIQUE_ROWS; i++) {
      Map<String, Object> map = new HashMap<>();
      map.put(FILTER_COLUMN, i);
      for (String svColumn : SV_COLUMNS) {
        map.put(svColumn, value);
        value += 1 + _random.nextInt(MAX_STEP_LENGTH);
      }
      for (String mvColumn : MV_COLUMNS) {
        int numMultiValues = 1 + _random.nextInt(MAX_NUM_MULTI_VALUES);
        Integer[] values = new Integer[numMultiValues];
        for (int k = 0; k < numMultiValues; k++) {
          values[k] = value;
          value += 1 + _random.nextInt(MAX_STEP_LENGTH);
        }
        map.put(mvColumn, values);
      }
      GenericRow row = new GenericRow();
      row.init(map);
      rows.add(row);
    }
    for (int i = UNIQUE_ROWS; i < NUM_ROWS; i++) {
      rows.add(rows.get(i % UNIQUE_ROWS));
    }

    // Create an index segment with the random values.
    Schema schema = new Schema();
    schema.addField(new DimensionFieldSpec(FILTER_COLUMN, FieldSpec.DataType.INT, true));
    for (String singleValueColumn : SV_COLUMNS) {
      schema.addField(new DimensionFieldSpec(singleValueColumn, FieldSpec.DataType.INT, true));
    }
    for (String multiValueColumn : MV_COLUMNS) {
      schema.addField(new DimensionFieldSpec(multiValueColumn, FieldSpec.DataType.INT, false));
    }

    SegmentGeneratorConfig config = new SegmentGeneratorConfig(schema);
    config.setOutDir(INDEX_DIR_PATH);
    config.setSegmentName(SEGMENT_NAME);

    SegmentIndexCreationDriverImpl driver = new SegmentIndexCreationDriverImpl();
    driver.init(config, new GenericRowRecordReader(rows, schema));
    driver.build();
    IndexSegment indexSegment = ImmutableSegmentLoader.load(new File(INDEX_DIR_PATH, SEGMENT_NAME), ReadMode.heap);

    // Generate a random query to filter out 2 unique rows
    int docId1 = _random.nextInt(50);
    int docId2 = docId1 + 1 + _random.nextInt(50);
    // NOTE: put all columns into group-by so that transform operator has expressions for all columns
    String query =
        String.format("SELECT COUNT(*) FROM table WHERE %s IN (%d, %d) GROUP BY %s, %s", FILTER_COLUMN, docId1, docId2,
            StringUtils.join(SV_COLUMNS, ", "), StringUtils.join(MV_COLUMNS, ", "));
    TransformPlanNode transformPlanNode =
        new TransformPlanNode(indexSegment, new Pql2Compiler().compileToBrokerRequest(query));
    _transformOperator = transformPlanNode.run();
    _transformBlock = _transformOperator.nextBlock();
  }

  @Test
  public void testArrayBasedSingleValue() {
    // Cardinality product < threshold.
    String[] groupByColumns = {"s1"};

    // Test initial status.
    DictionaryBasedGroupKeyGenerator dictionaryBasedGroupKeyGenerator =
        new DictionaryBasedGroupKeyGenerator(_transformOperator, getExpressions(groupByColumns), ARRAY_BASED_THRESHOLD);
    Assert.assertEquals(dictionaryBasedGroupKeyGenerator.getGlobalGroupKeyUpperBound(), UNIQUE_ROWS, _errorMessage);
    Assert.assertEquals(dictionaryBasedGroupKeyGenerator.getCurrentGroupKeyUpperBound(), UNIQUE_ROWS, _errorMessage);

    // Test group key generation.
    dictionaryBasedGroupKeyGenerator.generateKeysForBlock(_transformBlock, SV_GROUP_KEY_BUFFER);
    Assert.assertEquals(dictionaryBasedGroupKeyGenerator.getCurrentGroupKeyUpperBound(), 100, _errorMessage);
    compareSingleValueBuffer();
    testGetUniqueGroupKeys(dictionaryBasedGroupKeyGenerator.getUniqueGroupKeys(), 2);
  }

  @Test
  public void testLongMapBasedSingleValue() {
    // Cardinality product > threshold.
    String[] groupByColumns = {"s1", "s2", "s3", "s4"};

    // Test initial status.
    long expected = (long) Math.pow(UNIQUE_ROWS, groupByColumns.length);
    DictionaryBasedGroupKeyGenerator dictionaryBasedGroupKeyGenerator =
        new DictionaryBasedGroupKeyGenerator(_transformOperator, getExpressions(groupByColumns), ARRAY_BASED_THRESHOLD);
    Assert.assertEquals(dictionaryBasedGroupKeyGenerator.getGlobalGroupKeyUpperBound(), expected, _errorMessage);
    Assert.assertEquals(dictionaryBasedGroupKeyGenerator.getCurrentGroupKeyUpperBound(), 0, _errorMessage);

    // Test group key generation.
    dictionaryBasedGroupKeyGenerator.generateKeysForBlock(_transformBlock, SV_GROUP_KEY_BUFFER);
    Assert.assertEquals(dictionaryBasedGroupKeyGenerator.getCurrentGroupKeyUpperBound(), 2, _errorMessage);
    compareSingleValueBuffer();
    testGetUniqueGroupKeys(dictionaryBasedGroupKeyGenerator.getUniqueGroupKeys(), 2);
  }

  @Test
  public void testArrayMapBasedSingleValue() {
    // Cardinality product > Long.MAX_VALUE.
    String[] groupByColumns = {"s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10"};

    // Test initial status.
    DictionaryBasedGroupKeyGenerator dictionaryBasedGroupKeyGenerator =
        new DictionaryBasedGroupKeyGenerator(_transformOperator, getExpressions(groupByColumns), ARRAY_BASED_THRESHOLD);
    Assert.assertEquals(dictionaryBasedGroupKeyGenerator.getGlobalGroupKeyUpperBound(), Integer.MAX_VALUE,
        _errorMessage);
    Assert.assertEquals(dictionaryBasedGroupKeyGenerator.getCurrentGroupKeyUpperBound(), 0, _errorMessage);

    // Test group key generation.
    dictionaryBasedGroupKeyGenerator.generateKeysForBlock(_transformBlock, SV_GROUP_KEY_BUFFER);
    Assert.assertEquals(dictionaryBasedGroupKeyGenerator.getCurrentGroupKeyUpperBound(), 2, _errorMessage);
    compareSingleValueBuffer();
    testGetUniqueGroupKeys(dictionaryBasedGroupKeyGenerator.getUniqueGroupKeys(), 2);
  }

  /**
   * Helper method to compare the values inside the single value group key buffer.
   *
   * All odd number index values should be the same, all even number index values should be the same.
   * Odd number index values should be different from even number index values.
   */
  private void compareSingleValueBuffer() {
    Assert.assertTrue(SV_GROUP_KEY_BUFFER[0] != SV_GROUP_KEY_BUFFER[1], _errorMessage);
    for (int i = 0; i < 20; i += 2) {
      Assert.assertEquals(SV_GROUP_KEY_BUFFER[i], SV_GROUP_KEY_BUFFER[0], _errorMessage);
      Assert.assertEquals(SV_GROUP_KEY_BUFFER[i + 1], SV_GROUP_KEY_BUFFER[1], _errorMessage);
    }
  }

  @Test
  public void testArrayBasedMultiValue() {
    // Cardinality product < threshold.
    String[] groupByColumns = {"m1"};

    // Test initial status.
    DictionaryBasedGroupKeyGenerator dictionaryBasedGroupKeyGenerator =
        new DictionaryBasedGroupKeyGenerator(_transformOperator, getExpressions(groupByColumns), ARRAY_BASED_THRESHOLD);
    int groupKeyUpperBound = dictionaryBasedGroupKeyGenerator.getGlobalGroupKeyUpperBound();
    Assert.assertEquals(dictionaryBasedGroupKeyGenerator.getCurrentGroupKeyUpperBound(), groupKeyUpperBound,
        _errorMessage);

    // Test group key generation.
    dictionaryBasedGroupKeyGenerator.generateKeysForBlock(_transformBlock, MV_GROUP_KEY_BUFFER);
    int numUniqueKeys = MV_GROUP_KEY_BUFFER[0].length + MV_GROUP_KEY_BUFFER[1].length;
    Assert.assertEquals(dictionaryBasedGroupKeyGenerator.getCurrentGroupKeyUpperBound(), groupKeyUpperBound,
        _errorMessage);
    compareMultiValueBuffer();
    testGetUniqueGroupKeys(dictionaryBasedGroupKeyGenerator.getUniqueGroupKeys(), numUniqueKeys);
  }

  @Test
  public void testLongMapBasedMultiValue() {
    // Cardinality product > threshold.
    String[] groupByColumns = {"m1", "m2", "s1", "s2"};

    // Test initial status.
    DictionaryBasedGroupKeyGenerator dictionaryBasedGroupKeyGenerator =
        new DictionaryBasedGroupKeyGenerator(_transformOperator, getExpressions(groupByColumns), ARRAY_BASED_THRESHOLD);
    Assert.assertEquals(dictionaryBasedGroupKeyGenerator.getCurrentGroupKeyUpperBound(), 0, _errorMessage);

    // Test group key generation.
    dictionaryBasedGroupKeyGenerator.generateKeysForBlock(_transformBlock, MV_GROUP_KEY_BUFFER);
    int numUniqueKeys = MV_GROUP_KEY_BUFFER[0].length + MV_GROUP_KEY_BUFFER[1].length;
    Assert.assertEquals(dictionaryBasedGroupKeyGenerator.getCurrentGroupKeyUpperBound(), numUniqueKeys, _errorMessage);
    compareMultiValueBuffer();
    testGetUniqueGroupKeys(dictionaryBasedGroupKeyGenerator.getUniqueGroupKeys(), numUniqueKeys);
  }

  @Test
  public void testArrayMapBasedMultiValue() {
    // Cardinality product > Long.MAX_VALUE.
    String[] groupByColumns = {"m1", "m2", "s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10"};

    // Test initial status.
    DictionaryBasedGroupKeyGenerator dictionaryBasedGroupKeyGenerator =
        new DictionaryBasedGroupKeyGenerator(_transformOperator, getExpressions(groupByColumns), ARRAY_BASED_THRESHOLD);
    Assert.assertEquals(dictionaryBasedGroupKeyGenerator.getCurrentGroupKeyUpperBound(), 0, _errorMessage);

    // Test group key generation.
    dictionaryBasedGroupKeyGenerator.generateKeysForBlock(_transformBlock, MV_GROUP_KEY_BUFFER);
    int numUniqueKeys = MV_GROUP_KEY_BUFFER[0].length + MV_GROUP_KEY_BUFFER[1].length;
    Assert.assertEquals(dictionaryBasedGroupKeyGenerator.getCurrentGroupKeyUpperBound(), numUniqueKeys, _errorMessage);
    compareMultiValueBuffer();
    testGetUniqueGroupKeys(dictionaryBasedGroupKeyGenerator.getUniqueGroupKeys(), numUniqueKeys);
  }

  private static TransformExpressionTree[] getExpressions(String[] columns) {
    int numColumns = columns.length;
    TransformExpressionTree[] expressions = new TransformExpressionTree[numColumns];
    for (int i = 0; i < numColumns; i++) {
      expressions[i] = TransformExpressionTree.compileToExpressionTree(columns[i]);
    }
    return expressions;
  }

  /**
   * Helper method to compare the values inside the multi value group key buffer.
   *
   * All odd number index values should be the same, all even number index values should be the same.
   * Odd number index values should be different from even number index values.
   */
  private void compareMultiValueBuffer() {
    int length0 = MV_GROUP_KEY_BUFFER[0].length;
    int length1 = MV_GROUP_KEY_BUFFER[1].length;
    int compareLength = Math.min(length0, length1);
    for (int i = 0; i < compareLength; i++) {
      Assert.assertTrue(MV_GROUP_KEY_BUFFER[0][i] != MV_GROUP_KEY_BUFFER[1][i], _errorMessage);
    }
    for (int i = 0; i < 20; i += 2) {
      for (int j = 0; j < length0; j++) {
        Assert.assertEquals(MV_GROUP_KEY_BUFFER[i][j], MV_GROUP_KEY_BUFFER[0][j], _errorMessage);
      }
      for (int j = 0; j < length1; j++) {
        Assert.assertEquals(MV_GROUP_KEY_BUFFER[i + 1][j], MV_GROUP_KEY_BUFFER[1][j], _errorMessage);
      }
    }
  }

  /**
   * Helper method to test the group key iterator returned by getUniqueGroupKeys().
   *
   * @param groupKeyIterator group key iterator.
   * @param numUniqueKeys number of unique keys.
   */
  private void testGetUniqueGroupKeys(Iterator<GroupKeyGenerator.GroupKey> groupKeyIterator, int numUniqueKeys) {
    int count = 0;
    Set<Integer> idSet = new HashSet<>();
    Set<String> groupKeySet = new HashSet<>();

    while (groupKeyIterator.hasNext()) {
      count++;
      GroupKeyGenerator.GroupKey groupKey = groupKeyIterator.next();
      idSet.add(groupKey._groupId);
      groupKeySet.add(groupKey._stringKey);
    }

    Assert.assertEquals(count, numUniqueKeys, _errorMessage);
    Assert.assertEquals(idSet.size(), numUniqueKeys, _errorMessage);
    Assert.assertEquals(groupKeySet.size(), numUniqueKeys, _errorMessage);
  }

  @AfterClass
  public void tearDown() {
    FileUtils.deleteQuietly(new File(INDEX_DIR_PATH));
  }
}
