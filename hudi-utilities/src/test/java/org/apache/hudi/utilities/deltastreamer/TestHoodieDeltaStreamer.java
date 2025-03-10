/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hudi.utilities.deltastreamer;

import org.apache.hudi.DataSourceReadOptions;
import org.apache.hudi.DataSourceWriteOptions;
import org.apache.hudi.HoodieSparkRecordMerger;
import org.apache.hudi.HoodieSparkUtils$;
import org.apache.hudi.client.SparkRDDWriteClient;
import org.apache.hudi.client.transaction.lock.InProcessLockProvider;
import org.apache.hudi.common.config.DFSPropertiesConfiguration;
import org.apache.hudi.common.config.HoodieConfig;
import org.apache.hudi.common.config.HoodieMetadataConfig;
import org.apache.hudi.common.config.HoodieStorageConfig;
import org.apache.hudi.common.config.LockConfiguration;
import org.apache.hudi.common.config.TypedProperties;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.HoodieCommitMetadata;
import org.apache.hudi.common.model.HoodieFailedWritesCleaningPolicy;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecord.HoodieRecordType;
import org.apache.hudi.common.model.HoodieReplaceCommitMetadata;
import org.apache.hudi.common.model.HoodieTableType;
import org.apache.hudi.common.model.OverwriteWithLatestAvroPayload;
import org.apache.hudi.common.model.PartialUpdateAvroPayload;
import org.apache.hudi.common.model.WriteConcurrencyMode;
import org.apache.hudi.common.model.WriteOperationType;
import org.apache.hudi.common.table.HoodieTableConfig;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.TableSchemaResolver;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.testutils.HoodieTestDataGenerator;
import org.apache.hudi.common.testutils.HoodieTestUtils;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.StringUtils;
import org.apache.hudi.config.HoodieArchivalConfig;
import org.apache.hudi.config.HoodieCleanConfig;
import org.apache.hudi.config.HoodieClusteringConfig;
import org.apache.hudi.config.HoodieCompactionConfig;
import org.apache.hudi.config.HoodieLockConfig;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.exception.TableNotFoundException;
import org.apache.hudi.hive.HiveSyncConfig;
import org.apache.hudi.hive.HoodieHiveSyncClient;
import org.apache.hudi.keygen.SimpleKeyGenerator;
import org.apache.hudi.metrics.Metrics;
import org.apache.hudi.utilities.DummySchemaProvider;
import org.apache.hudi.utilities.HoodieClusteringJob;
import org.apache.hudi.utilities.HoodieIndexer;
import org.apache.hudi.utilities.schema.FilebasedSchemaProvider;
import org.apache.hudi.utilities.schema.KafkaOffsetPostProcessor;
import org.apache.hudi.utilities.schema.SchemaProvider;
import org.apache.hudi.utilities.schema.SparkAvroPostProcessor;
import org.apache.hudi.utilities.sources.CsvDFSSource;
import org.apache.hudi.utilities.sources.HoodieIncrSource;
import org.apache.hudi.utilities.sources.InputBatch;
import org.apache.hudi.utilities.sources.JdbcSource;
import org.apache.hudi.utilities.sources.JsonKafkaSource;
import org.apache.hudi.utilities.sources.ORCDFSSource;
import org.apache.hudi.utilities.sources.ParquetDFSSource;
import org.apache.hudi.utilities.sources.SqlSource;
import org.apache.hudi.utilities.sources.TestDataSource;
import org.apache.hudi.utilities.sources.TestParquetDFSSourceEmptyBatch;
import org.apache.hudi.utilities.testutils.JdbcTestUtils;
import org.apache.hudi.utilities.testutils.UtilitiesTestBase;
import org.apache.hudi.utilities.testutils.sources.DistributedTestDataSource;
import org.apache.hudi.utilities.testutils.sources.config.SourceConfigs;
import org.apache.hudi.utilities.transform.SqlQueryBasedTransformer;
import org.apache.hudi.utilities.transform.Transformer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.AnalysisException;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.api.java.UDF4;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.hudi.config.HoodieWriteConfig.MUTLI_WRITER_SOURCE_CHECKPOINT_ID;
import static org.apache.hudi.config.metrics.HoodieMetricsConfig.METRICS_REPORTER_TYPE_VALUE;
import static org.apache.hudi.config.metrics.HoodieMetricsConfig.TURN_METRICS_ON;
import static org.apache.hudi.sync.common.HoodieSyncConfig.META_SYNC_PARTITION_FIELDS;
import static org.apache.hudi.sync.common.HoodieSyncConfig.META_SYNC_TABLE_NAME;
import static org.apache.hudi.utilities.UtilHelpers.EXECUTE;
import static org.apache.hudi.utilities.UtilHelpers.SCHEDULE;
import static org.apache.hudi.utilities.UtilHelpers.SCHEDULE_AND_EXECUTE;
import static org.apache.hudi.utilities.deltastreamer.HoodieDeltaStreamer.CHECKPOINT_KEY;
import static org.apache.hudi.utilities.schema.KafkaOffsetPostProcessor.KAFKA_SOURCE_OFFSET_COLUMN;
import static org.apache.hudi.utilities.schema.KafkaOffsetPostProcessor.KAFKA_SOURCE_PARTITION_COLUMN;
import static org.apache.hudi.utilities.schema.KafkaOffsetPostProcessor.KAFKA_SOURCE_TIMESTAMP_COLUMN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Basic tests against {@link HoodieDeltaStreamer}, by issuing bulk_inserts, upserts, inserts. Check counts at the end.
 */
public class TestHoodieDeltaStreamer extends HoodieDeltaStreamerTestBase {

  private static final Logger LOG = LogManager.getLogger(TestHoodieDeltaStreamer.class);

  protected HoodieDeltaStreamer initialHoodieDeltaStreamer(String tableBasePath, int totalRecords, String asyncCluster, HoodieRecordType recordType) throws IOException {
    return initialHoodieDeltaStreamer(tableBasePath, totalRecords, asyncCluster, recordType, WriteOperationType.INSERT);
  }

  protected HoodieDeltaStreamer initialHoodieDeltaStreamer(String tableBasePath, int totalRecords, String asyncCluster, HoodieRecordType recordType,
                                                             WriteOperationType writeOperationType) throws IOException {
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, writeOperationType);
    TestHelpers.addRecordMerger(recordType, cfg.configs);
    cfg.continuousMode = true;
    cfg.tableType = HoodieTableType.COPY_ON_WRITE.name();
    cfg.configs.addAll(getAsyncServicesConfigs(totalRecords, "false", "", "", asyncCluster, ""));
    cfg.configs.addAll(getAllMultiWriterConfigs());
    return new HoodieDeltaStreamer(cfg, jsc);
  }

  protected HoodieClusteringJob initialHoodieClusteringJob(String tableBasePath, String clusteringInstantTime, Boolean runSchedule, String scheduleAndExecute, HoodieRecordType recordType) {
    return initialHoodieClusteringJob(tableBasePath, clusteringInstantTime, runSchedule, scheduleAndExecute, null, recordType);
  }

  protected HoodieClusteringJob initialHoodieClusteringJob(String tableBasePath, String clusteringInstantTime, Boolean runSchedule, String scheduleAndExecute) {
    return initialHoodieClusteringJob(tableBasePath, clusteringInstantTime, runSchedule, scheduleAndExecute, null, HoodieRecordType.AVRO);
  }

  protected HoodieClusteringJob initialHoodieClusteringJob(String tableBasePath, String clusteringInstantTime, Boolean runSchedule, String scheduleAndExecute,
                                                           Boolean retryLastFailedClusteringJob, HoodieRecordType recordType) {
    HoodieClusteringJob.Config scheduleClusteringConfig = buildHoodieClusteringUtilConfig(tableBasePath,
        clusteringInstantTime, runSchedule, scheduleAndExecute, retryLastFailedClusteringJob);
    TestHelpers.addRecordMerger(recordType, scheduleClusteringConfig.configs);
    scheduleClusteringConfig.configs.addAll(getAllMultiWriterConfigs());
    return new HoodieClusteringJob(jsc, scheduleClusteringConfig);
  }

  static class TestHelpers {

    static HoodieDeltaStreamer.Config makeDropAllConfig(String basePath, WriteOperationType op) {
      return makeConfig(basePath, op, Collections.singletonList(DropAllTransformer.class.getName()));
    }

    static HoodieDeltaStreamer.Config makeConfig(String basePath, WriteOperationType op) {
      return makeConfig(basePath, op, Collections.singletonList(TripsWithDistanceTransformer.class.getName()));
    }

    static HoodieDeltaStreamer.Config makeConfig(String basePath, WriteOperationType op, List<String> transformerClassNames) {
      return makeConfig(basePath, op, transformerClassNames, PROPS_FILENAME_TEST_SOURCE, false);
    }

    static HoodieDeltaStreamer.Config makeConfig(String basePath, WriteOperationType op, List<String> transformerClassNames,
                                                 String propsFilename, boolean enableHiveSync) {
      return makeConfig(basePath, op, transformerClassNames, propsFilename, enableHiveSync, true,
          false, null, null);
    }

    static HoodieDeltaStreamer.Config makeConfig(String basePath, WriteOperationType op, List<String> transformerClassNames,
                                                 String propsFilename, boolean enableHiveSync, boolean useSchemaProviderClass, boolean updatePayloadClass,
                                                 String payloadClassName, String tableType) {
      return makeConfig(basePath, op, TestDataSource.class.getName(), transformerClassNames, propsFilename, enableHiveSync,
          useSchemaProviderClass, 1000, updatePayloadClass, payloadClassName, tableType, "timestamp", null);
    }

    static HoodieDeltaStreamer.Config makeConfig(String basePath, WriteOperationType op, String sourceClassName,
                                                 List<String> transformerClassNames, String propsFilename, boolean enableHiveSync, boolean useSchemaProviderClass,
                                                 int sourceLimit, boolean updatePayloadClass, String payloadClassName, String tableType, String sourceOrderingField,
                                                 String checkpoint) {
      return makeConfig(basePath, op, sourceClassName, transformerClassNames, propsFilename, enableHiveSync, useSchemaProviderClass, sourceLimit, updatePayloadClass, payloadClassName,
          tableType, sourceOrderingField, checkpoint, false);
    }

    static HoodieDeltaStreamer.Config makeConfig(String basePath, WriteOperationType op, String sourceClassName,
                                                 List<String> transformerClassNames, String propsFilename, boolean enableHiveSync, boolean useSchemaProviderClass,
                                                 int sourceLimit, boolean updatePayloadClass, String payloadClassName, String tableType, String sourceOrderingField,
                                                 String checkpoint, boolean allowCommitOnNoCheckpointChange) {
      HoodieDeltaStreamer.Config cfg = new HoodieDeltaStreamer.Config();
      cfg.targetBasePath = basePath;
      cfg.targetTableName = "hoodie_trips";
      cfg.tableType = tableType == null ? "COPY_ON_WRITE" : tableType;
      cfg.sourceClassName = sourceClassName;
      cfg.transformerClassNames = transformerClassNames;
      cfg.operation = op;
      cfg.enableHiveSync = enableHiveSync;
      cfg.sourceOrderingField = sourceOrderingField;
      cfg.propsFilePath = UtilitiesTestBase.basePath + "/" + propsFilename;
      cfg.sourceLimit = sourceLimit;
      cfg.checkpoint = checkpoint;
      if (updatePayloadClass) {
        cfg.payloadClassName = payloadClassName;
      }
      if (useSchemaProviderClass) {
        cfg.schemaProviderClassName = defaultSchemaProviderClassName;
      }
      cfg.allowCommitOnNoCheckpointChange = allowCommitOnNoCheckpointChange;
      return cfg;
    }

    static HoodieDeltaStreamer.Config makeConfigForHudiIncrSrc(String srcBasePath, String basePath, WriteOperationType op,
                                                               boolean addReadLatestOnMissingCkpt, String schemaProviderClassName) {
      HoodieDeltaStreamer.Config cfg = new HoodieDeltaStreamer.Config();
      cfg.targetBasePath = basePath;
      cfg.targetTableName = "hoodie_trips_copy";
      cfg.tableType = "COPY_ON_WRITE";
      cfg.sourceClassName = HoodieIncrSource.class.getName();
      cfg.operation = op;
      cfg.sourceOrderingField = "timestamp";
      cfg.propsFilePath = UtilitiesTestBase.basePath + "/test-downstream-source.properties";
      cfg.sourceLimit = 1000;
      if (null != schemaProviderClassName) {
        cfg.schemaProviderClassName = schemaProviderClassName;
      }
      List<String> cfgs = new ArrayList<>();
      cfgs.add("hoodie.deltastreamer.source.hoodieincr.read_latest_on_missing_ckpt=" + addReadLatestOnMissingCkpt);
      cfgs.add("hoodie.deltastreamer.source.hoodieincr.path=" + srcBasePath);
      // No partition
      cfgs.add("hoodie.deltastreamer.source.hoodieincr.partition.fields=datestr");
      cfg.configs = cfgs;
      return cfg;
    }

    static void addRecordMerger(HoodieRecordType type, List<String> hoodieConfig) {
      if (type == HoodieRecordType.SPARK) {
        hoodieConfig.add(String.format("%s=%s", HoodieWriteConfig.RECORD_MERGER_IMPLS.key(), HoodieSparkRecordMerger.class.getName()));
        hoodieConfig.add(String.format("%s=%s", HoodieStorageConfig.LOGFILE_DATA_BLOCK_FORMAT.key(),"parquet"));
      }
    }

    static void assertRecordCount(long expected, String tablePath, SQLContext sqlContext) {
      sqlContext.clearCache();
      long recordCount = sqlContext.read().format("org.apache.hudi").load(tablePath).count();
      assertEquals(expected, recordCount);
    }

    static Map<String, Long> getPartitionRecordCount(String basePath, SQLContext sqlContext) {
      sqlContext.clearCache();
      List<Row> rows = sqlContext.read().format("org.apache.hudi").load(basePath).groupBy(HoodieRecord.PARTITION_PATH_METADATA_FIELD).count().collectAsList();
      Map<String, Long> partitionRecordCount = new HashMap<>();
      rows.stream().forEach(row -> partitionRecordCount.put(row.getString(0), row.getLong(1)));
      return partitionRecordCount;
    }

    static void assertNoPartitionMatch(String basePath, SQLContext sqlContext, String partitionToValidate) {
      sqlContext.clearCache();
      assertEquals(0, sqlContext.read().format("org.apache.hudi").load(basePath).filter(HoodieRecord.PARTITION_PATH_METADATA_FIELD + " = " + partitionToValidate).count());
    }

    static void assertDistinctRecordCount(long expected, String tablePath, SQLContext sqlContext) {
      sqlContext.clearCache();
      long recordCount = sqlContext.read().format("org.apache.hudi").load(tablePath).select("_hoodie_record_key").distinct().count();
      assertEquals(expected, recordCount);
    }

    static List<Row> countsPerCommit(String tablePath, SQLContext sqlContext) {
      sqlContext.clearCache();
      List<Row> rows = sqlContext.read().format("org.apache.hudi").load(tablePath)
          .groupBy("_hoodie_commit_time").count()
          .sort("_hoodie_commit_time").collectAsList();
      return rows;
    }

    static void assertDistanceCount(long expected, String tablePath, SQLContext sqlContext) {
      sqlContext.clearCache();
      sqlContext.read().format("org.apache.hudi").load(tablePath).registerTempTable("tmp_trips");
      long recordCount =
          sqlContext.sql("select * from tmp_trips where haversine_distance is not NULL").count();
      assertEquals(expected, recordCount);
    }

    static void assertDistanceCountWithExactValue(long expected, String tablePath, SQLContext sqlContext) {
      sqlContext.clearCache();
      sqlContext.read().format("org.apache.hudi").load(tablePath).registerTempTable("tmp_trips");
      long recordCount =
          sqlContext.sql("select * from tmp_trips where haversine_distance = 1.0").count();
      assertEquals(expected, recordCount);
    }

    static void assertAtleastNCompactionCommits(int minExpected, String tablePath, FileSystem fs) {
      HoodieTableMetaClient meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tablePath).build();
      HoodieTimeline timeline = meta.getActiveTimeline().getCommitTimeline().filterCompletedInstants();
      LOG.info("Timeline Instants=" + meta.getActiveTimeline().getInstants());
      int numCompactionCommits = timeline.countInstants();
      assertTrue(minExpected <= numCompactionCommits, "Got=" + numCompactionCommits + ", exp >=" + minExpected);
    }

    static void assertAtleastNDeltaCommits(int minExpected, String tablePath, FileSystem fs) {
      HoodieTableMetaClient meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tablePath).build();
      HoodieTimeline timeline = meta.getActiveTimeline().getDeltaCommitTimeline().filterCompletedInstants();
      LOG.info("Timeline Instants=" + meta.getActiveTimeline().getInstants());
      int numDeltaCommits = timeline.countInstants();
      assertTrue(minExpected <= numDeltaCommits, "Got=" + numDeltaCommits + ", exp >=" + minExpected);
    }

    static void assertAtleastNCompactionCommitsAfterCommit(int minExpected, String lastSuccessfulCommit, String tablePath, FileSystem fs) {
      HoodieTableMetaClient meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tablePath).build();
      HoodieTimeline timeline = meta.getActiveTimeline().getCommitTimeline().findInstantsAfter(lastSuccessfulCommit).filterCompletedInstants();
      LOG.info("Timeline Instants=" + meta.getActiveTimeline().getInstants());
      int numCompactionCommits = timeline.countInstants();
      assertTrue(minExpected <= numCompactionCommits, "Got=" + numCompactionCommits + ", exp >=" + minExpected);
    }

    static void assertAtleastNDeltaCommitsAfterCommit(int minExpected, String lastSuccessfulCommit, String tablePath, FileSystem fs) {
      HoodieTableMetaClient meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tablePath).build();
      HoodieTimeline timeline = meta.reloadActiveTimeline().getDeltaCommitTimeline().findInstantsAfter(lastSuccessfulCommit).filterCompletedInstants();
      LOG.info("Timeline Instants=" + meta.getActiveTimeline().getInstants());
      int numDeltaCommits = timeline.countInstants();
      assertTrue(minExpected <= numDeltaCommits, "Got=" + numDeltaCommits + ", exp >=" + minExpected);
    }

    static String assertCommitMetadata(String expected, String tablePath, FileSystem fs, int totalCommits)
        throws IOException {
      HoodieTableMetaClient meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tablePath).build();
      HoodieTimeline timeline = meta.getActiveTimeline().getCommitsTimeline().filterCompletedInstants();
      HoodieInstant lastInstant = timeline.lastInstant().get();
      HoodieCommitMetadata commitMetadata =
          HoodieCommitMetadata.fromBytes(timeline.getInstantDetails(lastInstant).get(), HoodieCommitMetadata.class);
      assertEquals(totalCommits, timeline.countInstants());
      assertEquals(expected, commitMetadata.getMetadata(CHECKPOINT_KEY));
      return lastInstant.getTimestamp();
    }

    static void waitTillCondition(Function<Boolean, Boolean> condition, Future dsFuture, long timeoutInSecs) throws Exception {
      Future<Boolean> res = Executors.newSingleThreadExecutor().submit(() -> {
        boolean ret = false;
        while (!ret && !dsFuture.isDone()) {
          try {
            Thread.sleep(3000);
            ret = condition.apply(true);
          } catch (Throwable error) {
            LOG.warn("Got error :", error);
            ret = false;
          }
        }
        return ret;
      });
      res.get(timeoutInSecs, TimeUnit.SECONDS);
    }

    static void assertAtLeastNCommits(int minExpected, String tablePath, FileSystem fs) {
      HoodieTableMetaClient meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tablePath).build();
      HoodieTimeline timeline = meta.getActiveTimeline().filterCompletedInstants();
      LOG.info("Timeline Instants=" + meta.getActiveTimeline().getInstants());
      int numDeltaCommits = timeline.countInstants();
      assertTrue(minExpected <= numDeltaCommits, "Got=" + numDeltaCommits + ", exp >=" + minExpected);
    }

    static void assertAtLeastNReplaceCommits(int minExpected, String tablePath, FileSystem fs) {
      HoodieTableMetaClient meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tablePath).setLoadActiveTimelineOnLoad(true).build();
      HoodieTimeline timeline = meta.getActiveTimeline().getCompletedReplaceTimeline();
      LOG.info("Timeline Instants=" + meta.getActiveTimeline().getInstants());
      int numDeltaCommits = timeline.countInstants();
      assertTrue(minExpected <= numDeltaCommits, "Got=" + numDeltaCommits + ", exp >=" + minExpected);
    }

    static void assertPendingIndexCommit(String tablePath, FileSystem fs) {
      HoodieTableMetaClient meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tablePath).setLoadActiveTimelineOnLoad(true).build();
      HoodieTimeline timeline = meta.getActiveTimeline().getAllCommitsTimeline().filterPendingIndexTimeline();
      LOG.info("Timeline Instants=" + meta.getActiveTimeline().getInstants());
      int numIndexCommits = timeline.countInstants();
      assertEquals(1, numIndexCommits, "Got=" + numIndexCommits + ", exp=1");
    }

    static void assertCompletedIndexCommit(String tablePath, FileSystem fs) {
      HoodieTableMetaClient meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tablePath).setLoadActiveTimelineOnLoad(true).build();
      HoodieTimeline timeline = meta.getActiveTimeline().getAllCommitsTimeline().filterCompletedIndexTimeline();
      LOG.info("Timeline Instants=" + meta.getActiveTimeline().getInstants());
      int numIndexCommits = timeline.countInstants();
      assertEquals(1, numIndexCommits, "Got=" + numIndexCommits + ", exp=1");
    }

    static void assertNoReplaceCommits(String tablePath, FileSystem fs) {
      HoodieTableMetaClient meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tablePath).setLoadActiveTimelineOnLoad(true).build();
      HoodieTimeline timeline = meta.getActiveTimeline().getCompletedReplaceTimeline();
      LOG.info("Timeline Instants=" + meta.getActiveTimeline().getInstants());
      int numDeltaCommits = timeline.countInstants();
      assertEquals(0, numDeltaCommits, "Got=" + numDeltaCommits + ", exp =" + 0);
    }

    static void assertAtLeastNReplaceRequests(int minExpected, String tablePath, FileSystem fs) {
      HoodieTableMetaClient meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tablePath).setLoadActiveTimelineOnLoad(true).build();
      HoodieTimeline timeline = meta.getActiveTimeline().filterPendingReplaceTimeline();
      LOG.info("Timeline Instants=" + meta.getActiveTimeline().getInstants());
      int numDeltaCommits = timeline.countInstants();
      assertTrue(minExpected <= numDeltaCommits, "Got=" + numDeltaCommits + ", exp >=" + minExpected);
    }
  }

  @Test
  public void testProps() {
    TypedProperties props =
        new DFSPropertiesConfiguration(fs.getConf(), new Path(basePath + "/" + PROPS_FILENAME_TEST_SOURCE)).getProps();
    assertEquals(2, props.getInteger("hoodie.upsert.shuffle.parallelism"));
    assertEquals("_row_key", props.getString("hoodie.datasource.write.recordkey.field"));
    assertEquals("org.apache.hudi.utilities.deltastreamer.TestHoodieDeltaStreamer$TestGenerator",
        props.getString("hoodie.datasource.write.keygenerator.class"));
  }

  private static HoodieDeltaStreamer.Config getBaseConfig() {
    // Base config with all required fields
    HoodieDeltaStreamer.Config base = new HoodieDeltaStreamer.Config();
    base.targetBasePath = TGT_BASE_PATH_VALUE;
    base.tableType = TABLE_TYPE_VALUE;
    base.targetTableName = TARGET_TABLE_VALUE;
    return base;
  }

  /**
   * args for schema evolution test.
   *
   * @return
   */
  private static Stream<Arguments> schemaEvolArgs() {
    return Stream.of(
        Arguments.of(DataSourceWriteOptions.COW_TABLE_TYPE_OPT_VAL(), true, true, HoodieRecordType.AVRO),
        Arguments.of(DataSourceWriteOptions.COW_TABLE_TYPE_OPT_VAL(), true, false, HoodieRecordType.AVRO),
        Arguments.of(DataSourceWriteOptions.COW_TABLE_TYPE_OPT_VAL(), false, true, HoodieRecordType.AVRO),
        Arguments.of(DataSourceWriteOptions.COW_TABLE_TYPE_OPT_VAL(), false, false, HoodieRecordType.AVRO),
        Arguments.of(DataSourceWriteOptions.MOR_TABLE_TYPE_OPT_VAL(), true, true, HoodieRecordType.AVRO),
        Arguments.of(DataSourceWriteOptions.MOR_TABLE_TYPE_OPT_VAL(), true, false, HoodieRecordType.AVRO),
        Arguments.of(DataSourceWriteOptions.MOR_TABLE_TYPE_OPT_VAL(), false, true, HoodieRecordType.AVRO),
        Arguments.of(DataSourceWriteOptions.MOR_TABLE_TYPE_OPT_VAL(), false, false, HoodieRecordType.AVRO),

        Arguments.of(DataSourceWriteOptions.COW_TABLE_TYPE_OPT_VAL(), true, true, HoodieRecordType.SPARK),
        Arguments.of(DataSourceWriteOptions.COW_TABLE_TYPE_OPT_VAL(), true, false, HoodieRecordType.SPARK),
        Arguments.of(DataSourceWriteOptions.COW_TABLE_TYPE_OPT_VAL(), false, true, HoodieRecordType.SPARK),
        Arguments.of(DataSourceWriteOptions.COW_TABLE_TYPE_OPT_VAL(), false, false, HoodieRecordType.SPARK),
        Arguments.of(DataSourceWriteOptions.MOR_TABLE_TYPE_OPT_VAL(), true, true, HoodieRecordType.SPARK),
        Arguments.of(DataSourceWriteOptions.MOR_TABLE_TYPE_OPT_VAL(), true, false, HoodieRecordType.SPARK),
        Arguments.of(DataSourceWriteOptions.MOR_TABLE_TYPE_OPT_VAL(), false, true, HoodieRecordType.SPARK),
        Arguments.of(DataSourceWriteOptions.MOR_TABLE_TYPE_OPT_VAL(), false, false, HoodieRecordType.SPARK));
  }

  private static Stream<Arguments> provideValidCliArgs() {

    HoodieDeltaStreamer.Config base = getBaseConfig();
    // String parameter
    HoodieDeltaStreamer.Config conf1 = getBaseConfig();
    conf1.baseFileFormat = BASE_FILE_FORMAT_VALUE;

    // Integer parameter
    HoodieDeltaStreamer.Config conf2 = getBaseConfig();
    conf2.sourceLimit = Long.parseLong(SOURCE_LIMIT_VALUE);

    // Boolean Parameter
    HoodieDeltaStreamer.Config conf3 = getBaseConfig();
    conf3.enableHiveSync = true;

    // ArrayList Parameter with 1 value
    HoodieDeltaStreamer.Config conf4 = getBaseConfig();
    conf4.configs = Arrays.asList(HOODIE_CONF_VALUE1);

    // ArrayList Parameter with comma separated values
    HoodieDeltaStreamer.Config conf5 = getBaseConfig();
    conf5.configs = Arrays.asList(HOODIE_CONF_VALUE2);

    // Multiple ArrayList values
    HoodieDeltaStreamer.Config conf6 = getBaseConfig();
    conf6.configs = Arrays.asList(HOODIE_CONF_VALUE1, HOODIE_CONF_VALUE2);

    // Super set of all cases
    HoodieDeltaStreamer.Config conf = getBaseConfig();
    conf.baseFileFormat = BASE_FILE_FORMAT_VALUE;
    conf.sourceLimit = Long.parseLong(SOURCE_LIMIT_VALUE);
    conf.enableHiveSync = true;
    conf.configs = Arrays.asList(HOODIE_CONF_VALUE1, HOODIE_CONF_VALUE2);

    String[] allConfig = new String[] {TGT_BASE_PATH_PARAM, TGT_BASE_PATH_VALUE, SOURCE_LIMIT_PARAM,
        SOURCE_LIMIT_VALUE, TABLE_TYPE_PARAM, TABLE_TYPE_VALUE, TARGET_TABLE_PARAM, TARGET_TABLE_VALUE,
        BASE_FILE_FORMAT_PARAM, BASE_FILE_FORMAT_VALUE, ENABLE_HIVE_SYNC_PARAM, HOODIE_CONF_PARAM, HOODIE_CONF_VALUE1,
        HOODIE_CONF_PARAM, HOODIE_CONF_VALUE2};

    return Stream.of(
        // Base
        Arguments.of(new String[] {TGT_BASE_PATH_PARAM, TGT_BASE_PATH_VALUE,
            TABLE_TYPE_PARAM, TABLE_TYPE_VALUE, TARGET_TABLE_PARAM, TARGET_TABLE_VALUE}, base),
        // String
        Arguments.of(new String[] {TGT_BASE_PATH_PARAM, TGT_BASE_PATH_VALUE,
            TABLE_TYPE_PARAM, TABLE_TYPE_VALUE, TARGET_TABLE_PARAM, TARGET_TABLE_VALUE,
            BASE_FILE_FORMAT_PARAM, BASE_FILE_FORMAT_VALUE}, conf1),
        // Integer
        Arguments.of(new String[] {TGT_BASE_PATH_PARAM, TGT_BASE_PATH_VALUE,
            TABLE_TYPE_PARAM, TABLE_TYPE_VALUE, TARGET_TABLE_PARAM, TARGET_TABLE_VALUE,
            SOURCE_LIMIT_PARAM, SOURCE_LIMIT_VALUE}, conf2),
        // Boolean
        Arguments.of(new String[] {TGT_BASE_PATH_PARAM, TGT_BASE_PATH_VALUE,
            TABLE_TYPE_PARAM, TABLE_TYPE_VALUE, TARGET_TABLE_PARAM, TARGET_TABLE_VALUE,
            ENABLE_HIVE_SYNC_PARAM}, conf3),
        // Array List 1
        Arguments.of(new String[] {TGT_BASE_PATH_PARAM, TGT_BASE_PATH_VALUE,
            TABLE_TYPE_PARAM, TABLE_TYPE_VALUE, TARGET_TABLE_PARAM, TARGET_TABLE_VALUE,
            HOODIE_CONF_PARAM, HOODIE_CONF_VALUE1}, conf4),
        // Array List with comma
        Arguments.of(new String[] {TGT_BASE_PATH_PARAM, TGT_BASE_PATH_VALUE,
            TABLE_TYPE_PARAM, TABLE_TYPE_VALUE, TARGET_TABLE_PARAM, TARGET_TABLE_VALUE,
            HOODIE_CONF_PARAM, HOODIE_CONF_VALUE2}, conf5),
        // Array list with multiple values
        Arguments.of(new String[] {TGT_BASE_PATH_PARAM, TGT_BASE_PATH_VALUE,
            TABLE_TYPE_PARAM, TABLE_TYPE_VALUE, TARGET_TABLE_PARAM, TARGET_TABLE_VALUE,
            HOODIE_CONF_PARAM, HOODIE_CONF_VALUE1, HOODIE_CONF_PARAM, HOODIE_CONF_VALUE2}, conf6),
        // All
        Arguments.of(allConfig, conf)
    );
  }

  @ParameterizedTest
  @MethodSource("provideValidCliArgs")
  public void testValidCommandLineArgs(String[] args, HoodieDeltaStreamer.Config expected) {
    assertEquals(expected, HoodieDeltaStreamer.getConfig(args));
  }

  @Test
  public void testKafkaConnectCheckpointProvider() throws IOException {
    String tableBasePath = basePath + "/test_table";
    String bootstrapPath = basePath + "/kafka_topic1";
    String partitionPath = bootstrapPath + "/year=2016/month=05/day=01";
    String filePath = partitionPath + "/kafka_topic1+0+100+200.parquet";
    String checkpointProviderClass = "org.apache.hudi.utilities.checkpointing.KafkaConnectHdfsProvider";
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeDropAllConfig(tableBasePath, WriteOperationType.UPSERT);
    TypedProperties props =
        new DFSPropertiesConfiguration(fs.getConf(), new Path(basePath + "/" + PROPS_FILENAME_TEST_SOURCE)).getProps();
    props.put("hoodie.deltastreamer.checkpoint.provider.path", bootstrapPath);
    cfg.initialCheckpointProvider = checkpointProviderClass;
    // create regular kafka connect hdfs dirs
    fs.mkdirs(new Path(bootstrapPath));
    fs.mkdirs(new Path(partitionPath));
    // generate parquet files using kafka connect naming convention
    HoodieTestDataGenerator dataGenerator = new HoodieTestDataGenerator();
    Helpers.saveParquetToDFS(Helpers.toGenericRecords(dataGenerator.generateInserts("000", 100)), new Path(filePath));
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(cfg, jsc, fs, jsc.hadoopConfiguration(), Option.ofNullable(props));
    assertEquals("kafka_topic1,0:200", deltaStreamer.getConfig().checkpoint);
  }

  @Test
  public void testPropsWithInvalidKeyGenerator() throws Exception {
    Exception e = assertThrows(IOException.class, () -> {
      String tableBasePath = basePath + "/test_table_invalid_key_gen";
      HoodieDeltaStreamer deltaStreamer =
          new HoodieDeltaStreamer(TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT,
              Collections.singletonList(TripsWithDistanceTransformer.class.getName()), PROPS_FILENAME_TEST_INVALID, false), jsc);
      deltaStreamer.sync();
    }, "Should error out when setting the key generator class property to an invalid value");
    // expected
    LOG.debug("Expected error during getting the key generator", e);
    assertTrue(e.getMessage().contains("Could not load key generator class"));
  }

  @Test
  public void testTableCreation() throws Exception {
    Exception e = assertThrows(TableNotFoundException.class, () -> {
      fs.mkdirs(new Path(basePath + "/not_a_table"));
      HoodieDeltaStreamer deltaStreamer =
          new HoodieDeltaStreamer(TestHelpers.makeConfig(basePath + "/not_a_table", WriteOperationType.BULK_INSERT), jsc);
      deltaStreamer.sync();
    }, "Should error out when pointed out at a dir thats not a table");
    // expected
    LOG.debug("Expected error during table creation", e);
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testBulkInsertsAndUpsertsWithBootstrap(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/test_table";

    // Initial bulk insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT);
    TestHelpers.addRecordMerger(recordType, cfg.configs);
    syncAndAssertRecordCount(cfg, 1000,  tableBasePath,  "00000",  1);

    // No new data => no commits.
    cfg.sourceLimit = 0;
    syncAndAssertRecordCount(cfg, 1000,  tableBasePath,  "00000",  1);

    // upsert() #1
    cfg.sourceLimit = 2000;
    cfg.operation = WriteOperationType.UPSERT;
    syncAndAssertRecordCount(cfg,1950, tableBasePath, "00001", 2);
    List<Row> counts = TestHelpers.countsPerCommit(tableBasePath, sqlContext);
    assertEquals(1950, counts.stream().mapToLong(entry -> entry.getLong(1)).sum());

    // Perform bootstrap with tableBasePath as source
    String bootstrapSourcePath = basePath + "/src_bootstrapped";
    Dataset<Row> sourceDf = sqlContext.read()
        .format("org.apache.hudi")
        .load(tableBasePath);
    // TODO(HUDI-4944): fix the test to use a partition column with slashes (`/`) included
    //  in the value.  Currently it fails the tests due to slash encoding.
    sourceDf.write().format("parquet").partitionBy("rider").save(bootstrapSourcePath);

    String newDatasetBasePath = basePath + "/test_dataset_bootstrapped";
    cfg.runBootstrap = true;
    cfg.configs.add(String.format("hoodie.bootstrap.base.path=%s", bootstrapSourcePath));
    cfg.configs.add(String.format("%s=%s", DataSourceWriteOptions.PARTITIONPATH_FIELD().key(), "rider"));
    cfg.configs.add(String.format("hoodie.bootstrap.keygen.class=%s", SimpleKeyGenerator.class.getName()));
    cfg.configs.add("hoodie.datasource.write.hive_style_partitioning=true");
    cfg.configs.add("hoodie.bootstrap.parallelism=5");
    cfg.targetBasePath = newDatasetBasePath;
    new HoodieDeltaStreamer(cfg, jsc).sync();
    Dataset<Row> res = sqlContext.read().format("org.apache.hudi").load(newDatasetBasePath);
    LOG.info("Schema :");
    res.printSchema();

    TestHelpers.assertRecordCount(1950, newDatasetBasePath, sqlContext);
    res.registerTempTable("bootstrapped");
    assertEquals(1950, sqlContext.sql("select distinct _hoodie_record_key from bootstrapped").count());
    // NOTE: To fetch record's count Spark will optimize the query fetching minimal possible amount
    //       of data, which might not provide adequate amount of test coverage
    sqlContext.sql("select * from bootstrapped").show();

    StructField[] fields = res.schema().fields();
    List<String> fieldNames = Arrays.asList(res.schema().fieldNames());
    List<String> expectedFieldNames = Arrays.asList(sourceDf.schema().fieldNames());
    assertEquals(expectedFieldNames.size(), fields.length);
    assertTrue(fieldNames.containsAll(HoodieRecord.HOODIE_META_COLUMNS));
    assertTrue(fieldNames.containsAll(expectedFieldNames));
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, bootstrapSourcePath);
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, newDatasetBasePath);
  }

  @Test
  public void testModifiedTableConfigs() throws Exception {
    String tableBasePath = basePath + "/test_table_modified_configs";

    // Initial bulk insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT);
    syncAndAssertRecordCount(cfg, 1000, tableBasePath, "00000", 1);

    // No new data => no commits.
    cfg.sourceLimit = 0;
    syncAndAssertRecordCount(cfg, 1000, tableBasePath, "00000", 1);

    // add disallowed config update to recordkey field. An exception should be thrown
    cfg.sourceLimit = 2000;
    cfg.operation = WriteOperationType.UPSERT;
    cfg.configs.add(HoodieTableConfig.RECORDKEY_FIELDS.key() + "=differentval");
    assertThrows(HoodieException.class, () -> syncAndAssertRecordCount(cfg,1000,tableBasePath,"00000",1));
    List<Row> counts = TestHelpers.countsPerCommit(tableBasePath, sqlContext);
    assertEquals(1000, counts.stream().mapToLong(entry -> entry.getLong(1)).sum());


    //perform the upsert and now with the original config, the commit should go through
    HoodieDeltaStreamer.Config newCfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT);
    newCfg.sourceLimit = 2000;
    newCfg.operation = WriteOperationType.UPSERT;
    syncAndAssertRecordCount(newCfg, 1950, tableBasePath, "00001", 2);
    List<Row> counts2 = TestHelpers.countsPerCommit(tableBasePath, sqlContext);
    assertEquals(1950, counts2.stream().mapToLong(entry -> entry.getLong(1)).sum());
  }

  private void syncAndAssertRecordCount(HoodieDeltaStreamer.Config cfg, Integer expected, String tableBasePath, String metadata, Integer totalCommits) throws Exception {
    new HoodieDeltaStreamer(cfg, jsc).sync();
    TestHelpers.assertRecordCount(expected, tableBasePath, sqlContext);
    TestHelpers.assertDistanceCount(expected, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata(metadata, tableBasePath, fs, totalCommits);
  }

  // TODO add tests w/ disabled reconciliation
  @ParameterizedTest
  @MethodSource("schemaEvolArgs")
  public void testSchemaEvolution(String tableType, boolean useUserProvidedSchema, boolean useSchemaPostProcessor, HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/test_table_schema_evolution" + tableType + "_" + useUserProvidedSchema + "_" + useSchemaPostProcessor;
    defaultSchemaProviderClassName = FilebasedSchemaProvider.class.getName();
    // Insert data produced with Schema A, pass Schema A
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, Collections.singletonList(TestIdentityTransformer.class.getName()),
        PROPS_FILENAME_TEST_SOURCE, false, true, false, null, tableType);
    TestHelpers.addRecordMerger(recordType, cfg.configs);
    cfg.configs.add("hoodie.deltastreamer.schemaprovider.source.schema.file=" + basePath + "/source.avsc");
    cfg.configs.add("hoodie.deltastreamer.schemaprovider.target.schema.file=" + basePath + "/source.avsc");
    cfg.configs.add(DataSourceWriteOptions.RECONCILE_SCHEMA().key() + "=true");
    if (!useSchemaPostProcessor) {
      cfg.configs.add(SparkAvroPostProcessor.Config.SPARK_AVRO_POST_PROCESSOR_PROP_ENABLE + "=false");
    }
    new HoodieDeltaStreamer(cfg, jsc).sync();
    TestHelpers.assertRecordCount(1000, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00000", tableBasePath, fs, 1);

    // Upsert data produced with Schema B, pass Schema B
    cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT, Collections.singletonList(TripsWithEvolvedOptionalFieldTransformer.class.getName()),
        PROPS_FILENAME_TEST_SOURCE, false, true, false, null, tableType);
    TestHelpers.addRecordMerger(recordType, cfg.configs);
    cfg.configs.add("hoodie.deltastreamer.schemaprovider.source.schema.file=" + basePath + "/source.avsc");
    cfg.configs.add("hoodie.deltastreamer.schemaprovider.target.schema.file=" + basePath + "/source_evolved.avsc");
    cfg.configs.add(DataSourceWriteOptions.RECONCILE_SCHEMA().key() + "=true");
    if (!useSchemaPostProcessor) {
      cfg.configs.add(SparkAvroPostProcessor.Config.SPARK_AVRO_POST_PROCESSOR_PROP_ENABLE + "=false");
    }
    new HoodieDeltaStreamer(cfg, jsc).sync();
    // out of 1000 new records, 500 are inserts, 450 are updates and 50 are deletes.
    TestHelpers.assertRecordCount(1450, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00001", tableBasePath, fs, 2);
    List<Row> counts = TestHelpers.countsPerCommit(tableBasePath, sqlContext);
    assertEquals(1450, counts.stream().mapToLong(entry -> entry.getLong(1)).sum());

    sqlContext.read().format("org.apache.hudi").load(tableBasePath).createOrReplaceTempView("tmp_trips");
    long recordCount =
        sqlContext.sparkSession().sql("select * from tmp_trips where evoluted_optional_union_field is not NULL").count();
    assertEquals(950, recordCount);

    // Upsert data produced with Schema A, pass Schema B
    if (!useUserProvidedSchema) {
      defaultSchemaProviderClassName = TestFileBasedSchemaProviderNullTargetSchema.class.getName();
    }
    cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT, Collections.singletonList(TestIdentityTransformer.class.getName()),
        PROPS_FILENAME_TEST_SOURCE, false, true, false, null, tableType);
    TestHelpers.addRecordMerger(recordType, cfg.configs);
    cfg.configs.add("hoodie.deltastreamer.schemaprovider.source.schema.file=" + basePath + "/source.avsc");
    if (useUserProvidedSchema) {
      cfg.configs.add("hoodie.deltastreamer.schemaprovider.target.schema.file=" + basePath + "/source_evolved.avsc");
    }
    if (!useSchemaPostProcessor) {
      cfg.configs.add(SparkAvroPostProcessor.Config.SPARK_AVRO_POST_PROCESSOR_PROP_ENABLE + "=false");
    }
    cfg.configs.add(DataSourceWriteOptions.RECONCILE_SCHEMA().key() + "=true");
    new HoodieDeltaStreamer(cfg, jsc).sync();
    // again, 1000 new records, 500 are inserts, 450 are updates and 50 are deletes.
    TestHelpers.assertRecordCount(1900, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00002", tableBasePath, fs, 3);
    counts = TestHelpers.countsPerCommit(tableBasePath, sqlContext);
    assertEquals(1900, counts.stream().mapToLong(entry -> entry.getLong(1)).sum());

    TableSchemaResolver tableSchemaResolver = new TableSchemaResolver(HoodieTableMetaClient.builder().setBasePath(tableBasePath).setConf(fs.getConf()).build());
    Schema tableSchema = tableSchemaResolver.getTableAvroSchema(false);
    assertNotNull(tableSchema);

    Schema expectedSchema;
    if (!useSchemaPostProcessor) {
      expectedSchema = new Schema.Parser().parse(fs.open(new Path(basePath + "/source_evolved.avsc")));
    } else {
      expectedSchema = new Schema.Parser().parse(fs.open(new Path(basePath + "/source_evolved_post_processed.avsc")));
    }
    assertEquals(expectedSchema, tableSchema);

    // clean up and reinit
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
    UtilitiesTestBase.Helpers.deleteFileFromDfs(FSUtils.getFs(cfg.targetBasePath, jsc.hadoopConfiguration()), basePath + "/" + PROPS_FILENAME_TEST_SOURCE);
    writeCommonPropsToFile(fs, basePath);
    defaultSchemaProviderClassName = FilebasedSchemaProvider.class.getName();
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testUpsertsCOWContinuousMode(HoodieRecordType recordType) throws Exception {
    testUpsertsContinuousMode(HoodieTableType.COPY_ON_WRITE, "continuous_cow", recordType);
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testUpsertsCOW_ContinuousModeDisabled(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath  + "/non_continuous_cow";
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT);
    TestHelpers.addRecordMerger(recordType, cfg.configs);
    cfg.tableType = HoodieTableType.COPY_ON_WRITE.name();
    cfg.configs.add(String.format("%s=%s", TURN_METRICS_ON.key(), "true"));
    cfg.configs.add(String.format("%s=%s", METRICS_REPORTER_TYPE_VALUE.key(), "CONSOLE"));
    cfg.continuousMode = false;
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    ds.sync();
    TestHelpers.assertRecordCount(SQL_SOURCE_NUM_RECORDS, tableBasePath, sqlContext);
    assertFalse(Metrics.isInitialized(tableBasePath), "Metrics should be shutdown");
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO"})
  public void testUpsertsMORContinuousModeShutdownGracefully(HoodieRecordType recordType) throws Exception {
    testUpsertsContinuousMode(HoodieTableType.MERGE_ON_READ, "continuous_cow", true, recordType);
  }

  @Disabled("HUDI-5815 for investigation")
  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testUpsertsMORContinuousMode(HoodieRecordType recordType) throws Exception {
    testUpsertsContinuousMode(HoodieTableType.MERGE_ON_READ, "continuous_mor", recordType);
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testUpsertsMOR_ContinuousModeDisabled(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath  + "/non_continuous_mor";
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT);
    TestHelpers.addRecordMerger(recordType, cfg.configs);
    cfg.tableType = HoodieTableType.MERGE_ON_READ.name();
    cfg.configs.add(String.format("%s=%s", TURN_METRICS_ON.key(), "true"));
    cfg.configs.add(String.format("%s=%s", METRICS_REPORTER_TYPE_VALUE.key(), "CONSOLE"));
    cfg.continuousMode = false;
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    ds.sync();
    TestHelpers.assertRecordCount(SQL_SOURCE_NUM_RECORDS, tableBasePath, sqlContext);
    assertFalse(Metrics.isInitialized(tableBasePath), "Metrics should be shutdown");
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  private void testUpsertsContinuousMode(HoodieTableType tableType, String tempDir, HoodieRecordType recordType) throws Exception {
    testUpsertsContinuousMode(tableType, tempDir, false, recordType);
  }

  private void testUpsertsContinuousMode(HoodieTableType tableType, String tempDir, boolean testShutdownGracefully, HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/" + tempDir;
    // Keep it higher than batch-size to test continuous mode
    int totalRecords = 3000;
    // Initial bulk insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT);
    TestHelpers.addRecordMerger(recordType, cfg.configs);
    cfg.continuousMode = true;
    if (testShutdownGracefully) {
      cfg.postWriteTerminationStrategyClass = NoNewDataTerminationStrategy.class.getName();
    }
    cfg.tableType = tableType.name();
    cfg.configs.add(String.format("%s=%d", SourceConfigs.MAX_UNIQUE_RECORDS_PROP, totalRecords));
    cfg.configs.add(String.format("%s=false", HoodieCleanConfig.AUTO_CLEAN.key()));
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    deltaStreamerTestRunner(ds, cfg, (r) -> {
      if (tableType.equals(HoodieTableType.MERGE_ON_READ)) {
        TestHelpers.assertAtleastNDeltaCommits(5, tableBasePath, fs);
        TestHelpers.assertAtleastNCompactionCommits(2, tableBasePath, fs);
      } else {
        TestHelpers.assertAtleastNCompactionCommits(5, tableBasePath, fs);
      }
      TestHelpers.assertRecordCount(totalRecords, tableBasePath, sqlContext);
      TestHelpers.assertDistanceCount(totalRecords, tableBasePath, sqlContext);
      if (testShutdownGracefully) {
        TestDataSource.returnEmptyBatch = true;
      }
      return true;
    });
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  static void deltaStreamerTestRunner(HoodieDeltaStreamer ds, HoodieDeltaStreamer.Config cfg, Function<Boolean, Boolean> condition) throws Exception {
    deltaStreamerTestRunner(ds, cfg, condition, "single_ds_job");
  }

  static void deltaStreamerTestRunner(HoodieDeltaStreamer ds, HoodieDeltaStreamer.Config cfg, Function<Boolean, Boolean> condition, String jobId) throws Exception {
    Future dsFuture = Executors.newSingleThreadExecutor().submit(() -> {
      try {
        ds.sync();
      } catch (Exception ex) {
        LOG.warn("DS continuous job failed, hence not proceeding with condition check for " + jobId);
        throw new RuntimeException(ex.getMessage(), ex);
      }
    });
    TestHelpers.waitTillCondition(condition, dsFuture, 360);
    if (cfg != null && !cfg.postWriteTerminationStrategyClass.isEmpty()) {
      awaitDeltaStreamerShutdown(ds);
    } else {
      ds.shutdownGracefully();
      dsFuture.get();
    }
  }

  static void awaitDeltaStreamerShutdown(HoodieDeltaStreamer ds) throws InterruptedException {
    // await until deltastreamer shuts down on its own
    boolean shutDownRequested = false;
    int timeSoFar = 0;
    while (!shutDownRequested) {
      shutDownRequested = ds.getIngestionService().isShutdownRequested();
      Thread.sleep(500);
      timeSoFar += 500;
      if (timeSoFar > (2 * 60 * 1000)) {
        Assertions.fail("Deltastreamer should have shutdown by now");
      }
    }
    boolean shutdownComplete = false;
    while (!shutdownComplete) {
      shutdownComplete = ds.getIngestionService().isShutdown();
      Thread.sleep(500);
      timeSoFar += 500;
      if (timeSoFar > (2 * 60 * 1000)) {
        Assertions.fail("Deltastreamer should have shutdown by now");
      }
    }
  }

  static void deltaStreamerTestRunner(HoodieDeltaStreamer ds, Function<Boolean, Boolean> condition) throws Exception {
    deltaStreamerTestRunner(ds, null, condition);
  }

  @ParameterizedTest
  @CsvSource(value = {"true, AVRO", "true, SPARK", "false, AVRO", "false, SPARK"})
  public void testInlineClustering(String preserveCommitMetadata, HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/inlineClustering";
    // Keep it higher than batch-size to test continuous mode
    int totalRecords = 3000;

    // Initial bulk insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT);
    TestHelpers.addRecordMerger(recordType, cfg.configs);
    cfg.continuousMode = true;
    cfg.tableType = HoodieTableType.MERGE_ON_READ.name();
    cfg.configs.addAll(getAsyncServicesConfigs(totalRecords, "false", "true", "2", "", "", preserveCommitMetadata));
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    deltaStreamerTestRunner(ds, cfg, (r) -> {
      TestHelpers.assertAtLeastNCommits(2, tableBasePath, fs);
      TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
      return true;
    });
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @Test
  public void testDeltaSyncWithPendingClustering() throws Exception {
    String tableBasePath = basePath + "/inlineClusteringPending";
    // ingest data
    int totalRecords = 2000;
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT);
    cfg.continuousMode = false;
    cfg.tableType = HoodieTableType.COPY_ON_WRITE.name();
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    ds.sync();
    // assert ingest successful
    TestHelpers.assertAtLeastNCommits(1, tableBasePath, fs);

    // schedule a clustering job to build a clustering plan and transition to inflight
    HoodieClusteringJob clusteringJob = initialHoodieClusteringJob(tableBasePath, null, false, "schedule");
    clusteringJob.cluster(0);
    HoodieTableMetaClient meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tableBasePath).build();
    List<HoodieInstant> hoodieClusteringInstants = meta.getActiveTimeline().filterPendingReplaceTimeline().getInstants();
    HoodieInstant clusteringRequest = hoodieClusteringInstants.get(0);
    meta.getActiveTimeline().transitionReplaceRequestedToInflight(clusteringRequest, Option.empty());

    // do another ingestion with inline clustering enabled
    cfg.configs.addAll(getAsyncServicesConfigs(totalRecords, "false", "true", "2", "", ""));
    cfg.retryLastPendingInlineClusteringJob = true;
    HoodieDeltaStreamer ds2 = new HoodieDeltaStreamer(cfg, jsc);
    ds2.sync();
    String completeClusteringTimeStamp = meta.reloadActiveTimeline().getCompletedReplaceTimeline().lastInstant().get().getTimestamp();
    assertEquals(clusteringRequest.getTimestamp(), completeClusteringTimeStamp);
    TestHelpers.assertAtLeastNCommits(2, tableBasePath, fs);
    TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
  }

  @ParameterizedTest
  @CsvSource(value = {"true, AVRO", "true, SPARK", "false, AVRO", "false, SPARK"})
  public void testCleanerDeleteReplacedDataWithArchive(Boolean asyncClean, HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/cleanerDeleteReplacedDataWithArchive" + asyncClean;

    int totalRecords = 3000;

    // Step 1 : Prepare and insert data without archival and cleaner.
    // Make sure that there are 6 commits including 2 replacecommits completed.
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT);
    TestHelpers.addRecordMerger(recordType, cfg.configs);
    cfg.continuousMode = true;
    cfg.tableType = HoodieTableType.COPY_ON_WRITE.name();
    cfg.configs.addAll(getAsyncServicesConfigs(totalRecords, "false", "true", "2", "", ""));
    cfg.configs.add(String.format("%s=%s", HoodieCompactionConfig.PARQUET_SMALL_FILE_LIMIT.key(), "0"));
    cfg.configs.add(String.format("%s=%s", HoodieMetadataConfig.COMPACT_NUM_DELTA_COMMITS.key(), "1"));
    cfg.configs.add(String.format("%s=%s", HoodieWriteConfig.MARKERS_TYPE.key(), "DIRECT"));
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    deltaStreamerTestRunner(ds, cfg, (r) -> {
      TestHelpers.assertAtLeastNReplaceCommits(2, tableBasePath, fs);
      return true;
    });

    TestHelpers.assertAtLeastNCommits(6, tableBasePath, fs);
    TestHelpers.assertAtLeastNReplaceCommits(2, tableBasePath, fs);

    // Step 2 : Get the first replacecommit and extract the corresponding replaced file IDs.
    HoodieTableMetaClient meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tableBasePath).build();
    HoodieTimeline replacedTimeline = meta.reloadActiveTimeline().getCompletedReplaceTimeline();
    Option<HoodieInstant> firstReplaceHoodieInstant = replacedTimeline.nthFromLastInstant(1);
    assertTrue(firstReplaceHoodieInstant.isPresent());

    Option<byte[]> firstReplaceHoodieInstantDetails = replacedTimeline.getInstantDetails(firstReplaceHoodieInstant.get());
    HoodieReplaceCommitMetadata firstReplaceMetadata = HoodieReplaceCommitMetadata.fromBytes(firstReplaceHoodieInstantDetails.get(), HoodieReplaceCommitMetadata.class);
    Map<String, List<String>> partitionToReplaceFileIds = firstReplaceMetadata.getPartitionToReplaceFileIds();
    String partitionName = null;
    List replacedFileIDs = null;
    for (Map.Entry entry : partitionToReplaceFileIds.entrySet()) {
      partitionName = String.valueOf(entry.getKey());
      replacedFileIDs = (List) entry.getValue();
    }

    assertNotNull(partitionName);
    assertNotNull(replacedFileIDs);

    // Step 3 : Based to replacedFileIDs , get the corresponding complete path.
    ArrayList<String> replacedFilePaths = new ArrayList<>();
    Path partitionPath = new Path(meta.getBasePath(), partitionName);
    RemoteIterator<LocatedFileStatus> hoodieFiles = meta.getFs().listFiles(partitionPath, true);
    while (hoodieFiles.hasNext()) {
      LocatedFileStatus f = hoodieFiles.next();
      String file = f.getPath().toUri().toString();
      for (Object replacedFileID : replacedFileIDs) {
        if (file.contains(String.valueOf(replacedFileID))) {
          replacedFilePaths.add(file);
        }
      }
    }

    assertFalse(replacedFilePaths.isEmpty());

    // Step 4 : Insert 1 record and trigger sync/async cleaner and archive.
    List<String> configs = getAsyncServicesConfigs(1, "true", "true", "6", "", "");
    configs.add(String.format("%s=%s", HoodieCleanConfig.CLEANER_POLICY.key(), "KEEP_LATEST_COMMITS"));
    configs.add(String.format("%s=%s", HoodieCleanConfig.CLEANER_COMMITS_RETAINED.key(), "1"));
    configs.add(String.format("%s=%s", HoodieArchivalConfig.MIN_COMMITS_TO_KEEP.key(), "2"));
    configs.add(String.format("%s=%s", HoodieArchivalConfig.MAX_COMMITS_TO_KEEP.key(), "3"));
    configs.add(String.format("%s=%s", HoodieCleanConfig.ASYNC_CLEAN.key(), asyncClean));
    configs.add(String.format("%s=%s", HoodieMetadataConfig.COMPACT_NUM_DELTA_COMMITS.key(), "1"));
    configs.add(String.format("%s=%s", HoodieWriteConfig.MARKERS_TYPE.key(), "DIRECT"));
    if (asyncClean) {
      configs.add(String.format("%s=%s", HoodieWriteConfig.WRITE_CONCURRENCY_MODE.key(),
          WriteConcurrencyMode.OPTIMISTIC_CONCURRENCY_CONTROL.name()));
      configs.add(String.format("%s=%s", HoodieCleanConfig.FAILED_WRITES_CLEANER_POLICY.key(),
          HoodieFailedWritesCleaningPolicy.LAZY.name()));
      configs.add(String.format("%s=%s", HoodieLockConfig.LOCK_PROVIDER_CLASS_NAME.key(),
          InProcessLockProvider.class.getName()));
    }
    TestHelpers.addRecordMerger(recordType, configs);
    cfg.configs = configs;
    cfg.continuousMode = false;
    ds = new HoodieDeltaStreamer(cfg, jsc);
    ds.sync();

    // Step 5 : Make sure that firstReplaceHoodieInstant is archived.
    long count = meta.reloadActiveTimeline().getCompletedReplaceTimeline().getInstantsAsStream().filter(instant -> firstReplaceHoodieInstant.get().equals(instant)).count();
    assertEquals(0, count);

    // Step 6 : All the replaced files in firstReplaceHoodieInstant should be deleted through sync/async cleaner.
    for (String replacedFilePath : replacedFilePaths) {
      assertFalse(meta.getFs().exists(new Path(replacedFilePath)));
    }
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  private List<String> getAsyncServicesConfigs(int totalRecords, String autoClean, String inlineCluster, String inlineClusterMaxCommit,
                                               String asyncCluster, String asyncClusterMaxCommit, String preserveCommitMetadata) {
    List<String> configs = getAsyncServicesConfigs(totalRecords, autoClean, inlineCluster, inlineClusterMaxCommit, asyncCluster, asyncClusterMaxCommit);
    configs.add(String.format("%s=%s", HoodieClusteringConfig.PRESERVE_COMMIT_METADATA.key(), preserveCommitMetadata));
    return configs;
  }

  private List<String> getAsyncServicesConfigs(int totalRecords, String autoClean, String inlineCluster,
                                               String inlineClusterMaxCommit, String asyncCluster, String asyncClusterMaxCommit) {
    List<String> configs = new ArrayList<>();
    configs.add(String.format("%s=%d", SourceConfigs.MAX_UNIQUE_RECORDS_PROP, totalRecords));
    if (!StringUtils.isNullOrEmpty(autoClean)) {
      configs.add(String.format("%s=%s", HoodieCleanConfig.AUTO_CLEAN.key(), autoClean));
    }
    if (!StringUtils.isNullOrEmpty(inlineCluster)) {
      configs.add(String.format("%s=%s", HoodieClusteringConfig.INLINE_CLUSTERING.key(), inlineCluster));
    }
    if (!StringUtils.isNullOrEmpty(inlineClusterMaxCommit)) {
      configs.add(String.format("%s=%s", HoodieClusteringConfig.INLINE_CLUSTERING_MAX_COMMITS.key(), inlineClusterMaxCommit));
    }
    if (!StringUtils.isNullOrEmpty(asyncCluster)) {
      configs.add(String.format("%s=%s", HoodieClusteringConfig.ASYNC_CLUSTERING_ENABLE.key(), asyncCluster));
    }
    if (!StringUtils.isNullOrEmpty(asyncClusterMaxCommit)) {
      configs.add(String.format("%s=%s", HoodieClusteringConfig.ASYNC_CLUSTERING_MAX_COMMITS.key(), asyncClusterMaxCommit));
    }
    return configs;
  }

  private List<String> getAllMultiWriterConfigs() {
    List<String> configs = new ArrayList<>();
    configs.add(String.format("%s=%s", HoodieLockConfig.LOCK_PROVIDER_CLASS_NAME.key(), InProcessLockProvider.class.getCanonicalName()));
    configs.add(String.format("%s=%s", LockConfiguration.LOCK_ACQUIRE_WAIT_TIMEOUT_MS_PROP_KEY,"3000"));
    configs.add(String.format("%s=%s", HoodieWriteConfig.WRITE_CONCURRENCY_MODE.key(), WriteConcurrencyMode.OPTIMISTIC_CONCURRENCY_CONTROL.name()));
    configs.add(String.format("%s=%s", HoodieCleanConfig.FAILED_WRITES_CLEANER_POLICY.key(), HoodieFailedWritesCleaningPolicy.LAZY.name()));
    return configs;
  }

  private HoodieClusteringJob.Config buildHoodieClusteringUtilConfig(String basePath,
                                                                     String clusteringInstantTime,
                                                                     Boolean runSchedule) {
    return buildHoodieClusteringUtilConfig(basePath, clusteringInstantTime, runSchedule, null, null);
  }

  private HoodieClusteringJob.Config buildHoodieClusteringUtilConfig(String basePath,
                                                                     String clusteringInstantTime,
                                                                     Boolean runSchedule,
                                                                     String runningMode,
                                                                     Boolean retryLastFailedClusteringJob) {
    HoodieClusteringJob.Config config = new HoodieClusteringJob.Config();
    config.basePath = basePath;
    config.clusteringInstantTime = clusteringInstantTime;
    config.runSchedule = runSchedule;
    config.propsFilePath = UtilitiesTestBase.basePath + "/clusteringjob.properties";
    config.runningMode = runningMode;
    if (retryLastFailedClusteringJob != null) {
      config.retryLastFailedClusteringJob = retryLastFailedClusteringJob;
    }
    return config;
  }

  private HoodieIndexer.Config buildIndexerConfig(String basePath,
                                                  String tableName,
                                                  String indexInstantTime,
                                                  String runningMode,
                                                  String indexTypes) {
    HoodieIndexer.Config config = new HoodieIndexer.Config();
    config.basePath = basePath;
    config.tableName = tableName;
    config.indexInstantTime = indexInstantTime;
    config.propsFilePath = UtilitiesTestBase.basePath + "/indexer.properties";
    config.runningMode = runningMode;
    config.indexTypes = indexTypes;
    return config;
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testHoodieIndexer(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/asyncindexer";
    HoodieDeltaStreamer ds = initialHoodieDeltaStreamer(tableBasePath, 1000, "false", recordType);

    deltaStreamerTestRunner(ds, (r) -> {
      TestHelpers.assertAtLeastNCommits(2, tableBasePath, fs);

      Option<String> scheduleIndexInstantTime = Option.empty();
      try {
        HoodieIndexer scheduleIndexingJob = new HoodieIndexer(jsc,
            buildIndexerConfig(tableBasePath, ds.getConfig().targetTableName, null, SCHEDULE, "COLUMN_STATS"));
        scheduleIndexInstantTime = scheduleIndexingJob.doSchedule();
      } catch (Exception e) {
        LOG.info("Schedule indexing failed", e);
        return false;
      }
      if (scheduleIndexInstantTime.isPresent()) {
        TestHelpers.assertPendingIndexCommit(tableBasePath, fs);
        LOG.info("Schedule indexing success, now build index with instant time " + scheduleIndexInstantTime.get());
        HoodieIndexer runIndexingJob = new HoodieIndexer(jsc,
            buildIndexerConfig(tableBasePath, ds.getConfig().targetTableName, scheduleIndexInstantTime.get(), EXECUTE, "COLUMN_STATS"));
        runIndexingJob.start(0);
        LOG.info("Metadata indexing success");
        TestHelpers.assertCompletedIndexCommit(tableBasePath, fs);
      } else {
        LOG.warn("Metadata indexing failed");
      }
      return true;
    });
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testHoodieAsyncClusteringJob(boolean shouldPassInClusteringInstantTime) throws Exception {
    String tableBasePath = basePath + "/asyncClusteringJob";
    HoodieDeltaStreamer ds = initialHoodieDeltaStreamer(tableBasePath, 3000, "false", HoodieRecordType.AVRO);
    CountDownLatch countDownLatch = new CountDownLatch(1);

    deltaStreamerTestRunner(ds, (r) -> {
      TestHelpers.assertAtLeastNCommits(2, tableBasePath, fs);
      countDownLatch.countDown();
      return true;
    });

    if (countDownLatch.await(2, TimeUnit.MINUTES)) {
      Option<String> scheduleClusteringInstantTime = Option.empty();
      try {
        HoodieClusteringJob scheduleClusteringJob =
            initialHoodieClusteringJob(tableBasePath, null, true, null);
        scheduleClusteringInstantTime = scheduleClusteringJob.doSchedule();
      } catch (Exception e) {
        LOG.warn("Schedule clustering failed", e);
        Assertions.fail("Schedule clustering failed", e);
      }
      if (scheduleClusteringInstantTime.isPresent()) {
        LOG.info("Schedule clustering success, now cluster with instant time " + scheduleClusteringInstantTime.get());
        HoodieClusteringJob.Config clusterClusteringConfig = buildHoodieClusteringUtilConfig(tableBasePath,
            shouldPassInClusteringInstantTime ? scheduleClusteringInstantTime.get() : null, false);
        HoodieClusteringJob clusterClusteringJob = new HoodieClusteringJob(jsc, clusterClusteringConfig);
        clusterClusteringJob.cluster(clusterClusteringConfig.retry);
        TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
        LOG.info("Cluster success");
      } else {
        LOG.warn("Clustering execution failed");
        Assertions.fail("Clustering execution failed");
      }
    } else {
      Assertions.fail("Deltastreamer should have completed 2 commits.");
    }
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testAsyncClusteringService(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/asyncClustering";
    // Keep it higher than batch-size to test continuous mode
    int totalRecords = 2000;

    // Initial bulk insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT);
    TestHelpers.addRecordMerger(recordType, cfg.configs);
    cfg.continuousMode = true;
    cfg.tableType = HoodieTableType.COPY_ON_WRITE.name();
    cfg.configs.addAll(getAsyncServicesConfigs(totalRecords, "false", "", "", "true", "3"));
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    deltaStreamerTestRunner(ds, cfg, (r) -> {
      TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
      return true;
    });
    // There should be 4 commits, one of which should be a replace commit
    TestHelpers.assertAtLeastNCommits(4, tableBasePath, fs);
    TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
    TestHelpers.assertDistinctRecordCount(totalRecords, tableBasePath, sqlContext);
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  /**
   * When deltastreamer writes clashes with pending clustering, deltastreamer should keep retrying and eventually succeed(once clustering completes)
   * w/o failing mid way.
   *
   * @throws Exception
   */
  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testAsyncClusteringServiceWithConflicts(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/asyncClusteringWithConflicts";
    // Keep it higher than batch-size to test continuous mode
    int totalRecords = 2000;

    // Initial bulk insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT);
    TestHelpers.addRecordMerger(recordType, cfg.configs);
    cfg.continuousMode = true;
    cfg.tableType = HoodieTableType.COPY_ON_WRITE.name();
    cfg.configs.addAll(getAsyncServicesConfigs(totalRecords, "false", "", "", "true", "3"));
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    deltaStreamerTestRunner(ds, cfg, (r) -> {
      TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
      return true;
    });
    // There should be 4 commits, one of which should be a replace commit
    TestHelpers.assertAtLeastNCommits(4, tableBasePath, fs);
    TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
    TestHelpers.assertDistinctRecordCount(1900, tableBasePath, sqlContext);
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @Disabled("HUDI-5815 for investigation")
  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testAsyncClusteringServiceWithCompaction(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/asyncClusteringCompaction";
    // Keep it higher than batch-size to test continuous mode
    int totalRecords = 2000;

    // Initial bulk insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT);
    TestHelpers.addRecordMerger(recordType, cfg.configs);
    cfg.continuousMode = true;
    cfg.tableType = HoodieTableType.MERGE_ON_READ.name();
    cfg.configs.addAll(getAsyncServicesConfigs(totalRecords, "false", "", "", "true", "3"));
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    deltaStreamerTestRunner(ds, cfg, (r) -> {
      TestHelpers.assertAtleastNCompactionCommits(2, tableBasePath, fs);
      TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
      return true;
    });
    // There should be 4 commits, one of which should be a replace commit
    TestHelpers.assertAtLeastNCommits(4, tableBasePath, fs);
    TestHelpers.assertAtLeastNReplaceCommits(1, tableBasePath, fs);
    TestHelpers.assertDistinctRecordCount(totalRecords, tableBasePath, sqlContext);
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @ParameterizedTest
  @CsvSource(value = {"true, AVRO", "true, SPARK", "false, AVRO", "false, SPARK"})
  public void testAsyncClusteringJobWithRetry(boolean retryLastFailedClusteringJob, HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/asyncClustering3";

    // ingest data
    int totalRecords = 3000;
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT);
    TestHelpers.addRecordMerger(recordType, cfg.configs);
    cfg.continuousMode = false;
    cfg.tableType = HoodieTableType.COPY_ON_WRITE.name();
    cfg.configs.addAll(getAsyncServicesConfigs(totalRecords, "false", "false", "0", "false", "0"));
    cfg.configs.addAll(getAllMultiWriterConfigs());
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    ds.sync();

    // assert ingest successful
    TestHelpers.assertAtLeastNCommits(1, tableBasePath, fs);

    // schedule a clustering job to build a clustering plan
    HoodieClusteringJob schedule = initialHoodieClusteringJob(tableBasePath, null, false, "schedule");
    schedule.cluster(0);

    // do another ingestion
    HoodieDeltaStreamer ds2 = new HoodieDeltaStreamer(cfg, jsc);
    ds2.sync();

    // convert clustering request into inflight, Simulate the last clustering failed scenario
    HoodieTableMetaClient meta = HoodieTableMetaClient.builder().setConf(fs.getConf()).setBasePath(tableBasePath).build();
    List<HoodieInstant> hoodieClusteringInstants = meta.getActiveTimeline().filterPendingReplaceTimeline().getInstants();
    HoodieInstant clusteringRequest = hoodieClusteringInstants.get(0);
    HoodieInstant hoodieInflightInstant = meta.getActiveTimeline().transitionReplaceRequestedToInflight(clusteringRequest, Option.empty());

    // trigger a scheduleAndExecute clustering job
    // when retryFailedClustering true => will rollback and re-execute failed clustering plan with same instant timestamp.
    // when retryFailedClustering false => will make and execute a new clustering plan with new instant timestamp.
    HoodieClusteringJob scheduleAndExecute = initialHoodieClusteringJob(tableBasePath, null, false, "scheduleAndExecute", retryLastFailedClusteringJob, recordType);
    scheduleAndExecute.cluster(0);

    String completeClusteringTimeStamp = meta.getActiveTimeline().reload().getCompletedReplaceTimeline().lastInstant().get().getTimestamp();

    if (retryLastFailedClusteringJob) {
      assertEquals(clusteringRequest.getTimestamp(), completeClusteringTimeStamp);
    } else {
      assertFalse(clusteringRequest.getTimestamp().equalsIgnoreCase(completeClusteringTimeStamp));
    }
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @Disabled("HUDI-5815 for investigation")
  @ParameterizedTest
  @CsvSource(value = {"execute, AVRO", "schedule, AVRO", "scheduleAndExecute, AVRO", "execute, SPARK", "schedule, SPARK", "scheduleAndExecute, SPARK"})
  public void testHoodieAsyncClusteringJobWithScheduleAndExecute(String runningMode, HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/asyncClustering2";
    HoodieDeltaStreamer ds = initialHoodieDeltaStreamer(tableBasePath, 3000, "false", recordType, WriteOperationType.BULK_INSERT);
    HoodieClusteringJob scheduleClusteringJob = initialHoodieClusteringJob(tableBasePath, null, true, runningMode, recordType);

    deltaStreamerTestRunner(ds, (r) -> {
      Exception exception = null;
      TestHelpers.assertAtLeastNCommits(2, tableBasePath, fs);
      try {
        int result = scheduleClusteringJob.cluster(0);
        if (result == 0) {
          LOG.info("Cluster success");
        } else {
          LOG.warn("Cluster failed");
          if (!runningMode.toLowerCase().equals(EXECUTE)) {
            return false;
          }
        }
      } catch (Exception e) {
        LOG.warn("ScheduleAndExecute clustering failed", e);
        exception = e;
        if (!runningMode.equalsIgnoreCase(EXECUTE)) {
          return false;
        }
      }
      switch (runningMode.toLowerCase()) {
        case SCHEDULE_AND_EXECUTE: {
          TestHelpers.assertAtLeastNReplaceCommits(2, tableBasePath, fs);
          return true;
        }
        case SCHEDULE: {
          TestHelpers.assertAtLeastNReplaceRequests(2, tableBasePath, fs);
          TestHelpers.assertNoReplaceCommits(tableBasePath, fs);
          return true;
        }
        case EXECUTE: {
          TestHelpers.assertNoReplaceCommits(tableBasePath, fs);
          return true;
        }
        default:
          throw new IllegalStateException("Unexpected value: " + runningMode);
      }
    });
    if (runningMode.toLowerCase(Locale.ROOT).equals(SCHEDULE_AND_EXECUTE)) {
      UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
    }
  }

  /**
   * Test Bulk Insert and upserts with hive syncing. Tests Hudi incremental processing using a 2 step pipeline The first
   * step involves using a SQL template to transform a source TEST-DATA-SOURCE ============================> HUDI TABLE
   * 1 ===============> HUDI TABLE 2 (incr-pull with transform) (incr-pull) Hudi Table 1 is synced with Hive.
   */
  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testBulkInsertsAndUpsertsWithSQLBasedTransformerFor2StepPipeline(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/test_table2";
    String downstreamTableBasePath = basePath + "/test_downstream_table2";

    // Initial bulk insert to ingest to first hudi table
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT,
        Collections.singletonList(SqlQueryBasedTransformer.class.getName()), PROPS_FILENAME_TEST_SOURCE, true);
    TestHelpers.addRecordMerger(recordType, cfg.configs);
    // NOTE: We should not have need to set below config, 'datestr' should have assumed date partitioning
    cfg.configs.add("hoodie.datasource.hive_sync.partition_fields=year,month,day");
    new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf()).sync();
    TestHelpers.assertRecordCount(1000, tableBasePath, sqlContext);
    TestHelpers.assertDistanceCount(1000, tableBasePath, sqlContext);
    TestHelpers.assertDistanceCountWithExactValue(1000, tableBasePath, sqlContext);
    String lastInstantForUpstreamTable = TestHelpers.assertCommitMetadata("00000", tableBasePath, fs, 1);

    // Now incrementally pull from the above hudi table and ingest to second table
    HoodieDeltaStreamer.Config downstreamCfg =
        TestHelpers.makeConfigForHudiIncrSrc(tableBasePath, downstreamTableBasePath, WriteOperationType.BULK_INSERT,
            true, null);
    TestHelpers.addRecordMerger(recordType, downstreamCfg.configs);
    new HoodieDeltaStreamer(downstreamCfg, jsc, fs, hiveServer.getHiveConf()).sync();
    TestHelpers.assertRecordCount(1000, downstreamTableBasePath, sqlContext);
    TestHelpers.assertDistanceCount(1000, downstreamTableBasePath, sqlContext);
    TestHelpers.assertDistanceCountWithExactValue(1000, downstreamTableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata(lastInstantForUpstreamTable, downstreamTableBasePath, fs, 1);

    // No new data => no commits for upstream table
    cfg.sourceLimit = 0;
    new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf()).sync();
    TestHelpers.assertRecordCount(1000, tableBasePath, sqlContext);
    TestHelpers.assertDistanceCount(1000, tableBasePath, sqlContext);
    TestHelpers.assertDistanceCountWithExactValue(1000, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00000", tableBasePath, fs, 1);

    // with no change in upstream table, no change in downstream too when pulled.
    HoodieDeltaStreamer.Config downstreamCfg1 =
        TestHelpers.makeConfigForHudiIncrSrc(tableBasePath, downstreamTableBasePath,
            WriteOperationType.BULK_INSERT, true, DummySchemaProvider.class.getName());
    new HoodieDeltaStreamer(downstreamCfg1, jsc).sync();
    TestHelpers.assertRecordCount(1000, downstreamTableBasePath, sqlContext);
    TestHelpers.assertDistanceCount(1000, downstreamTableBasePath, sqlContext);
    TestHelpers.assertDistanceCountWithExactValue(1000, downstreamTableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata(lastInstantForUpstreamTable, downstreamTableBasePath, fs, 1);

    // upsert() #1 on upstream hudi table
    cfg.sourceLimit = 2000;
    cfg.operation = WriteOperationType.UPSERT;
    new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf()).sync();
    TestHelpers.assertRecordCount(1950, tableBasePath, sqlContext);
    TestHelpers.assertDistanceCount(1950, tableBasePath, sqlContext);
    TestHelpers.assertDistanceCountWithExactValue(1950, tableBasePath, sqlContext);
    lastInstantForUpstreamTable = TestHelpers.assertCommitMetadata("00001", tableBasePath, fs, 2);
    List<Row> counts = TestHelpers.countsPerCommit(tableBasePath, sqlContext);
    assertEquals(1950, counts.stream().mapToLong(entry -> entry.getLong(1)).sum());

    // Incrementally pull changes in upstream hudi table and apply to downstream table
    downstreamCfg =
        TestHelpers.makeConfigForHudiIncrSrc(tableBasePath, downstreamTableBasePath, WriteOperationType.UPSERT,
            false, null);
    TestHelpers.addRecordMerger(recordType, downstreamCfg.configs);
    downstreamCfg.sourceLimit = 2000;
    new HoodieDeltaStreamer(downstreamCfg, jsc).sync();
    TestHelpers.assertRecordCount(2000, downstreamTableBasePath, sqlContext);
    TestHelpers.assertDistanceCount(2000, downstreamTableBasePath, sqlContext);
    TestHelpers.assertDistanceCountWithExactValue(2000, downstreamTableBasePath, sqlContext);
    String finalInstant =
        TestHelpers.assertCommitMetadata(lastInstantForUpstreamTable, downstreamTableBasePath, fs, 2);
    counts = TestHelpers.countsPerCommit(downstreamTableBasePath, sqlContext);
    assertEquals(2000, counts.stream().mapToLong(entry -> entry.getLong(1)).sum());

    // Test Hive integration
    HiveSyncConfig hiveSyncConfig = getHiveSyncConfig(tableBasePath, "hive_trips");
    hiveSyncConfig.setValue(META_SYNC_PARTITION_FIELDS, "year,month,day");
    hiveSyncConfig.setHadoopConf(hiveTestService.getHiveConf());
    HoodieHiveSyncClient hiveClient = new HoodieHiveSyncClient(hiveSyncConfig);
    final String tableName = hiveSyncConfig.getString(META_SYNC_TABLE_NAME);
    assertTrue(hiveClient.tableExists(tableName), "Table " + tableName + " should exist");
    assertEquals(3, hiveClient.getAllPartitions(tableName).size(),
        "Table partitions should match the number of partitions we wrote");
    assertEquals(lastInstantForUpstreamTable,
        hiveClient.getLastCommitTimeSynced(tableName).get(),
        "The last commit that was synced should be updated in the TBLPROPERTIES");
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, downstreamTableBasePath);
  }

  @Test
  public void testNullSchemaProvider() throws Exception {
    String tableBasePath = basePath + "/test_table";
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT,
        Collections.singletonList(SqlQueryBasedTransformer.class.getName()), PROPS_FILENAME_TEST_SOURCE, true,
        false, false, null, null);
    Exception e = assertThrows(HoodieException.class, () -> {
      new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf()).sync();
    }, "Should error out when schema provider is not provided");
    LOG.debug("Expected error during reading data from source ", e);
    assertTrue(e.getMessage().contains("Please provide a valid schema provider class!"));
  }

  @Test
  public void testPayloadClassUpdate() throws Exception {
    String dataSetBasePath = basePath + "/test_dataset_mor_payload_class_update";
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(dataSetBasePath, WriteOperationType.BULK_INSERT,
        Collections.singletonList(SqlQueryBasedTransformer.class.getName()), PROPS_FILENAME_TEST_SOURCE, false,
        true, false, null, "MERGE_ON_READ");
    new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf()).sync();
    TestHelpers.assertRecordCount(1000, dataSetBasePath, sqlContext);

    //now create one more deltaStreamer instance and update payload class
    cfg = TestHelpers.makeConfig(dataSetBasePath, WriteOperationType.BULK_INSERT,
        Collections.singletonList(SqlQueryBasedTransformer.class.getName()), PROPS_FILENAME_TEST_SOURCE, false,
        true, true, DummyAvroPayload.class.getName(), "MERGE_ON_READ");
    new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf());

    //now assert that hoodie.properties file now has updated payload class name
    Properties props = new Properties();
    String metaPath = dataSetBasePath + "/.hoodie/hoodie.properties";
    FileSystem fs = FSUtils.getFs(cfg.targetBasePath, jsc.hadoopConfiguration());
    try (FSDataInputStream inputStream = fs.open(new Path(metaPath))) {
      props.load(inputStream);
    }

    assertEquals(new HoodieConfig(props).getString(HoodieTableConfig.PAYLOAD_CLASS_NAME), DummyAvroPayload.class.getName());
  }

  @Test
  public void testPartialPayloadClass() throws Exception {
    String dataSetBasePath = basePath + "/test_dataset_mor";
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(dataSetBasePath, WriteOperationType.BULK_INSERT,
          Collections.singletonList(SqlQueryBasedTransformer.class.getName()), PROPS_FILENAME_TEST_SOURCE, false,
          true, true, PartialUpdateAvroPayload.class.getName(), "MERGE_ON_READ");
    new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf()).sync();
    TestHelpers.assertRecordCount(1000, dataSetBasePath, sqlContext);

    //now assert that hoodie.properties file now has updated payload class name
    Properties props = new Properties();
    String metaPath = dataSetBasePath + "/.hoodie/hoodie.properties";
    FileSystem fs = FSUtils.getFs(cfg.targetBasePath, jsc.hadoopConfiguration());
    try (FSDataInputStream inputStream = fs.open(new Path(metaPath))) {
      props.load(inputStream);
    }
    assertEquals(new HoodieConfig(props).getString(HoodieTableConfig.PAYLOAD_CLASS_NAME), PartialUpdateAvroPayload.class.getName());
  }

  @Test
  public void testPayloadClassUpdateWithCOWTable() throws Exception {
    String dataSetBasePath = basePath + "/test_dataset_cow";
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(dataSetBasePath, WriteOperationType.BULK_INSERT,
        Collections.singletonList(SqlQueryBasedTransformer.class.getName()), PROPS_FILENAME_TEST_SOURCE, false,
        true, false, null, null);
    new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf()).sync();
    TestHelpers.assertRecordCount(1000, dataSetBasePath, sqlContext);

    //now create one more deltaStreamer instance and update payload class
    cfg = TestHelpers.makeConfig(dataSetBasePath, WriteOperationType.BULK_INSERT,
        Collections.singletonList(SqlQueryBasedTransformer.class.getName()), PROPS_FILENAME_TEST_SOURCE, false,
        true, true, DummyAvroPayload.class.getName(), null);
    new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf());

    //now assert that hoodie.properties file does not have payload class prop since it is a COW table
    Properties props = new Properties();
    String metaPath = dataSetBasePath + "/.hoodie/hoodie.properties";
    FileSystem fs = FSUtils.getFs(cfg.targetBasePath, jsc.hadoopConfiguration());
    try (FSDataInputStream inputStream = fs.open(new Path(metaPath))) {
      props.load(inputStream);
    }

    assertFalse(props.containsKey(HoodieTableConfig.PAYLOAD_CLASS_NAME.key()));
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testFilterDupes(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/test_dupes_table";

    // Initial bulk insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT);
    TestHelpers.addRecordMerger(recordType, cfg.configs);
    new HoodieDeltaStreamer(cfg, jsc).sync();
    TestHelpers.assertRecordCount(1000, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00000", tableBasePath, fs, 1);

    // Generate the same 1000 records + 1000 new ones for upsert
    cfg.filterDupes = true;
    cfg.sourceLimit = 2000;
    cfg.operation = WriteOperationType.INSERT;
    new HoodieDeltaStreamer(cfg, jsc).sync();
    TestHelpers.assertRecordCount(2000, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00001", tableBasePath, fs, 2);
    // 1000 records for commit 00000 & 1000 for commit 00001
    List<Row> counts = TestHelpers.countsPerCommit(tableBasePath, sqlContext);
    assertEquals(1000, counts.get(0).getLong(1));
    assertEquals(1000, counts.get(1).getLong(1));

    // Test with empty commits
    HoodieTableMetaClient mClient = HoodieTableMetaClient.builder().setConf(jsc.hadoopConfiguration()).setBasePath(tableBasePath).setLoadActiveTimelineOnLoad(true).build();
    HoodieInstant lastFinished = mClient.getCommitsTimeline().filterCompletedInstants().lastInstant().get();
    HoodieDeltaStreamer.Config cfg2 = TestHelpers.makeDropAllConfig(tableBasePath, WriteOperationType.UPSERT);
    TestHelpers.addRecordMerger(recordType, cfg2.configs);
    cfg2.filterDupes = false;
    cfg2.sourceLimit = 2000;
    cfg2.operation = WriteOperationType.UPSERT;
    cfg2.configs.add(String.format("%s=false", HoodieCleanConfig.AUTO_CLEAN.key()));
    HoodieDeltaStreamer ds2 = new HoodieDeltaStreamer(cfg2, jsc);
    ds2.sync();
    mClient = HoodieTableMetaClient.builder().setConf(jsc.hadoopConfiguration()).setBasePath(tableBasePath).setLoadActiveTimelineOnLoad(true).build();
    HoodieInstant newLastFinished = mClient.getCommitsTimeline().filterCompletedInstants().lastInstant().get();
    assertTrue(HoodieTimeline.compareTimestamps(newLastFinished.getTimestamp(), HoodieTimeline.GREATER_THAN, lastFinished.getTimestamp()
    ));

    // Ensure it is empty
    HoodieCommitMetadata commitMetadata = HoodieCommitMetadata
        .fromBytes(mClient.getActiveTimeline().getInstantDetails(newLastFinished).get(), HoodieCommitMetadata.class);
    System.out.println("New Commit Metadata=" + commitMetadata);
    assertTrue(commitMetadata.getPartitionToWriteStats().isEmpty());

    // Try UPSERT with filterDupes true. Expect exception
    cfg2.filterDupes = true;
    cfg2.operation = WriteOperationType.UPSERT;
    try {
      new HoodieDeltaStreamer(cfg2, jsc).sync();
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("'--filter-dupes' needs to be disabled when '--op' is 'UPSERT' to ensure updates are not missed."));
    }
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @Test
  public void testDistributedTestDataSource() {
    TypedProperties props = new TypedProperties();
    props.setProperty(SourceConfigs.MAX_UNIQUE_RECORDS_PROP, "1000");
    props.setProperty(SourceConfigs.NUM_SOURCE_PARTITIONS_PROP, "1");
    props.setProperty(SourceConfigs.USE_ROCKSDB_FOR_TEST_DATAGEN_KEYS, "true");
    DistributedTestDataSource distributedTestDataSource = new DistributedTestDataSource(props, jsc, sparkSession, null);
    InputBatch<JavaRDD<GenericRecord>> batch = distributedTestDataSource.fetchNext(Option.empty(), 10000000);
    batch.getBatch().get().cache();
    long c = batch.getBatch().get().count();
    assertEquals(1000, c);
  }

  private static void prepareJsonKafkaDFSFiles(int numRecords, boolean createTopic, String topicName) {
    prepareJsonKafkaDFSFiles(numRecords, createTopic, topicName, 2);
  }

  private static void prepareJsonKafkaDFSFiles(int numRecords, boolean createTopic, String topicName, int numPartitions) {
    if (createTopic) {
      try {
        testUtils.createTopic(topicName, numPartitions);
      } catch (TopicExistsException e) {
        // no op
      }
    }
    HoodieTestDataGenerator dataGenerator = new HoodieTestDataGenerator();
    testUtils.sendMessages(topicName, Helpers.jsonifyRecords(dataGenerator.generateInsertsAsPerSchema("000", numRecords, HoodieTestDataGenerator.TRIP_SCHEMA)));
  }

  private void prepareParquetDFSSource(boolean useSchemaProvider, boolean hasTransformer, String emptyBatchParam) throws IOException {
    prepareParquetDFSSource(useSchemaProvider, hasTransformer, "source.avsc", "target.avsc",
        PROPS_FILENAME_TEST_PARQUET, PARQUET_SOURCE_ROOT, false, "partition_path", emptyBatchParam);
  }

  private void prepareParquetDFSSource(boolean useSchemaProvider, boolean hasTransformer) throws IOException {
    prepareParquetDFSSource(useSchemaProvider, hasTransformer, "");
  }

  private void prepareParquetDFSSource(boolean useSchemaProvider, boolean hasTransformer, String sourceSchemaFile, String targetSchemaFile,
                                       String propsFileName, String parquetSourceRoot, boolean addCommonProps, String partitionPath) throws IOException {
    prepareParquetDFSSource(useSchemaProvider, hasTransformer, sourceSchemaFile, targetSchemaFile, propsFileName, parquetSourceRoot, addCommonProps,
        partitionPath, "");
  }

  private void prepareParquetDFSSource(boolean useSchemaProvider, boolean hasTransformer, String sourceSchemaFile, String targetSchemaFile,
                                       String propsFileName, String parquetSourceRoot, boolean addCommonProps,
                                       String partitionPath, String emptyBatchParam) throws IOException {
    // Properties used for testing delta-streamer with Parquet source
    TypedProperties parquetProps = new TypedProperties();

    if (addCommonProps) {
      populateCommonProps(parquetProps, basePath);
    }

    parquetProps.setProperty("hoodie.datasource.write.keygenerator.class", TestHoodieDeltaStreamer.TestGenerator.class.getName());

    parquetProps.setProperty("include", "base.properties");
    parquetProps.setProperty("hoodie.embed.timeline.server", "false");
    parquetProps.setProperty("hoodie.datasource.write.recordkey.field", "_row_key");
    parquetProps.setProperty("hoodie.datasource.write.partitionpath.field", partitionPath);
    if (useSchemaProvider) {
      parquetProps.setProperty("hoodie.deltastreamer.schemaprovider.source.schema.file", basePath + "/" + sourceSchemaFile);
      if (hasTransformer) {
        parquetProps.setProperty("hoodie.deltastreamer.schemaprovider.target.schema.file", basePath + "/" + targetSchemaFile);
      }
    }
    parquetProps.setProperty("hoodie.deltastreamer.source.dfs.root", parquetSourceRoot);
    if (!StringUtils.isNullOrEmpty(emptyBatchParam)) {
      parquetProps.setProperty(TestParquetDFSSourceEmptyBatch.RETURN_EMPTY_BATCH, emptyBatchParam);
    }
    UtilitiesTestBase.Helpers.savePropsToDFS(parquetProps, fs, basePath + "/" + propsFileName);
  }

  private void testParquetDFSSource(boolean useSchemaProvider, List<String> transformerClassNames) throws Exception {
    testParquetDFSSource(useSchemaProvider, transformerClassNames, false);
  }

  private void testParquetDFSSource(boolean useSchemaProvider, List<String> transformerClassNames, boolean testEmptyBatch) throws Exception {
    PARQUET_SOURCE_ROOT = basePath + "/parquetFilesDfs" + testNum;
    int parquetRecordsCount = 10;
    boolean hasTransformer = transformerClassNames != null && !transformerClassNames.isEmpty();
    prepareParquetDFSFiles(parquetRecordsCount, PARQUET_SOURCE_ROOT, FIRST_PARQUET_FILE_NAME, false, null, null);
    prepareParquetDFSSource(useSchemaProvider, hasTransformer, "source.avsc", "target.avsc", PROPS_FILENAME_TEST_PARQUET,
        PARQUET_SOURCE_ROOT, false, "partition_path", testEmptyBatch ? "1" : "");

    String tableBasePath = basePath + "/test_parquet_table" + testNum;
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, testEmptyBatch ? TestParquetDFSSourceEmptyBatch.class.getName()
                : ParquetDFSSource.class.getName(),
            transformerClassNames, PROPS_FILENAME_TEST_PARQUET, false,
            useSchemaProvider, 100000, false, null, null, "timestamp", null), jsc);
    deltaStreamer.sync();
    TestHelpers.assertRecordCount(parquetRecordsCount, tableBasePath, sqlContext);

    if (testEmptyBatch) {
      prepareParquetDFSFiles(100, PARQUET_SOURCE_ROOT, "2.parquet", false, null, null);
      deltaStreamer.sync();
      // since we mimic'ed empty batch, total records should be same as first sync().
      TestHelpers.assertRecordCount(parquetRecordsCount, tableBasePath, sqlContext);
      HoodieTableMetaClient metaClient = HoodieTableMetaClient.builder().setBasePath(tableBasePath).setConf(jsc.hadoopConfiguration()).build();

      // validate table schema fetches valid schema from last but one commit.
      TableSchemaResolver tableSchemaResolver = new TableSchemaResolver(metaClient);
      assertNotEquals(tableSchemaResolver.getTableAvroSchema(), Schema.create(Schema.Type.NULL).toString());
    }

    // proceed w/ non empty batch.
    prepareParquetDFSFiles(100, PARQUET_SOURCE_ROOT, "3.parquet", false, null, null);
    deltaStreamer.sync();
    TestHelpers.assertRecordCount(parquetRecordsCount + 100, tableBasePath, sqlContext);
    // validate commit metadata for all completed commits to have valid schema in extra metadata.
    HoodieTableMetaClient metaClient = HoodieTableMetaClient.builder().setBasePath(tableBasePath).setConf(jsc.hadoopConfiguration()).build();
    metaClient.reloadActiveTimeline().getCommitsTimeline().filterCompletedInstants().getInstants().forEach(entry -> assertValidSchemaInCommitMetadata(entry, metaClient));
    testNum++;
  }

  private void assertValidSchemaInCommitMetadata(HoodieInstant instant, HoodieTableMetaClient metaClient) {
    try {
      HoodieCommitMetadata commitMetadata = HoodieCommitMetadata
          .fromBytes(metaClient.getActiveTimeline().getInstantDetails(instant).get(), HoodieCommitMetadata.class);
      assertFalse(StringUtils.isNullOrEmpty(commitMetadata.getMetadata(HoodieCommitMetadata.SCHEMA_KEY)));
    } catch (IOException ioException) {
      throw new HoodieException("Failed to parse commit metadata for " + instant.toString());
    }
  }

  private void testORCDFSSource(boolean useSchemaProvider, List<String> transformerClassNames) throws Exception {
    // prepare ORCDFSSource
    TypedProperties orcProps = new TypedProperties();

    // Properties used for testing delta-streamer with orc source
    orcProps.setProperty("include", "base.properties");
    orcProps.setProperty("hoodie.embed.timeline.server", "false");
    orcProps.setProperty("hoodie.datasource.write.recordkey.field", "_row_key");
    orcProps.setProperty("hoodie.datasource.write.partitionpath.field", "partition_path");
    if (useSchemaProvider) {
      orcProps.setProperty("hoodie.deltastreamer.schemaprovider.source.schema.file", basePath + "/" + "source.avsc");
      if (transformerClassNames != null) {
        orcProps.setProperty("hoodie.deltastreamer.schemaprovider.target.schema.file", basePath + "/" + "target.avsc");
      }
    }
    orcProps.setProperty("hoodie.deltastreamer.source.dfs.root", ORC_SOURCE_ROOT);
    UtilitiesTestBase.Helpers.savePropsToDFS(orcProps, fs, basePath + "/" + PROPS_FILENAME_TEST_ORC);

    String tableBasePath = basePath + "/test_orc_source_table" + testNum;
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, ORCDFSSource.class.getName(),
            transformerClassNames, PROPS_FILENAME_TEST_ORC, false,
            useSchemaProvider, 100000, false, null, null, "timestamp", null), jsc);
    deltaStreamer.sync();
    TestHelpers.assertRecordCount(ORC_NUM_RECORDS, tableBasePath, sqlContext);
    testNum++;
  }

  private void prepareJsonKafkaDFSSource(String propsFileName, String autoResetValue, String topicName) throws IOException {
    prepareJsonKafkaDFSSource(propsFileName, autoResetValue, topicName, null, false);
  }

  private void prepareJsonKafkaDFSSource(String propsFileName, String autoResetValue, String topicName, Map<String,String> extraProps, boolean shouldAddOffsets) throws IOException {
    // Properties used for testing delta-streamer with JsonKafka source
    TypedProperties props = new TypedProperties();
    populateAllCommonProps(props, basePath, testUtils.brokerAddress());
    props.setProperty("include", "base.properties");
    props.setProperty("hoodie.embed.timeline.server", "false");
    props.setProperty("hoodie.datasource.write.recordkey.field", "_row_key");
    props.setProperty("hoodie.datasource.write.partitionpath.field", "driver");
    props.setProperty("hoodie.deltastreamer.source.dfs.root", JSON_KAFKA_SOURCE_ROOT);
    props.setProperty("hoodie.deltastreamer.source.kafka.topic", topicName);
    props.setProperty("hoodie.deltastreamer.source.kafka.checkpoint.type", kafkaCheckpointType);
    props.setProperty("hoodie.deltastreamer.schemaprovider.source.schema.file", basePath + "/source_uber.avsc");
    props.setProperty("hoodie.deltastreamer.schemaprovider.target.schema.file", basePath + "/target_uber.avsc");
    props.setProperty("auto.offset.reset", autoResetValue);
    if (extraProps != null && !extraProps.isEmpty()) {
      extraProps.forEach(props::setProperty);
    }
    props.setProperty(KafkaOffsetPostProcessor.Config.KAFKA_APPEND_OFFSETS.key(), Boolean.toString(shouldAddOffsets));
    UtilitiesTestBase.Helpers.savePropsToDFS(props, fs, basePath + "/" + propsFileName);
  }

  /**
   * Tests Deltastreamer with parquet dfs source and transitions to JsonKafkaSource.
   *
   * @param autoResetToLatest true if auto reset value to be set to LATEST. false to leave it as default(i.e. EARLIEST)
   * @throws Exception
   */
  private void testDeltaStreamerTransitionFromParquetToKafkaSource(boolean autoResetToLatest) throws Exception {
    // prep parquet source
    PARQUET_SOURCE_ROOT = basePath + "/parquetFilesDfsToKafka" + testNum;
    int parquetRecords = 10;
    prepareParquetDFSFiles(parquetRecords, PARQUET_SOURCE_ROOT, FIRST_PARQUET_FILE_NAME, true, HoodieTestDataGenerator.TRIP_SCHEMA, HoodieTestDataGenerator.AVRO_TRIP_SCHEMA);

    prepareParquetDFSSource(true, true, "source_uber.avsc", "target_uber.avsc", PROPS_FILENAME_TEST_PARQUET,
        PARQUET_SOURCE_ROOT, false, "driver");
    // delta streamer w/ parquet source
    String tableBasePath = basePath + "/test_dfs_to_kafka" + testNum;
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, ParquetDFSSource.class.getName(),
            Collections.emptyList(), PROPS_FILENAME_TEST_PARQUET, false,
            true, 100000, false, null, null, "timestamp", null), jsc);
    deltaStreamer.sync();
    TestHelpers.assertRecordCount(parquetRecords, tableBasePath, sqlContext);
    deltaStreamer.shutdownGracefully();

    // prep json kafka source
    topicName = "topic" + testNum;
    prepareJsonKafkaDFSFiles(JSON_KAFKA_NUM_RECORDS, true, topicName);
    prepareJsonKafkaDFSSource(PROPS_FILENAME_TEST_JSON_KAFKA, autoResetToLatest ? "latest" : "earliest", topicName);
    // delta streamer w/ json kafka source
    deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT, JsonKafkaSource.class.getName(),
            Collections.emptyList(), PROPS_FILENAME_TEST_JSON_KAFKA, false,
            true, 100000, false, null, null, "timestamp", null), jsc);
    deltaStreamer.sync();
    // if auto reset value is set to LATEST, this all kafka records so far may not be synced.
    int totalExpectedRecords = parquetRecords + ((autoResetToLatest) ? 0 : JSON_KAFKA_NUM_RECORDS);
    TestHelpers.assertRecordCount(totalExpectedRecords, tableBasePath, sqlContext);

    // verify 2nd batch to test LATEST auto reset value.
    prepareJsonKafkaDFSFiles(20, false, topicName);
    totalExpectedRecords += 20;
    deltaStreamer.sync();
    TestHelpers.assertRecordCount(totalExpectedRecords, tableBasePath, sqlContext);
    testNum++;
  }

  @Test
  public void testJsonKafkaDFSSource() throws Exception {
    topicName = "topic" + testNum;
    prepareJsonKafkaDFSFiles(JSON_KAFKA_NUM_RECORDS, true, topicName);
    prepareJsonKafkaDFSSource(PROPS_FILENAME_TEST_JSON_KAFKA, "earliest", topicName);
    String tableBasePath = basePath + "/test_json_kafka_table" + testNum;
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT, JsonKafkaSource.class.getName(),
            Collections.emptyList(), PROPS_FILENAME_TEST_JSON_KAFKA, false,
            true, 100000, false, null, null, "timestamp", null), jsc);
    deltaStreamer.sync();
    TestHelpers.assertRecordCount(JSON_KAFKA_NUM_RECORDS, tableBasePath, sqlContext);

    int totalRecords = JSON_KAFKA_NUM_RECORDS;
    int records = 10;
    totalRecords += records;
    prepareJsonKafkaDFSFiles(records, false, topicName);
    deltaStreamer.sync();
    TestHelpers.assertRecordCount(totalRecords, tableBasePath, sqlContext);
  }

  @Test
  public void testJsonKafkaDFSSourceWithOffsets() throws Exception {
    topicName = "topic" + testNum;
    int numRecords = 15;
    int numPartitions = 3;
    int recsPerPartition = numRecords / numPartitions;
    long beforeTime = Instant.now().toEpochMilli();
    prepareJsonKafkaDFSFiles(numRecords, true, topicName, numPartitions);
    prepareJsonKafkaDFSSource(PROPS_FILENAME_TEST_JSON_KAFKA, "earliest", topicName, null, true);
    String tableBasePath = basePath + "/test_json_kafka_offsets_table" + testNum;
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT, JsonKafkaSource.class.getName(),
            Collections.emptyList(), PROPS_FILENAME_TEST_JSON_KAFKA, false,
            true, 100000, false, null, null, "timestamp", null), jsc);
    deltaStreamer.sync();
    sqlContext.clearCache();
    Dataset<Row> ds = sqlContext.read().format("org.apache.hudi").load(tableBasePath);
    assertEquals(numRecords, ds.count());
    //ensure that kafka partition column exists and is populated correctly
    for (int i = 0; i < numPartitions; i++) {
      assertEquals(recsPerPartition, ds.filter(KAFKA_SOURCE_PARTITION_COLUMN + "=" + i).count());
    }

    //ensure that kafka timestamp column exists and is populated correctly
    long afterTime = Instant.now().toEpochMilli();
    assertEquals(numRecords, ds.filter(KAFKA_SOURCE_TIMESTAMP_COLUMN + ">" + beforeTime).filter(KAFKA_SOURCE_TIMESTAMP_COLUMN + "<" + afterTime).count());


    //ensure that kafka offset column exists and is populated correctly
    sqlContext.read().format("org.apache.hudi").load(tableBasePath).col(KAFKA_SOURCE_OFFSET_COLUMN);
    for (int i = 0; i < recsPerPartition; i++) {
      for (int j = 0; j < numPartitions; j++) {
        //each offset partition pair should be unique
        assertEquals(1, ds.filter(KAFKA_SOURCE_OFFSET_COLUMN + "=" + i).filter(KAFKA_SOURCE_PARTITION_COLUMN + "=" + j).count());
      }
    }
  }

  @Test
  public void testKafkaTimestampType() throws Exception {
    topicName = "topic" + testNum;
    kafkaCheckpointType = "timestamp";
    prepareJsonKafkaDFSFiles(JSON_KAFKA_NUM_RECORDS, true, topicName);
    prepareJsonKafkaDFSSource(PROPS_FILENAME_TEST_JSON_KAFKA, "earliest", topicName);
    String tableBasePath = basePath + "/test_json_kafka_table" + testNum;
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT, JsonKafkaSource.class.getName(),
            Collections.emptyList(), PROPS_FILENAME_TEST_JSON_KAFKA, false,
            true, 100000, false, null,
            null, "timestamp", String.valueOf(System.currentTimeMillis())), jsc);
    deltaStreamer.sync();
    TestHelpers.assertRecordCount(JSON_KAFKA_NUM_RECORDS, tableBasePath, sqlContext);

    prepareJsonKafkaDFSFiles(JSON_KAFKA_NUM_RECORDS, false, topicName);
    deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT, JsonKafkaSource.class.getName(),
            Collections.emptyList(), PROPS_FILENAME_TEST_JSON_KAFKA, false,
            true, 100000, false, null, null,
            "timestamp", String.valueOf(System.currentTimeMillis())), jsc);
    deltaStreamer.sync();
    TestHelpers.assertRecordCount(JSON_KAFKA_NUM_RECORDS * 2, tableBasePath, sqlContext);
  }

  @Test
  public void testDeltaStreamerMultiwriterCheckpoint() throws Exception {
    // prep parquet source
    PARQUET_SOURCE_ROOT = basePath + "/parquetFilesMultiCheckpoint" + testNum;
    int parquetRecords = 100;
    HoodieTestDataGenerator dataGenerator = prepareParquetDFSFiles(parquetRecords, PARQUET_SOURCE_ROOT, FIRST_PARQUET_FILE_NAME, true,
        HoodieTestDataGenerator.TRIP_SCHEMA, HoodieTestDataGenerator.AVRO_TRIP_SCHEMA);

    prepareParquetDFSSource(true, true, "source_uber.avsc", "target_uber.avsc", PROPS_FILENAME_TEST_PARQUET,
        PARQUET_SOURCE_ROOT, false, "driver");

    // delta streamer w/ parquet source
    String tableBasePath = basePath + "/test_multi_checkpoint" + testNum;
    HoodieDeltaStreamer.Config parquetCfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, ParquetDFSSource.class.getName(),
        Collections.emptyList(), PROPS_FILENAME_TEST_PARQUET, false,
        true, Integer.MAX_VALUE, false, null, null, "timestamp", null);
    parquetCfg.configs = new ArrayList<>();
    parquetCfg.configs.add(MUTLI_WRITER_SOURCE_CHECKPOINT_ID.key() + "=parquet");
    //parquetCfg.continuousMode = false;
    HoodieDeltaStreamer parquetDs = new HoodieDeltaStreamer(parquetCfg, jsc);
    parquetDs.sync();
    TestHelpers.assertRecordCount(100, tableBasePath, sqlContext);

    // prep json kafka source
    topicName = "topic" + testNum;
    prepareJsonKafkaDFSFiles(20, true, topicName);
    Map<String, String> kafkaExtraProps = new HashMap<>();
    kafkaExtraProps.put(MUTLI_WRITER_SOURCE_CHECKPOINT_ID.key(), "kafka");
    prepareJsonKafkaDFSSource(PROPS_FILENAME_TEST_JSON_KAFKA, "earliest", topicName, kafkaExtraProps, false);
    // delta streamer w/ json kafka source
    HoodieDeltaStreamer kafkaDs = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT, JsonKafkaSource.class.getName(),
            Collections.emptyList(), PROPS_FILENAME_TEST_JSON_KAFKA, false,
            true, Integer.MAX_VALUE, false, null, null, "timestamp", null), jsc);
    kafkaDs.sync();
    int totalExpectedRecords = parquetRecords + 20;
    TestHelpers.assertRecordCount(totalExpectedRecords, tableBasePath, sqlContext);
    //parquet again
    prepareParquetDFSUpdates(parquetRecords, PARQUET_SOURCE_ROOT, FIRST_PARQUET_FILE_NAME, true, HoodieTestDataGenerator.TRIP_SCHEMA, HoodieTestDataGenerator.AVRO_TRIP_SCHEMA,
        dataGenerator, "001");
    parquetDs = new HoodieDeltaStreamer(parquetCfg, jsc);
    parquetDs.sync();
    TestHelpers.assertRecordCount(parquetRecords * 2 + 20, tableBasePath, sqlContext);

    HoodieTableMetaClient metaClient = HoodieTestUtils.init(jsc.hadoopConfiguration(), tableBasePath);
    List<HoodieInstant> instants = metaClient.getCommitsTimeline().getInstants();

    ObjectMapper objectMapper = new ObjectMapper();
    HoodieCommitMetadata commitMetadata = HoodieCommitMetadata
        .fromBytes(metaClient.getCommitsTimeline().getInstantDetails(instants.get(0)).get(), HoodieCommitMetadata.class);
    Map<String,String>  checkpointVals = objectMapper.readValue(commitMetadata.getExtraMetadata().get(CHECKPOINT_KEY), Map.class);

    String parquetFirstcheckpoint = checkpointVals.get("parquet");
    assertNotNull(parquetFirstcheckpoint);
    commitMetadata = HoodieCommitMetadata
        .fromBytes(metaClient.getCommitsTimeline().getInstantDetails(instants.get(1)).get(), HoodieCommitMetadata.class);
    checkpointVals = objectMapper.readValue(commitMetadata.getExtraMetadata().get(CHECKPOINT_KEY), Map.class);
    String kafkaCheckpoint = checkpointVals.get("kafka");
    assertNotNull(kafkaCheckpoint);
    assertEquals(parquetFirstcheckpoint, checkpointVals.get("parquet"));

    commitMetadata = HoodieCommitMetadata
        .fromBytes(metaClient.getCommitsTimeline().getInstantDetails(instants.get(2)).get(), HoodieCommitMetadata.class);
    checkpointVals = objectMapper.readValue(commitMetadata.getExtraMetadata().get(CHECKPOINT_KEY), Map.class);
    String parquetSecondCheckpoint = checkpointVals.get("parquet");
    assertNotNull(parquetSecondCheckpoint);
    assertEquals(kafkaCheckpoint,checkpointVals.get("kafka"));
    assertTrue(Long.parseLong(parquetSecondCheckpoint) > Long.parseLong(parquetFirstcheckpoint));
    parquetDs.shutdownGracefully();
    kafkaDs.shutdownGracefully();
  }

  @Test
  public void testParquetSourceToKafkaSourceEarliestAutoResetValue() throws Exception {
    testDeltaStreamerTransitionFromParquetToKafkaSource(false);
  }

  @Test
  public void testParquetSourceToKafkaSourceLatestAutoResetValue() throws Exception {
    testDeltaStreamerTransitionFromParquetToKafkaSource(true);
  }

  @Test
  public void testParquetDFSSourceWithoutSchemaProviderAndNoTransformer() throws Exception {
    testParquetDFSSource(false, null);
  }

  @Test
  public void testParquetDFSSourceForEmptyBatch() throws Exception {
    testParquetDFSSource(false, null, true);
  }

  @Test
  public void testDeltaStreamerRestartAfterMissingHoodieProps() throws Exception {
    testDeltaStreamerRestartAfterMissingHoodieProps(true);
  }

  @Test
  public void testDeltaStreamerRestartAfterMissingHoodiePropsAfterValidCommit() throws Exception {
    testDeltaStreamerRestartAfterMissingHoodieProps(false);
  }

  private void testDeltaStreamerRestartAfterMissingHoodieProps(boolean testInitFailure) throws Exception {
    PARQUET_SOURCE_ROOT = basePath + "/parquetFilesDfs" + testNum;
    int parquetRecordsCount = 10;
    boolean hasTransformer = false;
    boolean useSchemaProvider = false;
    prepareParquetDFSFiles(parquetRecordsCount, PARQUET_SOURCE_ROOT, FIRST_PARQUET_FILE_NAME, false, null, null);
    prepareParquetDFSSource(useSchemaProvider, hasTransformer, "source.avsc", "target.avsc", PROPS_FILENAME_TEST_PARQUET,
        PARQUET_SOURCE_ROOT, false, "partition_path", "0");

    String tableBasePath = basePath + "/test_parquet_table" + testNum;
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, testInitFailure ? TestParquetDFSSourceEmptyBatch.class.getName() : ParquetDFSSource.class.getName(),
            null, PROPS_FILENAME_TEST_PARQUET, false,
            useSchemaProvider, 100000, false, null, null, "timestamp", null), jsc);
    deltaStreamer.sync();

    if (testInitFailure) {
      FileStatus[] fileStatuses = fs.listStatus(new Path(tableBasePath + "/.hoodie/"));
      Arrays.stream(fileStatuses).filter(entry -> entry.getPath().getName().contains("commit") || entry.getPath().getName().contains("inflight")).forEach(entry -> {
        try {
          fs.delete(entry.getPath());
        } catch (IOException e) {
          e.printStackTrace();
        }
      });
    }
    // delete hoodie.properties
    fs.delete(new Path(tableBasePath + "/.hoodie/hoodie.properties"));

    // restart the pipeline.
    if (testInitFailure) { // should succeed.
      deltaStreamer = new HoodieDeltaStreamer(
          TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, ParquetDFSSource.class.getName(),
              null, PROPS_FILENAME_TEST_PARQUET, false,
              useSchemaProvider, 100000, false, null, null, "timestamp", null), jsc);
      deltaStreamer.sync();
      TestHelpers.assertRecordCount(parquetRecordsCount, tableBasePath, sqlContext);
    } else {
      assertThrows(org.apache.hudi.exception.HoodieIOException.class, () -> new HoodieDeltaStreamer(
          TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, ParquetDFSSource.class.getName(),
              null, PROPS_FILENAME_TEST_PARQUET, false,
              useSchemaProvider, 100000, false, null, null, "timestamp", null), jsc));
    }
    testNum++;
  }

  @Test
  public void testParquetDFSSourceWithoutSchemaProviderAndTransformer() throws Exception {
    testParquetDFSSource(false, Collections.singletonList(TripsWithDistanceTransformer.class.getName()));
  }

  @Test
  public void testParquetDFSSourceWithSourceSchemaFileAndNoTransformer() throws Exception {
    testParquetDFSSource(true, null);
  }

  @Test
  public void testParquetDFSSourceWithSchemaFilesAndTransformer() throws Exception {
    testParquetDFSSource(true, Collections.singletonList(TripsWithDistanceTransformer.class.getName()));
  }

  @Test
  public void testORCDFSSourceWithoutSchemaProviderAndNoTransformer() throws Exception {
    // NOTE: Hudi doesn't support Orc in Spark < 3.0
    //       Please check HUDI-4496 for more details
    if (HoodieSparkUtils$.MODULE$.gteqSpark3_0()) {
      testORCDFSSource(false, null);
    }
  }

  @Test
  public void testORCDFSSourceWithSchemaProviderAndWithTransformer() throws Exception {
    // NOTE: Hudi doesn't support Orc in Spark < 3.0
    //       Please check HUDI-4496 for more details
    if (HoodieSparkUtils$.MODULE$.gteqSpark3_0()) {
      testORCDFSSource(true, Collections.singletonList(TripsWithDistanceTransformer.class.getName()));
    }
  }

  private void prepareCsvDFSSource(
      boolean hasHeader, char sep, boolean useSchemaProvider, boolean hasTransformer) throws IOException {
    String sourceRoot = basePath + "/csvFiles";
    String recordKeyField = (hasHeader || useSchemaProvider) ? "_row_key" : "_c1";
    String partitionPath = (hasHeader || useSchemaProvider) ? "partition_path" : "_c2";

    // Properties used for testing delta-streamer with CSV source
    TypedProperties csvProps = new TypedProperties();
    csvProps.setProperty("include", "base.properties");
    csvProps.setProperty("hoodie.datasource.write.recordkey.field", recordKeyField);
    csvProps.setProperty("hoodie.datasource.write.partitionpath.field", partitionPath);
    if (useSchemaProvider) {
      csvProps.setProperty("hoodie.deltastreamer.schemaprovider.source.schema.file", basePath + "/source-flattened.avsc");
      if (hasTransformer) {
        csvProps.setProperty("hoodie.deltastreamer.schemaprovider.target.schema.file", basePath + "/target-flattened.avsc");
      }
    }
    csvProps.setProperty("hoodie.deltastreamer.source.dfs.root", sourceRoot);

    if (sep != ',') {
      if (sep == '\t') {
        csvProps.setProperty("hoodie.deltastreamer.csv.sep", "\\t");
      } else {
        csvProps.setProperty("hoodie.deltastreamer.csv.sep", Character.toString(sep));
      }
    }
    if (hasHeader) {
      csvProps.setProperty("hoodie.deltastreamer.csv.header", Boolean.toString(hasHeader));
    }

    UtilitiesTestBase.Helpers.savePropsToDFS(csvProps, fs, basePath + "/" + PROPS_FILENAME_TEST_CSV);

    String path = sourceRoot + "/1.csv";
    HoodieTestDataGenerator dataGenerator = new HoodieTestDataGenerator();
    UtilitiesTestBase.Helpers.saveCsvToDFS(
        hasHeader, sep,
        Helpers.jsonifyRecords(dataGenerator.generateInserts("000", CSV_NUM_RECORDS, true)),
        fs, path);
  }

  private void testCsvDFSSource(
      boolean hasHeader, char sep, boolean useSchemaProvider, List<String> transformerClassNames) throws Exception {
    prepareCsvDFSSource(hasHeader, sep, useSchemaProvider, transformerClassNames != null);
    String tableBasePath = basePath + "/test_csv_table" + testNum;
    String sourceOrderingField = (hasHeader || useSchemaProvider) ? "timestamp" : "_c0";
    HoodieDeltaStreamer deltaStreamer =
        new HoodieDeltaStreamer(TestHelpers.makeConfig(
            tableBasePath, WriteOperationType.INSERT, CsvDFSSource.class.getName(),
            transformerClassNames, PROPS_FILENAME_TEST_CSV, false,
            useSchemaProvider, 1000, false, null, null, sourceOrderingField, null), jsc);
    deltaStreamer.sync();
    TestHelpers.assertRecordCount(CSV_NUM_RECORDS, tableBasePath, sqlContext);
    testNum++;
  }

  @Test
  public void testCsvDFSSourceWithHeaderWithoutSchemaProviderAndNoTransformer() throws Exception {
    // The CSV files have header, the columns are separated by ',', the default separator
    // No schema provider is specified, no transformer is applied
    // In this case, the source schema comes from the inferred schema of the CSV files
    testCsvDFSSource(true, ',', false, null);
  }

  @Test
  public void testCsvDFSSourceWithHeaderAndSepWithoutSchemaProviderAndNoTransformer() throws Exception {
    // The CSV files have header, the columns are separated by '\t',
    // which is passed in through the Hudi CSV properties
    // No schema provider is specified, no transformer is applied
    // In this case, the source schema comes from the inferred schema of the CSV files
    testCsvDFSSource(true, '\t', false, null);
  }

  @Test
  public void testCsvDFSSourceWithHeaderAndSepWithSchemaProviderAndNoTransformer() throws Exception {
    // The CSV files have header, the columns are separated by '\t'
    // File schema provider is used, no transformer is applied
    // In this case, the source schema comes from the source Avro schema file
    testCsvDFSSource(true, '\t', true, null);
  }

  @Test
  public void testCsvDFSSourceWithHeaderAndSepWithoutSchemaProviderAndWithTransformer() throws Exception {
    // The CSV files have header, the columns are separated by '\t'
    // No schema provider is specified, transformer is applied
    // In this case, the source schema comes from the inferred schema of the CSV files.
    // Target schema is determined based on the Dataframe after transformation
    testCsvDFSSource(true, '\t', false, Collections.singletonList(TripsWithDistanceTransformer.class.getName()));
  }

  @Test
  public void testCsvDFSSourceWithHeaderAndSepWithSchemaProviderAndTransformer() throws Exception {
    // The CSV files have header, the columns are separated by '\t'
    // File schema provider is used, transformer is applied
    // In this case, the source and target schema come from the Avro schema files
    testCsvDFSSource(true, '\t', true, Collections.singletonList(TripsWithDistanceTransformer.class.getName()));
  }

  @Test
  public void testCsvDFSSourceNoHeaderWithoutSchemaProviderAndNoTransformer() throws Exception {
    // The CSV files do not have header, the columns are separated by '\t',
    // which is passed in through the Hudi CSV properties
    // No schema provider is specified, no transformer is applied
    // In this case, the source schema comes from the inferred schema of the CSV files
    // No CSV header and no schema provider at the same time are not recommended
    // as the column names are not informative
    testCsvDFSSource(false, '\t', false, null);
  }

  @Test
  public void testCsvDFSSourceNoHeaderWithSchemaProviderAndNoTransformer() throws Exception {
    // The CSV files do not have header, the columns are separated by '\t'
    // File schema provider is used, no transformer is applied
    // In this case, the source schema comes from the source Avro schema file
    testCsvDFSSource(false, '\t', true, null);
  }

  @Test
  public void testCsvDFSSourceNoHeaderWithoutSchemaProviderAndWithTransformer() throws Exception {
    // The CSV files do not have header, the columns are separated by '\t'
    // No schema provider is specified, transformer is applied
    // In this case, the source schema comes from the inferred schema of the CSV files.
    // Target schema is determined based on the Dataframe after transformation
    // No CSV header and no schema provider at the same time are not recommended,
    // as the transformer behavior may be unexpected
    Exception e = assertThrows(AnalysisException.class, () -> {
      testCsvDFSSource(false, '\t', false, Collections.singletonList(TripsWithDistanceTransformer.class.getName()));
    }, "Should error out when doing the transformation.");
    LOG.debug("Expected error during transformation", e);
    assertTrue(e.getMessage().contains("cannot resolve '`begin_lat`' given input columns:"));
  }

  @Test
  public void testCsvDFSSourceNoHeaderWithSchemaProviderAndTransformer() throws Exception {
    // The CSV files do not have header, the columns are separated by '\t'
    // File schema provider is used, transformer is applied
    // In this case, the source and target schema come from the Avro schema files
    testCsvDFSSource(false, '\t', true, Collections.singletonList(TripsWithDistanceTransformer.class.getName()));
  }

  private void prepareSqlSource() throws IOException {
    String sourceRoot = basePath + "sqlSourceFiles";
    TypedProperties sqlSourceProps = new TypedProperties();
    sqlSourceProps.setProperty("include", "base.properties");
    sqlSourceProps.setProperty("hoodie.embed.timeline.server", "false");
    sqlSourceProps.setProperty("hoodie.datasource.write.recordkey.field", "_row_key");
    sqlSourceProps.setProperty("hoodie.datasource.write.partitionpath.field", "partition_path");
    sqlSourceProps.setProperty("hoodie.deltastreamer.source.sql.sql.query","select * from test_sql_table");

    UtilitiesTestBase.Helpers.savePropsToDFS(sqlSourceProps, fs, basePath + "/" + PROPS_FILENAME_TEST_SQL_SOURCE);

    // Data generation
    HoodieTestDataGenerator dataGenerator = new HoodieTestDataGenerator();
    generateSqlSourceTestTable(sourceRoot, "1", "1000", SQL_SOURCE_NUM_RECORDS, dataGenerator);
  }

  private void generateSqlSourceTestTable(String dfsRoot, String filename, String instantTime, int n, HoodieTestDataGenerator dataGenerator) throws IOException {
    Path path = new Path(dfsRoot, filename);
    Helpers.saveParquetToDFS(Helpers.toGenericRecords(dataGenerator.generateInserts(instantTime, n, false)), path);
    sparkSession.read().parquet(dfsRoot).createOrReplaceTempView("test_sql_table");
  }

  @Test
  public void testSqlSourceSource() throws Exception {
    prepareSqlSource();
    String tableBasePath = basePath + "/test_sql_source_table" + testNum++;
    HoodieDeltaStreamer deltaStreamer =
        new HoodieDeltaStreamer(TestHelpers.makeConfig(
            tableBasePath, WriteOperationType.INSERT, SqlSource.class.getName(),
            Collections.emptyList(), PROPS_FILENAME_TEST_SQL_SOURCE, false,
            false, 1000, false, null, null, "timestamp", null, true), jsc);
    deltaStreamer.sync();
    TestHelpers.assertRecordCount(SQL_SOURCE_NUM_RECORDS, tableBasePath, sqlContext);
  }

  @Disabled
  @Test
  public void testJdbcSourceIncrementalFetchInContinuousMode() {
    try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:test_mem", "test", "jdbc")) {
      TypedProperties props = new TypedProperties();
      props.setProperty("hoodie.deltastreamer.jdbc.url", "jdbc:h2:mem:test_mem");
      props.setProperty("hoodie.deltastreamer.jdbc.driver.class", "org.h2.Driver");
      props.setProperty("hoodie.deltastreamer.jdbc.user", "test");
      props.setProperty("hoodie.deltastreamer.jdbc.password", "jdbc");
      props.setProperty("hoodie.deltastreamer.jdbc.table.name", "triprec");
      props.setProperty("hoodie.deltastreamer.jdbc.incr.pull", "true");
      props.setProperty("hoodie.deltastreamer.jdbc.table.incr.column.name", "id");

      props.setProperty("hoodie.datasource.write.keygenerator.class", SimpleKeyGenerator.class.getName());
      props.setProperty("hoodie.datasource.write.recordkey.field", "ID");
      props.setProperty("hoodie.datasource.write.partitionpath.field", "partition_path");

      UtilitiesTestBase.Helpers.savePropsToDFS(props, fs, basePath + "/test-jdbc-source.properties");

      int numRecords = 1000;
      int sourceLimit = 100;
      String tableBasePath = basePath + "/triprec";
      HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, JdbcSource.class.getName(),
          null, "test-jdbc-source.properties", false,
          false, sourceLimit, false, null, null, "timestamp", null);
      cfg.continuousMode = true;
      // Add 1000 records
      JdbcTestUtils.clearAndInsert("000", numRecords, connection, new HoodieTestDataGenerator(), props);

      HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(cfg, jsc);
      deltaStreamerTestRunner(deltaStreamer, cfg, (r) -> {
        TestHelpers.assertAtleastNCompactionCommits(numRecords / sourceLimit + ((numRecords % sourceLimit == 0) ? 0 : 1), tableBasePath, fs);
        TestHelpers.assertRecordCount(numRecords, tableBasePath, sqlContext);
        return true;
      });
    } catch (Exception e) {
      fail(e.getMessage());
    }
  }

  @Test
  public void testHoodieIncrFallback() throws Exception {
    String tableBasePath = basePath + "/incr_test_table";
    String downstreamTableBasePath = basePath + "/incr_test_downstream_table";

    insertInTable(tableBasePath, 1, WriteOperationType.BULK_INSERT);
    HoodieDeltaStreamer.Config downstreamCfg =
        TestHelpers.makeConfigForHudiIncrSrc(tableBasePath, downstreamTableBasePath,
            WriteOperationType.BULK_INSERT, true, null);
    downstreamCfg.configs.add("hoodie.deltastreamer.source.hoodieincr.num_instants=1");
    new HoodieDeltaStreamer(downstreamCfg, jsc).sync();

    insertInTable(tableBasePath, 9, WriteOperationType.UPSERT);
    //No change as this fails with Path not exist error
    assertThrows(org.apache.spark.sql.AnalysisException.class, () -> new HoodieDeltaStreamer(downstreamCfg, jsc).sync());
    TestHelpers.assertRecordCount(1000, downstreamTableBasePath, sqlContext);

    if (downstreamCfg.configs == null) {
      downstreamCfg.configs = new ArrayList<>();
    }

    // Remove source.hoodieincr.num_instants config
    downstreamCfg.configs.remove(downstreamCfg.configs.size() - 1);
    downstreamCfg.configs.add(DataSourceReadOptions.INCREMENTAL_FALLBACK_TO_FULL_TABLE_SCAN_FOR_NON_EXISTING_FILES().key() + "=true");
    //Adding this conf to make testing easier :)
    downstreamCfg.configs.add("hoodie.deltastreamer.source.hoodieincr.num_instants=10");
    downstreamCfg.operation = WriteOperationType.UPSERT;
    new HoodieDeltaStreamer(downstreamCfg, jsc).sync();
    new HoodieDeltaStreamer(downstreamCfg, jsc).sync();

    long baseTableRecords = sqlContext.read().format("org.apache.hudi").load(tableBasePath).count();
    long downStreamTableRecords = sqlContext.read().format("org.apache.hudi").load(downstreamTableBasePath).count();
    assertEquals(baseTableRecords, downStreamTableRecords);
  }

  private void insertInTable(String tableBasePath, int count, WriteOperationType operationType) throws Exception {
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, operationType,
        Collections.singletonList(SqlQueryBasedTransformer.class.getName()), PROPS_FILENAME_TEST_SOURCE, false);
    if (cfg.configs == null) {
      cfg.configs = new ArrayList<>();
    }
    cfg.configs.add("hoodie.cleaner.commits.retained=3");
    cfg.configs.add("hoodie.keep.min.commits=4");
    cfg.configs.add("hoodie.keep.max.commits=5");
    cfg.configs.add("hoodie.test.source.generate.inserts=true");

    for (int i = 0; i < count; i++) {
      new HoodieDeltaStreamer(cfg, jsc).sync();
    }
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testInsertOverwrite(HoodieRecordType recordType) throws Exception {
    testDeltaStreamerWithSpecifiedOperation(basePath + "/insert_overwrite", WriteOperationType.INSERT_OVERWRITE, recordType);
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testInsertOverwriteTable(HoodieRecordType recordType) throws Exception {
    testDeltaStreamerWithSpecifiedOperation(basePath + "/insert_overwrite_table", WriteOperationType.INSERT_OVERWRITE_TABLE, recordType);
  }

  @Disabled("Local run passing; flaky in CI environment.")
  @Test
  public void testDeletePartitions() throws Exception {
    prepareParquetDFSSource(false, false, "source.avsc", "target.avsc",
        PROPS_FILENAME_TEST_PARQUET, PARQUET_SOURCE_ROOT, false, "partition_path");
    String tableBasePath = basePath + "/test_parquet_table" + testNum;
    HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.INSERT, ParquetDFSSource.class.getName(),
            null, PROPS_FILENAME_TEST_PARQUET, false,
            false, 100000, false, null, null, "timestamp", null), jsc);
    deltaStreamer.sync();
    TestHelpers.assertRecordCount(PARQUET_NUM_RECORDS, tableBasePath, sqlContext);
    testNum++;

    prepareParquetDFSFiles(PARQUET_NUM_RECORDS, PARQUET_SOURCE_ROOT);
    prepareParquetDFSSource(false, false);
    // set write operation to DELETE_PARTITION and add transformer to filter only for records with partition HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION
    deltaStreamer = new HoodieDeltaStreamer(
        TestHelpers.makeConfig(tableBasePath, WriteOperationType.DELETE_PARTITION, ParquetDFSSource.class.getName(),
            Collections.singletonList(TestSpecificPartitionTransformer.class.getName()), PROPS_FILENAME_TEST_PARQUET, false,
            false, 100000, false, null, null, "timestamp", null), jsc);
    deltaStreamer.sync();
    // No records should match the HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION.
    TestHelpers.assertNoPartitionMatch(tableBasePath, sqlContext, HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH);
  }

  @Test
  public void testToSortedTruncatedStringSecretsMasked() {
    TypedProperties props =
        new DFSPropertiesConfiguration(fs.getConf(), new Path(basePath + "/" + PROPS_FILENAME_TEST_SOURCE)).getProps();
    props.put("ssl.trustore.location", "SSL SECRET KEY");
    props.put("sasl.jaas.config", "SASL SECRET KEY");
    props.put("auth.credentials", "AUTH CREDENTIALS");
    props.put("auth.user.info", "AUTH USER INFO");

    String truncatedKeys = HoodieDeltaStreamer.toSortedTruncatedString(props);
    assertFalse(truncatedKeys.contains("SSL SECRET KEY"));
    assertFalse(truncatedKeys.contains("SASL SECRET KEY"));
    assertFalse(truncatedKeys.contains("AUTH CREDENTIALS"));
    assertFalse(truncatedKeys.contains("AUTH USER INFO"));
    assertTrue(truncatedKeys.contains("SENSITIVE_INFO_MASKED"));
  }

  void testDeltaStreamerWithSpecifiedOperation(final String tableBasePath, WriteOperationType operationType, HoodieRecordType recordType) throws Exception {
    // Initial insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT);
    TestHelpers.addRecordMerger(recordType, cfg.configs);
    new HoodieDeltaStreamer(cfg, jsc).sync();
    TestHelpers.assertRecordCount(1000, tableBasePath, sqlContext);
    TestHelpers.assertDistanceCount(1000, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00000", tableBasePath, fs, 1);

    // setting the operationType
    cfg.operation = operationType;
    // No new data => no commits.
    cfg.sourceLimit = 0;
    new HoodieDeltaStreamer(cfg, jsc).sync();
    TestHelpers.assertRecordCount(1000, tableBasePath, sqlContext);
    TestHelpers.assertDistanceCount(1000, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00000", tableBasePath, fs, 1);

    cfg.sourceLimit = 1000;
    new HoodieDeltaStreamer(cfg, jsc).sync();
    TestHelpers.assertRecordCount(1950, tableBasePath, sqlContext);
    TestHelpers.assertDistanceCount(1950, tableBasePath, sqlContext);
    TestHelpers.assertCommitMetadata("00001", tableBasePath, fs, 2);
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @Test
  public void testFetchingCheckpointFromPreviousCommits() throws IOException {
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(basePath + "/testFetchPreviousCheckpoint", WriteOperationType.BULK_INSERT);

    TypedProperties properties = new TypedProperties();
    properties.setProperty("hoodie.datasource.write.recordkey.field","key");
    properties.setProperty("hoodie.datasource.write.partitionpath.field","pp");
    TestDeltaSync testDeltaSync = new TestDeltaSync(cfg, sparkSession, null, properties,
        jsc, fs, jsc.hadoopConfiguration(), null);

    properties.put(HoodieTableConfig.NAME.key(), "sample_tbl");
    HoodieTableMetaClient metaClient = HoodieTestUtils.init(jsc.hadoopConfiguration(), basePath, HoodieTableType.COPY_ON_WRITE, properties);

    Map<String, String> extraMetadata = new HashMap<>();
    extraMetadata.put(HoodieWriteConfig.DELTASTREAMER_CHECKPOINT_KEY, "abc");
    addCommitToTimeline(metaClient, extraMetadata);
    metaClient.reloadActiveTimeline();
    assertEquals(testDeltaSync.getLatestCommitMetadataWithValidCheckpointInfo(metaClient.getActiveTimeline()
        .getCommitsTimeline()).get().getMetadata(CHECKPOINT_KEY), "abc");

    extraMetadata.put(HoodieWriteConfig.DELTASTREAMER_CHECKPOINT_KEY, "def");
    addCommitToTimeline(metaClient, extraMetadata);
    metaClient.reloadActiveTimeline();
    assertEquals(testDeltaSync.getLatestCommitMetadataWithValidCheckpointInfo(metaClient.getActiveTimeline()
        .getCommitsTimeline()).get().getMetadata(CHECKPOINT_KEY), "def");

    // add a replace commit which does not have CEHCKPOINT_KEY. Deltastreamer should be able to go back and pick the right checkpoint.
    addReplaceCommitToTimeline(metaClient, Collections.emptyMap());
    metaClient.reloadActiveTimeline();
    assertEquals(testDeltaSync.getLatestCommitMetadataWithValidCheckpointInfo(metaClient.getActiveTimeline()
        .getCommitsTimeline()).get().getMetadata(CHECKPOINT_KEY), "def");
  }

  @ParameterizedTest
  @EnumSource(value = HoodieRecordType.class, names = {"AVRO", "SPARK"})
  public void testDropPartitionColumns(HoodieRecordType recordType) throws Exception {
    String tableBasePath = basePath + "/test_drop_partition_columns" + testNum++;
    // ingest data with dropping partition columns enabled
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.UPSERT);
    TestHelpers.addRecordMerger(recordType, cfg.configs);
    cfg.configs.add(String.format("%s=%s", HoodieTableConfig.DROP_PARTITION_COLUMNS.key(), "true"));
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    ds.sync();
    // assert ingest successful
    TestHelpers.assertAtLeastNCommits(1, tableBasePath, fs);

    TableSchemaResolver tableSchemaResolver = new TableSchemaResolver(
            HoodieTableMetaClient.builder().setBasePath(tableBasePath).setConf(fs.getConf()).build());
    // get schema from data file written in the latest commit
    Schema tableSchema = tableSchemaResolver.getTableAvroSchemaFromDataFile();
    assertNotNull(tableSchema);

    List<String> tableFields = tableSchema.getFields().stream().map(Schema.Field::name).collect(Collectors.toList());
    // now assert that the partition column is not in the target schema
    assertFalse(tableFields.contains("partition_path"));
    UtilitiesTestBase.Helpers.deleteFileFromDfs(fs, tableBasePath);
  }

  @Test
  public void testForceEmptyMetaSync() throws Exception {
    String tableBasePath = basePath + "/test_force_empty_meta_sync";

    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(tableBasePath, WriteOperationType.BULK_INSERT);
    cfg.sourceLimit = 0;
    cfg.allowCommitOnNoCheckpointChange = true;
    cfg.enableMetaSync = true;
    cfg.forceEmptyMetaSync = true;

    new HoodieDeltaStreamer(cfg, jsc, fs, hiveServer.getHiveConf()).sync();
    TestHelpers.assertRecordCount(0, tableBasePath, sqlContext);

    // make sure hive table is present
    HiveSyncConfig hiveSyncConfig = getHiveSyncConfig(tableBasePath, "hive_trips");
    hiveSyncConfig.setHadoopConf(hiveServer.getHiveConf());
    HoodieHiveSyncClient hiveClient = new HoodieHiveSyncClient(hiveSyncConfig);
    final String tableName = hiveSyncConfig.getString(META_SYNC_TABLE_NAME);
    assertTrue(hiveClient.tableExists(tableName), "Table " + tableName + " should exist");
  }

  class TestDeltaSync extends DeltaSync {

    public TestDeltaSync(HoodieDeltaStreamer.Config cfg, SparkSession sparkSession, SchemaProvider schemaProvider, TypedProperties props,
                         JavaSparkContext jssc, FileSystem fs, Configuration conf,
                         Function<SparkRDDWriteClient, Boolean> onInitializingHoodieWriteClient) throws IOException {
      super(cfg, sparkSession, schemaProvider, props, jssc, fs, conf, onInitializingHoodieWriteClient);
    }

    public Option<HoodieCommitMetadata> getLatestCommitMetadataWithValidCheckpointInfo(HoodieTimeline timeline) throws IOException {
      return super.getLatestCommitMetadataWithValidCheckpointInfo(timeline);
    }
  }

  /**
   * UDF to calculate Haversine distance.
   */
  public static class DistanceUDF implements UDF4<Double, Double, Double, Double, Double> {

    /**
     * Returns some random number as distance between the points.
     *
     * @param lat1 Latitiude of source
     * @param lat2 Latitude of destination
     * @param lon1 Longitude of source
     * @param lon2 Longitude of destination
     */
    @Override
    public Double call(Double lat1, Double lat2, Double lon1, Double lon2) {
      return RANDOM.nextDouble();
    }
  }

  /**
   * Adds a new field "haversine_distance" to the row.
   */
  public static class TripsWithDistanceTransformer implements Transformer {

    @Override
    public Dataset<Row> apply(JavaSparkContext jsc, SparkSession sparkSession, Dataset<Row> rowDataset,
                              TypedProperties properties) {
      rowDataset.sqlContext().udf().register("distance_udf", new DistanceUDF(), DataTypes.DoubleType);
      return rowDataset.withColumn("haversine_distance", functions.callUDF("distance_udf", functions.col("begin_lat"),
          functions.col("end_lat"), functions.col("begin_lon"), functions.col("end_lat")));
    }
  }

  public static class TestGenerator extends SimpleKeyGenerator {

    public TestGenerator(TypedProperties props) {
      super(props);
    }
  }

  public static class TestTableLevelGenerator extends SimpleKeyGenerator {

    public TestTableLevelGenerator(TypedProperties props) {
      super(props);
    }
  }

  public static class DummyAvroPayload extends OverwriteWithLatestAvroPayload {

    public DummyAvroPayload(GenericRecord gr, Comparable orderingVal) {
      super(gr, orderingVal);
    }
  }

  /**
   * Return empty table.
   */
  public static class DropAllTransformer implements Transformer {

    @Override
    public Dataset apply(JavaSparkContext jsc, SparkSession sparkSession, Dataset<Row> rowDataset,
                         TypedProperties properties) {
      System.out.println("DropAllTransformer called !!");
      return sparkSession.createDataFrame(jsc.emptyRDD(), rowDataset.schema());
    }
  }

  public static class TestIdentityTransformer implements Transformer {

    @Override
    public Dataset<Row> apply(JavaSparkContext jsc, SparkSession sparkSession, Dataset<Row> rowDataset,
                              TypedProperties properties) {
      return rowDataset;
    }
  }

  public static class TestSpecificPartitionTransformer implements Transformer {

    @Override
    public Dataset<Row> apply(JavaSparkContext jsc, SparkSession sparkSession, Dataset<Row> rowDataset,
                              TypedProperties properties) {
      Dataset<Row> toReturn = rowDataset.filter("partition_path == '" + HoodieTestDataGenerator.DEFAULT_FIRST_PARTITION_PATH + "'");
      return toReturn;
    }
  }

  /**
   * Add new field evoluted_optional_union_field with value of the field rider.
   */
  public static class TripsWithEvolvedOptionalFieldTransformer implements Transformer {

    @Override
    public Dataset<Row> apply(JavaSparkContext jsc, SparkSession sparkSession, Dataset<Row> rowDataset,
                              TypedProperties properties) {
      return rowDataset.withColumn("evoluted_optional_union_field", functions.col("rider"));
    }
  }

  /**
   * {@link FilebasedSchemaProvider} to be used in tests where target schema is null.
   */
  public static class TestFileBasedSchemaProviderNullTargetSchema extends FilebasedSchemaProvider {

    public TestFileBasedSchemaProviderNullTargetSchema(TypedProperties props, JavaSparkContext jssc) {
      super(props, jssc);
    }

    @Override
    public Schema getTargetSchema() {
      return null;
    }
  }

  private static Stream<Arguments> testORCDFSSource() {
    // arg1 boolean useSchemaProvider, arg2 List<String> transformerClassNames
    return Stream.of(
        arguments(false, null),
        arguments(true, Collections.singletonList(TripsWithDistanceTransformer.class.getName()))
    );
  }

}
