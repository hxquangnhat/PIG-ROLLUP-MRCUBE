/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pig.backend.hadoop.executionengine.mapReduceLayer;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.joda.time.DateTimeZone;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileSystem.Statistics;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.Counters;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobID;
import org.apache.hadoop.mapred.Task;
import org.apache.hadoop.mapred.TaskReport;
import org.apache.hadoop.mapred.jobcontrol.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.pig.PigConfiguration;
import org.apache.pig.PigException;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.hadoop.HDataType;
import org.apache.pig.backend.hadoop.datastorage.ConfigurationUtil;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.partitioners.RollupH2IRGAutoPivotPartitioner;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.partitioners.RollupH2IRGPartitioner;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.POStatus;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.PhysicalOperator;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.Result;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.expressionOperators.POUserFunc;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.expressionOperators.POUserFuncRollupSample;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhysicalPlan;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POForEach;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POJoinPackage;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POLocalRearrange;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POPackage;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POPreCombinerLocalRearrange;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.PORollupCombinerPackage;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.PORollupH2IRGForEach;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.PORollupSampling;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POStore;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.PORollupH2IRGForEach.TupleComparator;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.util.PlanHelper;
import org.apache.pig.backend.hadoop.executionengine.util.MapRedUtil;
import org.apache.pig.builtin.LinearRegressionModel;
import org.apache.pig.builtin.PigStorage;
import org.apache.pig.builtin.RegressionModel;
import org.apache.pig.builtin.RollupDimensions;
import org.apache.pig.data.BagFactory;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.DataType;
import org.apache.pig.data.SchemaTupleBackend;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.PigContext;
import org.apache.pig.impl.io.NullablePartitionWritable;
import org.apache.pig.impl.io.NullableTuple;
import org.apache.pig.impl.io.PigNullableWritable;
import org.apache.pig.impl.plan.DependencyOrderWalker;
import org.apache.pig.impl.plan.VisitorException;
import org.apache.pig.impl.util.ObjectSerializer;
import org.apache.pig.impl.util.Pair;
import org.apache.pig.impl.util.SpillableMemoryManager;
import org.apache.pig.impl.util.UDFContext;
import org.apache.pig.tools.pigstats.PigStatusReporter;

/**
 * This class is the static Mapper &amp; Reducer classes that are used by Pig to
 * execute Pig Map Reduce jobs. Since there is a reduce phase, the leaf is bound
 * to be a POLocalRearrange. So the map phase has to separate the key and tuple
 * and collect it into the output collector.
 * 
 * The shuffle and sort phase sorts these keys &amp; tuples and creates key,
 * List&lt;Tuple&gt; and passes the key and iterator to the list. The
 * deserialized POPackage operator is used to package the key, List&lt;Tuple&gt;
 * into pigKey, Bag&lt;Tuple&gt; where pigKey is of the appropriate pig type and
 * then the result of the package is attached to the reduce plan which is
 * executed if its not empty. Either the result of the reduce plan or the
 * package res is collected into the output collector.
 *
 * The index of the tuple (that is, which bag it should be placed in by the
 * package) is packed into the key. This is done so that hadoop sorts the keys
 * in order of index for join.
 *
 * This class is the base class for PigMapReduce, which has slightly difference
 * among different versions of hadoop. PigMapReduce implementation is located in
 * $PIG_HOME/shims.
 */
public class PigGenericMapReduce {

    public static JobContext sJobContext = null;

    /**
     * @deprecated Use {@link UDFContext} instead in the following way to get
     *             the job's {@link Configuration}:
     * 
     *             <pre>
     * UdfContext.getUdfContext().getJobConf()
     * </pre>
     */
    @Deprecated
    public static Configuration sJobConf = null;

    public static final ThreadLocal<Configuration> sJobConfInternal = new ThreadLocal<Configuration>();

    public static class Map extends PigMapBase {

        protected long sTime = 0;
        protected long fTime = 0;
        protected long mapWriteTime = 0;
        protected final Log log = LogFactory.getLog(getClass());
        
        protected TupleFactory mTupleFactory = TupleFactory.getInstance();
        protected Tuple tmpKey = mTupleFactory.newTuple();
        protected Tuple tmpVal = mTupleFactory.newTuple();
        protected Tuple tmpValCbn = mTupleFactory.newTuple();
        protected Long value = (long) 0;
        protected int dimensionSize = 0;
        
        protected boolean isH2IRG = false;

        protected HashMap<Integer, Integer> mrcube = new HashMap<Integer, Integer>();

        @Override
        public void setup(Context oc) throws InterruptedException, IOException {

            sTime = System.currentTimeMillis();
            log.debug("Mapper Setup");
            Configuration conf = oc.getConfiguration();

            isH2IRG = conf.getBoolean(
                    PigConfiguration.PIG_H2IRG_ROLLUP_OPTIMIZABLE, false);

            dimensionSize = conf.getInt(PigConfiguration.PIG_H2IRG_ROLLUP_TOTAL_FIELD, 0);
            
            if (isH2IRG) {

                Path pPivot = new Path("/tmp/partition/mrcube");
                FileSystem fs;
                fs = FileSystem.get(conf);
                BufferedReader br = new BufferedReader(new InputStreamReader(fs
                        .open(pPivot)));
                String line = null;
                while ((line = br.readLine()) != null) {
                    String splitted[] = line.split(" ");
                    mrcube.put(Integer.parseInt(splitted[2]), Integer
                            .parseInt(splitted[1]));
                }
                br.close();
            }
            super.setup(oc);
        }

        protected void setTuple(Tuple from, Tuple to) {
            try {
            if (to.size() == 0)
                for (int i = 0; i < from.size(); i++)
                    to.append(from.get(i));
            else
                for (int i = 0; i < from.size(); i++)
                    to.set(i, from.get(i));
            } catch (ExecException e) {
                e.printStackTrace();
            }
        }
        
        @Override
        public void collect(Context oc, Tuple tuple)
                throws InterruptedException, IOException {
            Byte index = (Byte) tuple.get(0);
            PigNullableWritable key = null;
            int count = 0;
            for (int i = 0; i < ((Tuple) tuple.get(1)).size(); i++)
                if (((Tuple) tuple.get(1)).get(i) == null)
                    count++;
            
            setTuple((Tuple) tuple.get(2), tmpVal);
            setTuple((Tuple) tmpVal.get(0), tmpValCbn);
            value = (Long) tmpValCbn.get(0);
            
            if (mrcube.get(count) == 0)
                ((Tuple) tuple.get(1)).append((long)-1);
            else
                ((Tuple) tuple.get(1)).append(value % mrcube.get(count));
            
            //key = HDataType.getWritableComparableTypes(tmpKey, keyType);
            key = HDataType.getWritableComparableTypes((Tuple) tuple.get(1), keyType);
            NullableTuple val = new NullableTuple((Tuple) tuple.get(2));
            // Both the key and the value need the index. The key needs it so
            // that it can be sorted on the index in addition to the key
            // value. The value needs it so that POPackage can properly
            // assign the tuple to its slot in the projection.
            key.setIndex(index);
            val.setIndex(index);

            // sTime = System.currentTimeMillis();
            oc.write(key, val);
            // System.out.println(key + " " + val);
            // mapWriteTime += System.currentTimeMillis() - sTime;
        }

        @Override
        public void cleanup(Context oc) throws InterruptedException,
                IOException {
            super.cleanup(oc);
        }
    }

    /**
     * This Mapper class is used for the samplign job of rollup Each key will be
     * output with the value is 1
     */
    public static class MapRollupSample extends PigMapBaseRollupSample {

        protected final Log log = LogFactory.getLog(getClass());

        long sTime = 0;
        long fTime = 0;

        long swTime = 0;
        long fwTime = 0;

        long mapWriteTime = 0;

        long mapWrite[] = new long[7];

        @Override
        public void setup(Context oc) throws InterruptedException, IOException {
            sTime = System.currentTimeMillis();
            log.debug("Mapper Setup");
            // Configuration jConf = oc.getConfiguration();
            // int length =
            // jConf.getInt(PigConfiguration.PIG_H2IRG_ROLLUP_TOTAL_FIELD, 0);
            // mapWrite = new long[length+1];
            super.setup(oc);
        }

        @Override
        public void collect(Context oc, Tuple tuple)
                throws InterruptedException, IOException {

            Byte index = (Byte) tuple.get(0);
            PigNullableWritable key = HDataType.getWritableComparableTypes(
                    tuple.get(1), keyType);
            // NullableTuple val = new NullableTuple((Tuple)tuple.get(2));
            Tuple tmpVal = (Tuple) tuple.get(2);
            ((Tuple) tmpVal.get(0)).set(0, new Integer(1));
            NullableTuple val = new NullableTuple(tmpVal);
            key.setIndex(index);
            val.setIndex(index);
            oc.write(key, val);
        }
    }

    /**
     * This "specialized" map class is ONLY to be used in pig queries with order
     * by a udf. A UDF used for comparison in the order by expects to be handed
     * tuples. Hence this map class ensures that the "key" used in the order by
     * is wrapped into a tuple (if it isn't already a tuple)
     */
    public static class MapWithComparator extends PigMapBase {

        @Override
        public void collect(Context oc, Tuple tuple)
                throws InterruptedException, IOException {

            Object keyTuple = null;
            if (keyType != DataType.TUPLE) {
                Object k = tuple.get(1);
                keyTuple = tf.newTuple(k);
            } else {
                keyTuple = tuple.get(1);
            }

            Byte index = (Byte) tuple.get(0);
            PigNullableWritable key = HDataType.getWritableComparableTypes(
                    keyTuple, DataType.TUPLE);
            NullableTuple val = new NullableTuple((Tuple) tuple.get(2));

            // Both the key and the value need the index. The key needs it so
            // that it can be sorted on the index in addition to the key
            // value. The value needs it so that POPackage can properly
            // assign the tuple to its slot in the projection.
            key.setIndex(index);
            val.setIndex(index);

            oc.write(key, val);
        }
    }

    /**
     * Used by Skewed Join
     */
    public static class MapWithPartitionIndex extends Map {

        @Override
        public void collect(Context oc, Tuple tuple)
                throws InterruptedException, IOException {

            Byte tupleKeyIdx = 2;
            Byte tupleValIdx = 3;

            Byte index = (Byte) tuple.get(0);
            Integer partitionIndex = -1;
            // for partitioning table, the partition index isn't present
            if (tuple.size() == 3) {
                // super.collect(oc, tuple);
                // return;
                tupleKeyIdx--;
                tupleValIdx--;
            } else {
                partitionIndex = (Integer) tuple.get(1);
            }

            PigNullableWritable key = HDataType.getWritableComparableTypes(
                    tuple.get(tupleKeyIdx), keyType);

            NullablePartitionWritable wrappedKey = new NullablePartitionWritable(
                    key);

            NullableTuple val = new NullableTuple((Tuple) tuple
                    .get(tupleValIdx));

            // Both the key and the value need the index. The key needs it so
            // that it can be sorted on the index in addition to the key
            // value. The value needs it so that POPackage can properly
            // assign the tuple to its slot in the projection.
            wrappedKey.setIndex(index);

            // set the partition
            wrappedKey.setPartition(partitionIndex);
            val.setIndex(index);
            oc.write(wrappedKey, val);
        }

        @Override
        protected void runPipeline(PhysicalOperator leaf) throws IOException,
                InterruptedException {

            while (true) {
                Result res = leaf.getNextTuple();

                if (res.returnStatus == POStatus.STATUS_OK) {
                    // For POPartitionRearrange, the result is a bag.
                    // This operator is used for skewed join
                    if (res.result instanceof DataBag) {
                        Iterator<Tuple> its = ((DataBag) res.result).iterator();
                        while (its.hasNext()) {
                            collect(outputCollector, its.next());
                        }
                    } else {
                        collect(outputCollector, (Tuple) res.result);
                    }
                    continue;
                }

                if (res.returnStatus == POStatus.STATUS_EOP) {
                    return;
                }

                if (res.returnStatus == POStatus.STATUS_NULL) {
                    continue;
                }

                if (res.returnStatus == POStatus.STATUS_ERR) {
                    // remember that we had an issue so that in
                    // close() we can do the right thing
                    errorInMap = true;
                    // if there is an errmessage use it
                    String errMsg;
                    if (res.result != null) {
                        errMsg = "Received Error while "
                                + "processing the map plan: " + res.result;
                    } else {
                        errMsg = "Received Error while "
                                + "processing the map plan.";
                    }

                    int errCode = 2055;
                    throw new ExecException(errMsg, errCode, PigException.BUG);
                }
            }
        }
    }

    abstract public static class Reduce
            extends
            Reducer<PigNullableWritable, NullableTuple, PigNullableWritable, Writable> {

        protected final Log log = LogFactory.getLog(getClass());

        // The reduce plan
        protected PhysicalPlan rp = null;

        // Store operators
        protected List<POStore> stores;

        protected long mapInputRecords;

        // The POPackage operator which is the
        // root of every Map Reduce plan is
        // obtained through the job conf. The portion
        // remaining after its removal is the reduce
        // plan
        protected POPackage pack;

        ProgressableReporter pigReporter;

        protected Context outputCollector;

        protected boolean errorInReduce = false;

        PhysicalOperator[] roots;

        private PhysicalOperator leaf;

        PigContext pigContext = null;
        protected volatile boolean initialized = false;

        private boolean inIllustrator = false;

        protected long writeToDiskTime = 0;

        protected long reduceWholeStime = 0;

        protected long reduceWholeFtime = 0;

        protected long swTime = 0;

        protected long packTime = 0;

        protected long leafTime = 0;

        protected int lengthTuple = 0;

        protected boolean receivedLast = false;
        
        protected TupleFactory tf = TupleFactory.getInstance();

        protected HashMap<String, List<Long>> hm = new HashMap<String, List<Long>>();
        
        protected FileSystem fs;
        protected FSDataOutputStream pOut;

        /**
         * Set the reduce plan: to be used by local runner for illustrator
         * 
         * @param plan
         *            Reduce plan
         */
        public void setReducePlan(PhysicalPlan plan) {
            rp = plan;
        }

        /**
         * Configures the Reduce plan, the POPackage operator and the reporter
         * thread
         */
        @SuppressWarnings("unchecked")
        @Override
        protected void setup(Context context) throws IOException,
                InterruptedException {
            super.setup(context);
            reduceWholeStime = System.currentTimeMillis();
            inIllustrator = inIllustrator(context);
            if (inIllustrator)
                pack = getPack(context);
            Configuration jConf = context.getConfiguration();
            SpillableMemoryManager.configure(ConfigurationUtil
                    .toProperties(jConf));
            sJobContext = context;
            sJobConfInternal.set(context.getConfiguration());
            sJobConf = context.getConfiguration();
            try {
                PigContext
                        .setPackageImportList((ArrayList<String>) ObjectSerializer
                                .deserialize(jConf.get("udf.import.list")));
                pigContext = (PigContext) ObjectSerializer.deserialize(jConf
                        .get("pig.pigContext"));

                // This attempts to fetch all of the generated code from the
                // distributed cache, and resolve it
                SchemaTupleBackend.initialize(jConf, pigContext);

                if (rp == null)
                    rp = (PhysicalPlan) ObjectSerializer.deserialize(jConf
                            .get("pig.reducePlan"));
                stores = PlanHelper.getPhysicalOperators(rp, POStore.class);

                if (!inIllustrator)
                    pack = (POPackage) ObjectSerializer.deserialize(jConf
                            .get("pig.reduce.package"));
                // To be removed
                if (rp.isEmpty())
                    log.debug("Reduce Plan empty!");
                else {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    rp.explain(baos);
                    log.debug(baos.toString());
                }
                pigReporter = new ProgressableReporter();
                if (!(rp.isEmpty())) {
                    roots = rp.getRoots().toArray(new PhysicalOperator[1]);
                    leaf = rp.getLeaves().get(0);
                }

                // Get the UDF specific context
                MapRedUtil.setupUDFContext(jConf);

            } catch (IOException ioe) {
                String msg = "Problem while configuring reduce plan.";
                throw new RuntimeException(msg, ioe);
            }
            log.info("Aliases being processed per job phase (AliasName[line,offset]): "
                    + jConf.get("pig.alias.location"));

            String dtzStr = PigMapReduce.sJobConfInternal.get().get(
                    "pig.datetime.default.tz");
            if (dtzStr != null && dtzStr.length() > 0) {
                // ensure that the internal timezone is uniformly in UTC offset
                // style
                DateTimeZone.setDefault(DateTimeZone
                        .forOffsetMillis(DateTimeZone.forID(dtzStr).getOffset(
                                null)));
            }
            lengthTuple = jConf.getInt(
                    PigConfiguration.PIG_H2IRG_ROLLUP_TOTAL_FIELD, 0);
            fs = FileSystem.get(context.getConfiguration());
            String taskID = context.getTaskAttemptID().toString();
            String outputDir = context.getConfiguration().get("pig.reduce.output.dirs");
            String outputTmp = outputDir.substring(0, outputDir.indexOf("friendly")) + "unfriendly/" + taskID;
            pOut = fs.create(new Path(outputTmp));
        }

        /**
         * The reduce function which packages the key and List&lt;Tuple&gt; into
         * key, Bag&lt;Tuple&gt; after converting Hadoop type key into Pig type.
         * The package result is either collected as is, if the reduce plan is
         * empty or after passing through the reduce plan.
         */
        @Override
        protected void reduce(PigNullableWritable key,
                Iterable<NullableTuple> tupIter, Context context)
                throws IOException, InterruptedException {

            if (!initialized) {
                initialized = true;

                // cache the collector for use in runPipeline()
                // which could additionally be called from close()
                this.outputCollector = context;
                pigReporter.setRep(context);
                PhysicalOperator.setReporter(pigReporter);

                boolean aggregateWarning = "true".equalsIgnoreCase(pigContext
                        .getProperties().getProperty("aggregate.warning"));

                PigHadoopLogger pigHadoopLogger = PigHadoopLogger.getInstance();
                pigHadoopLogger.setAggregate(aggregateWarning);
                PigStatusReporter.setContext(context);
                pigHadoopLogger.setReporter(PigStatusReporter.getInstance());

                PhysicalOperator.setPigLogger(pigHadoopLogger);

                if (!inIllustrator)
                    for (POStore store : stores) {
                        MapReducePOStoreImpl impl = new MapReducePOStoreImpl(
                                context);
                        store.setStoreImpl(impl);
                        store.setUp();
                    }
            }

            // In the case we optimize the join, we combine
            // POPackage and POForeach - so we could get many
            // tuples out of the getnext() call of POJoinPackage
            // In this case, we process till we see EOP from
            // POJoinPacakage.getNext()
            if (pack instanceof POJoinPackage) {
                pack.attachInput(key, tupIter.iterator());
                while (true) {
                    if (processOnePackageOutput(context))
                        break;
                }
            } else {
                // join is not optimized, so package will
                // give only one tuple out for the key
                Tuple tmpKey = (Tuple) key.getValueAsPigType();
                int l = tmpKey.size();
                /*
                 * if(l > lengthTuple + 1) processSamplingTuple(tmpKey); else {
                 * if(l == lengthTuple +1) if (receivedLast == false)
                 * receivedLast = true; else return; pack.attachInput(key,
                 * tupIter.iterator()); processOnePackageOutput(context); }
                 */
                if (tmpKey.get(0) == null && tmpKey.get(1) != null)
                    processSamplingTuple(tmpKey);
                else {
                    pack.attachInput(key, tupIter.iterator());
                    processOnePackageOutput(context);
                }
            }
        }

        public void processSamplingTuple(Tuple tmpKey) throws ExecException {
            int counterType = (Integer) tmpKey.get(1);
            // if (counterType == 1) {
            String id = (String) tmpKey.get(2);
            List<Long> t = new ArrayList<Long>();
            long Total = (Long) tmpKey.get(3);
            long Start = (Long) tmpKey.get(4);
            long Finish = (Long) tmpKey.get(5);
            long Write = (Long) tmpKey.get(6);
            long ReadParse = (Long) tmpKey.get(7);
            long MapFunc = (Long) tmpKey.get(8);
            long Hash = (Long) tmpKey.get(9);
            if (hm.containsKey(id))
                t = hm.get(id);
            t.add(new Long(counterType * -1));
            t.add(Total);
            t.add(Start);
            t.add(Finish);
            t.add(Write);
            t.add(ReadParse);
            t.add(MapFunc);
            t.add(Hash);
            hm.put(id, t);/*
                           * } else { String id = (String)tmpKey.get(2);
                           * List<Long> t = new ArrayList<Long>(); long cbnTotal
                           * = (Long)tmpKey.get(3); long cbnStart =
                           * (Long)tmpKey.get(4); long cbnFinish =
                           * (Long)tmpKey.get(5); long cbnWrite =
                           * (Long)tmpKey.get(6); if(hm.containsKey(id)) t =
                           * hm.get(id); t.add(new Long(-2)); t.add(cbnTotal);
                           * t.add(cbnStart); t.add(cbnFinish); t.add(cbnWrite);
                           * hm.put(id, t); }
                           */
        }

        // return: false-more output
        // true- end of processing
        public boolean processOnePackageOutput(Context oc) throws IOException,
                InterruptedException {

            long packStart = System.currentTimeMillis();
            Result res = pack.getNextTuple();
            packTime += System.currentTimeMillis() - packStart;
            if (res.returnStatus == POStatus.STATUS_OK) {
                Tuple packRes = (Tuple) res.result;

                if (rp.isEmpty()) {
                    swTime = System.currentTimeMillis();
                    oc.write(null, packRes);
                    writeToDiskTime += System.currentTimeMillis() - swTime;

                    return false;
                }
                for (int i = 0; i < roots.length; i++) {
                    roots[i].attachInput(packRes);
                }
                long leafStart = System.currentTimeMillis();
                runPipeline(leaf);
                leafTime += System.currentTimeMillis() - leafStart;

            }

            if (res.returnStatus == POStatus.STATUS_NULL) {
                return false;
            }

            if (res.returnStatus == POStatus.STATUS_ERR) {
                int errCode = 2093;
                String msg = "Encountered error in package operator while processing group.";
                throw new ExecException(msg, errCode, PigException.BUG);
            }

            if (res.returnStatus == POStatus.STATUS_EOP) {
                return true;
            }

            return false;

        }

        /**
         * @param leaf
         * @throws InterruptedException
         * @throws IOException
         */
        protected void runPipeline(PhysicalOperator leaf)
                throws InterruptedException, IOException {

            while (true) {
                Result redRes = leaf.getNextTuple();
                if (redRes.returnStatus == POStatus.STATUS_OK) {
                    try {
                        swTime = System.currentTimeMillis();
                        System.out.println(redRes.toString());
                        Tuple key = tf.newTuple();
                        for (int i = 0; i < lengthTuple; i++)
                            key.append(((Tuple)((Tuple)redRes.result).get(0)).get(i));
                        if ((Long)((Tuple)((Tuple)redRes.result).get(0)).get(lengthTuple) < (long)0) {
                            Tuple changed = tf.newTuple();
                            changed.append(key);
                            changed.append(((Tuple)redRes.result).get(1));
                            outputCollector.write(null, changed);
                        }else {
                            //String out = changed.toDelimitedString("\t") + "\t" + ((Tuple)redRes.result).get(1) + "\n";
                            String out = key.toDelimitedString("\t") + "\t" + ((Tuple)redRes.result).get(1) + "\n";
                            pOut.writeBytes(out);
                        }
                        writeToDiskTime += System.currentTimeMillis() - swTime;
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                    continue;
                }

                if (redRes.returnStatus == POStatus.STATUS_EOP) {
                    return;
                }

                if (redRes.returnStatus == POStatus.STATUS_NULL) {
                    continue;
                }

                if (redRes.returnStatus == POStatus.STATUS_ERR) {
                    // remember that we had an issue so that in
                    // close() we can do the right thing
                    errorInReduce = true;
                    // if there is an errmessage use it
                    String msg;
                    if (redRes.result != null) {
                        msg = "Received Error while "
                                + "processing the reduce plan: "
                                + redRes.result;
                    } else {
                        msg = "Received Error while "
                                + "processing the reduce plan.";
                    }
                    int errCode = 2090;
                    throw new ExecException(msg, errCode, PigException.BUG);
                }
            }
        }

        /**
         * Will be called once all the intermediate keys and values are
         * processed. So right place to stop the reporter thread.
         */
        @Override
        protected void cleanup(Context context) throws IOException,
                InterruptedException {
            super.cleanup(context);
            if (errorInReduce) {
                // there was an error in reduce - just return
                return;
            }

            if (PigMapReduce.sJobConfInternal.get().get("pig.stream.in.reduce",
                    "false").equals("true")) {
                // If there is a stream in the pipeline we could
                // potentially have more to process - so lets
                // set the flag stating that all map input has been sent
                // already and then lets run the pipeline one more time
                // This will result in nothing happening in the case
                // where there is no stream in the pipeline
                rp.endOfAllInput = true;
                runPipeline(leaf);
            }

            if (!inIllustrator) {
                for (POStore store : stores) {
                    if (!initialized) {
                        MapReducePOStoreImpl impl = new MapReducePOStoreImpl(
                                context);
                        store.setStoreImpl(impl);
                        store.setUp();
                    }
                    store.tearDown();
                }
            }

            // Calling EvalFunc.finish()
            UDFFinishVisitor finisher = new UDFFinishVisitor(rp,
                    new DependencyOrderWalker<PhysicalOperator, PhysicalPlan>(
                            rp));

            try {
                finisher.visit();
            } catch (VisitorException e) {
                throw new IOException("Error trying to finish UDFs", e);
            }

            PhysicalOperator.setReporter(null);
            initialized = false;
            PORollupH2IRGForEach rollup = null;
            if (leaf instanceof PORollupH2IRGForEach) {
                rollup = (PORollupH2IRGForEach) leaf;
                //ArrayList<Tuple> remained = 
                //rollup.finalBuc(outputCollector);
                rollup.finalBucCleanUp(outputCollector, pOut);
                pOut.close();
                /*for (Tuple t : remained)  
                    outputCollector.write(null, t);*/
            }
        }

        /**
         * Get reducer's illustrator context
         * 
         * @param input
         *            Input buffer as output by maps
         * @param pkg
         *            package
         * @return reducer's illustrator context
         * @throws IOException
         * @throws InterruptedException
         */
        abstract public Context getIllustratorContext(Job job,
                List<Pair<PigNullableWritable, Writable>> input, POPackage pkg)
                throws IOException, InterruptedException;

        abstract public boolean inIllustrator(Context context);

        abstract public POPackage getPack(Context context);
    }

    abstract public static class ReduceRollupSample
            extends
            Reducer<PigNullableWritable, NullableTuple, PigNullableWritable, Writable> {
        protected final Log log = LogFactory.getLog(getClass());

        // The reduce plan
        protected PhysicalPlan rp = null;

        // Store operators
        protected List<POStore> stores;

        protected long mapInputRecords;

        // The POPackage operator which is the
        // root of every Map Reduce plan is
        // obtained through the job conf. The portion
        // remaining after its removal is the reduce
        // plan
        protected POPackage pack;

        ProgressableReporter pigReporter;

        protected Context outputCollector;

        protected boolean errorInReduce = false;

        PhysicalOperator[] roots;

        private PhysicalOperator leaf;

        PigContext pigContext = null;
        protected volatile boolean initialized = false;

        private boolean inIllustrator = false;

        protected boolean receivedLast = false;

        protected HashMap<String, List<Long>> hm = new HashMap<String, List<Long>>();

        protected HashMap<Integer, ArrayList<Tuple>> group = new HashMap<Integer, ArrayList<Tuple>>();

        protected long N = 0;
        protected float r = 0;
        protected long c = 0;
        protected long D = 0;

        protected float ufThreshold = 0;

        protected int lengthTuple = 0;

        protected boolean unfriendly[] = new boolean[7];

        protected HashMap<Integer, ArrayList<Integer>> batching = new HashMap<Integer, ArrayList<Integer>>();

        protected int partitionFactor[] = new int[7];
        
        protected int memLimit = 0;

        /**
         * Set the reduce plan: to be used by local runner for illustrator
         * 
         * @param plan
         *            Reduce plan
         */
        public void setReducePlan(PhysicalPlan plan) {
            rp = plan;
        }

        /**
         * Configures the Reduce plan, the POPackage operator and the reporter
         * thread
         */
        @SuppressWarnings("unchecked")
        @Override
        protected void setup(Context context) throws IOException,
                InterruptedException {
            super.setup(context);
            inIllustrator = inIllustrator(context);
            if (inIllustrator)
                pack = getPack(context);
            Configuration jConf = context.getConfiguration();
            SpillableMemoryManager.configure(ConfigurationUtil
                    .toProperties(jConf));
            sJobContext = context;
            sJobConfInternal.set(context.getConfiguration());
            sJobConf = context.getConfiguration();
            try {
                PigContext
                        .setPackageImportList((ArrayList<String>) ObjectSerializer
                                .deserialize(jConf.get("udf.import.list")));
                pigContext = (PigContext) ObjectSerializer.deserialize(jConf
                        .get("pig.pigContext"));

                // This attempts to fetch all of the generated code from the
                // distributed cache, and resolve it
                SchemaTupleBackend.initialize(jConf, pigContext);

                if (rp == null)
                    rp = (PhysicalPlan) ObjectSerializer.deserialize(jConf
                            .get("pig.reducePlan"));
                stores = PlanHelper.getPhysicalOperators(rp, POStore.class);

                if (!inIllustrator)
                    pack = (POPackage) ObjectSerializer.deserialize(jConf
                            .get("pig.reduce.package"));
                // To be removed
                if (rp.isEmpty())
                    log.debug("Reduce Plan empty!");
                else {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    rp.explain(baos);
                    log.debug(baos.toString());
                }
                pigReporter = new ProgressableReporter();
                if (!(rp.isEmpty())) {
                    roots = rp.getRoots().toArray(new PhysicalOperator[1]);
                    leaf = rp.getLeaves().get(0);
                }

                // Get the UDF specific context
                MapRedUtil.setupUDFContext(jConf);

            } catch (IOException ioe) {
                String msg = "Problem while configuring reduce plan.";
                throw new RuntimeException(msg, ioe);
            }
            log.info("Aliases being processed per job phase (AliasName[line,offset]): "
                    + jConf.get("pig.alias.location"));

            String dtzStr = PigMapReduce.sJobConfInternal.get().get(
                    "pig.datetime.default.tz");
            if (dtzStr != null && dtzStr.length() > 0) {
                // ensure that the internal timezone is uniformly in UTC offset
                // style
                DateTimeZone.setDefault(DateTimeZone
                        .forOffsetMillis(DateTimeZone.forID(dtzStr).getOffset(
                                null)));
            }

            lengthTuple = jConf.getInt(
                    PigConfiguration.PIG_H2IRG_ROLLUP_TOTAL_FIELD, 0);
            
            memLimit = jConf.getInt(PigConfiguration.PIG_H2IRG_ROLLUP_CHUNKSIZE, 512);

            for (int i = 0; i < lengthTuple + 1; i++) {
                group.put(i, new ArrayList<Tuple>());
                unfriendly[i] = false;
                partitionFactor[i] = 0;
            }
        }

        /**
         * The reduce function which packages the key and List&lt;Tuple&gt; into
         * key, Bag&lt;Tuple&gt; after converting Hadoop type key into Pig type.
         * The package result is either collected as is, if the reduce plan is
         * empty or after passing through the reduce plan.
         */
        @Override
        protected void reduce(PigNullableWritable key,
                Iterable<NullableTuple> tupIter, Context context)
                throws IOException, InterruptedException {

            if (!initialized) {
                initialized = true;

                // cache the collector for use in runPipeline()
                // which could additionally be called from close()
                this.outputCollector = context;
                pigReporter.setRep(context);
                PhysicalOperator.setReporter(pigReporter);

                boolean aggregateWarning = "true".equalsIgnoreCase(pigContext
                        .getProperties().getProperty("aggregate.warning"));

                PigHadoopLogger pigHadoopLogger = PigHadoopLogger.getInstance();
                pigHadoopLogger.setAggregate(aggregateWarning);
                PigStatusReporter.setContext(context);
                pigHadoopLogger.setReporter(PigStatusReporter.getInstance());

                PhysicalOperator.setPigLogger(pigHadoopLogger);

                if (!inIllustrator)
                    for (POStore store : stores) {
                        MapReducePOStoreImpl impl = new MapReducePOStoreImpl(
                                context);
                        store.setStoreImpl(impl);
                        store.setUp();
                    }
            }

            // In the case we optimize the join, we combine
            // POPackage and POForeach - so we could get many
            // tuples out of the getnext() call of POJoinPackage
            // In this case, we process till we see EOP from
            // POJoinPacakage.getNext()
            if (pack instanceof POJoinPackage) {
                pack.attachInput(key, tupIter.iterator());
                while (true) {
                    if (processOnePackageOutput(context))
                        break;
                }
            } else {
                // join is not optimized, so package will
                // give only one tuple out for the key
                pack.attachInput(key, tupIter.iterator());
                processOnePackageOutput(context);
            }
        }

        // return: false-more output
        // true- end of processing
        public boolean processOnePackageOutput(Context oc) throws IOException,
                InterruptedException {

            Result res = pack.getNextTuple();
            if (res.returnStatus == POStatus.STATUS_OK) {
                Tuple packRes = (Tuple) res.result;

                if (rp.isEmpty()) {
                    oc.write(null, packRes);

                    return false;
                }
                for (int i = 0; i < roots.length; i++) {
                    roots[i].attachInput(packRes);
                }
                runPipeline(leaf);

            }

            if (res.returnStatus == POStatus.STATUS_NULL) {
                return false;
            }

            if (res.returnStatus == POStatus.STATUS_ERR) {
                int errCode = 2093;
                String msg = "Encountered error in package operator while processing group.";
                throw new ExecException(msg, errCode, PigException.BUG);
            }

            if (res.returnStatus == POStatus.STATUS_EOP) {
                return true;
            }

            return false;

        }

        /**
         * @param leaf
         * @throws InterruptedException
         * @throws IOException
         */
        protected void runPipeline(PhysicalOperator leaf)
                throws InterruptedException, IOException {

            while (true) {
                Result redRes = leaf.getNextTuple();
                if (redRes.returnStatus == POStatus.STATUS_OK) {
                    try {
                        Tuple tmp = (Tuple) redRes.result;
                        Tuple key = (Tuple) tmp.get(0);
                        int count = 0;
                        for (int i = 0; i < key.size(); i++)
                            if (key.get(i) == null)
                                count++;
                        group.get(count).add(tmp);
                        outputCollector.write(null, (Tuple) redRes.result);
                    } catch (Exception e) {
                        throw new IOException(e);
                    }
                    continue;
                }

                if (redRes.returnStatus == POStatus.STATUS_EOP) {
                    return;
                }

                if (redRes.returnStatus == POStatus.STATUS_NULL) {
                    continue;
                }

                if (redRes.returnStatus == POStatus.STATUS_ERR) {
                    // remember that we had an issue so that in
                    // close() we can do the right thing
                    errorInReduce = true;
                    // if there is an errmessage use it
                    String msg;
                    if (redRes.result != null) {
                        msg = "Received Error while "
                                + "processing the reduce plan: "
                                + redRes.result;
                    } else {
                        msg = "Received Error while "
                                + "processing the reduce plan.";
                    }
                    int errCode = 2090;
                    throw new ExecException(msg, errCode, PigException.BUG);
                }
            }
        }

        protected void batch(Context context) throws IOException {
            int batchIndex = 0;
            for (int i = 0; i < unfriendly.length; i++) {
                if (unfriendly[i] == false) {
                    if (!batching.containsKey(batchIndex))
                        batching.put(batchIndex, new ArrayList<Integer>());
                    batching.get(batchIndex).add(i);
                }
            }

            batchIndex++;
            for (int i = 0; i < unfriendly.length; i++) {
                if (unfriendly[i] == true) {
                    if (!batching.containsKey(batchIndex)) {
                        batching.put(batchIndex, new ArrayList<Integer>());
                        batching.get(batchIndex).add(i);
                    } else {
                        if (partitionFactor[i] == partitionFactor[batching.get(
                                batchIndex).get(0)])
                            batching.get(batchIndex).add(i);
                        else {
                            batchIndex++;
                            batching.put(batchIndex, new ArrayList<Integer>());
                            batching.get(batchIndex).add(i);
                        }
                    }
                }
            }

            FileSystem fs = FileSystem.get(context.getConfiguration());
            FSDataOutputStream pOut = fs.create(new Path("/tmp/partition/mrcube"));

            for (Entry<Integer, ArrayList<Integer>> entry : batching.entrySet()) {
                Integer key = entry.getKey();
                ArrayList<Integer> value = entry.getValue();
                String line = key + " " + partitionFactor[value.get(0)] + " ";
                for (Integer i : value)
                    line = line + i + " ";
                line = line + "\n";
                pOut.writeBytes(line);
            }
            pOut.close();
        }

        protected void valuePartition(Context context) throws IOException {

            Configuration jConf = context.getConfiguration();

            N = 21841;// (Long) group.get(lengthTuple).get(0).get(1);

            String inputFile = jConf.get("pig.input.dirs", "");
            float rate = jConf
                    .getFloat("pig.h2irg.rollup.rate", (float) 0.0005);

            if (inputFile != "") {
                Path pPivot = new Path(inputFile);
                FileSystem fs = FileSystem.get(jConf);
                FileStatus stt = fs.getFileStatus(pPivot);
                D = stt.getLen();
            }

            /*
             * String reducerChild = jConf .get("mapred.reduce.child.java.opts",
             * ""); String reducerMem = ""; if (reducerChild != "") reducerMem =
             * reducerChild.split("-Xmx")[1].split("m")[0]; else reducerMem =
             * "2048";
             * 
             * c = Long.parseLong(reducerMem) * 1024 * 1024;
             */

            //c = 256 * 1024;// + 512*1024*1024;
            
            c = memLimit*1024;

            r = (float) (c * 1.0 / D) * 100;

            ufThreshold = (float) (0.75 * r * N);

            ufThreshold = (float) (0.75 * rate * c) / 25;

            System.out.println("r c D N: " + r + " " + c + " " + D + " " + N);
            System.out.println("ufThreshold: " + ufThreshold);

            for (int i = 0; i < group.size(); i++) {
                ArrayList<Tuple> grp = group.get(i);
                long max = 0;
                for (int j = 0; j < grp.size(); j++) {
                    Long val = (Long) grp.get(j).get(1);
                    if (val > max)
                        max = val;
                }
                if (max > ufThreshold) {
                    unfriendly[i] = true;
                    int pFactor = (int) Math
                            .ceil(max * 1.0 / ((rate * c) / 25));
                    System.out.println("Larger than threshold: " + max
                            + " at i: " + i);
                    System.out.println("Partition factor: " + pFactor);
                    partitionFactor[i] = pFactor;
                }
                System.out.println("i: " + i + " Max: " + max);
            }

            unfriendly[2] = true;
            unfriendly[3] = true;
            unfriendly[4] = true;
            partitionFactor[2] = 2;
            partitionFactor[3] = 3;
            partitionFactor[4] = 4;
            unfriendly[5] = true;
            unfriendly[6] = true;
            partitionFactor[5] = 2;
            partitionFactor[6] = 3;
        }

        /**
         * Will be called once all the intermediate keys and values are
         * processed. So right place to stop the reporter thread.
         */
        @Override
        protected void cleanup(Context context) throws IOException,
                InterruptedException {
            super.cleanup(context);
            if (errorInReduce) {
                // there was an error in reduce - just return
                return;
            }

            if (PigMapReduce.sJobConfInternal.get().get("pig.stream.in.reduce",
                    "false").equals("true")) {
                // If there is a stream in the pipeline we could
                // potentially have more to process - so lets
                // set the flag stating that all map input has been sent
                // already and then lets run the pipeline one more time
                // This will result in nothing happening in the case
                // where there is no stream in the pipeline
                rp.endOfAllInput = true;
                runPipeline(leaf);
            }

            if (!inIllustrator) {
                for (POStore store : stores) {
                    if (!initialized) {
                        MapReducePOStoreImpl impl = new MapReducePOStoreImpl(
                                context);
                        store.setStoreImpl(impl);
                        store.setUp();
                    }
                    store.tearDown();
                }
            }

            // Calling EvalFunc.finish()
            UDFFinishVisitor finisher = new UDFFinishVisitor(rp,
                    new DependencyOrderWalker<PhysicalOperator, PhysicalPlan>(
                            rp));

            try {
                finisher.visit();
            } catch (VisitorException e) {
                throw new IOException("Error trying to finish UDFs", e);
            }
            PhysicalOperator.setReporter(null);
            initialized = false;

            valuePartition(context);
            for (int i = 0; i < lengthTuple + 1; i++)
                System.out.println("i: " + i + " - " + unfriendly[i]);
            batch(context);
        }

        /**
         * Get reducer's illustrator context
         * 
         * @param input
         *            Input buffer as output by maps
         * @param pkg
         *            package
         * @return reducer's illustrator context
         * @throws IOException
         * @throws InterruptedException
         */
        abstract public Context getIllustratorContext(Job job,
                List<Pair<PigNullableWritable, Writable>> input, POPackage pkg)
                throws IOException, InterruptedException;

        abstract public boolean inIllustrator(Context context);

        abstract public POPackage getPack(Context context);
    }

    /**
     * This "specialized" reduce class is ONLY to be used in pig queries with
     * order by a udf. A UDF used for comparison in the order by expects to be
     * handed tuples. Hence a specialized map class
     * (PigMapReduce.MapWithComparator) ensures that the "key" used in the order
     * by is wrapped into a tuple (if it isn't already a tuple). This reduce
     * class unwraps this tuple in the case where the map had wrapped into a
     * tuple and handes the "unwrapped" key to the POPackage for processing
     */
    public static class ReduceWithComparator extends PigMapReduce.Reduce {

        private byte keyType;

        /**
         * Configures the Reduce plan, the POPackage operator and the reporter
         * thread
         */
        @Override
        protected void setup(Context context) throws IOException,
                InterruptedException {
            super.setup(context);
            keyType = pack.getKeyType();
        }

        /**
         * The reduce function which packages the key and List&lt;Tuple&gt; into
         * key, Bag&lt;Tuple&gt; after converting Hadoop type key into Pig type.
         * The package result is either collected as is, if the reduce plan is
         * empty or after passing through the reduce plan.
         */
        @Override
        protected void reduce(PigNullableWritable key,
                Iterable<NullableTuple> tupIter, Context context)
                throws IOException, InterruptedException {

            if (!initialized) {
                initialized = true;

                // cache the collector for use in runPipeline()
                // which could additionally be called from close()
                this.outputCollector = context;
                pigReporter.setRep(context);
                PhysicalOperator.setReporter(pigReporter);

                boolean aggregateWarning = "true".equalsIgnoreCase(pigContext
                        .getProperties().getProperty("aggregate.warning"));

                PigHadoopLogger pigHadoopLogger = PigHadoopLogger.getInstance();
                pigHadoopLogger.setAggregate(aggregateWarning);
                PigStatusReporter.setContext(context);
                pigHadoopLogger.setReporter(PigStatusReporter.getInstance());

                PhysicalOperator.setPigLogger(pigHadoopLogger);

                for (POStore store : stores) {
                    MapReducePOStoreImpl impl = new MapReducePOStoreImpl(
                            context);
                    store.setStoreImpl(impl);
                    store.setUp();
                }
            }

            // If the keyType is not a tuple, the MapWithComparator.collect()
            // would have wrapped the key into a tuple so that the
            // comparison UDF used in the order by can process it.
            // We need to unwrap the key out of the tuple and hand it
            // to the POPackage for processing
            if (keyType != DataType.TUPLE) {
                Tuple t = (Tuple) (key.getValueAsPigType());
                try {
                    key = HDataType.getWritableComparableTypes(t.get(0),
                            keyType);
                } catch (ExecException e) {
                    throw e;
                }
            }

            pack.attachInput(key, tupIter.iterator());

            Result res = pack.getNextTuple();
            if (res.returnStatus == POStatus.STATUS_OK) {
                Tuple packRes = (Tuple) res.result;

                if (rp.isEmpty()) {
                    context.write(null, packRes);
                    return;
                }

                rp.attachInput(packRes);

                List<PhysicalOperator> leaves = rp.getLeaves();

                PhysicalOperator leaf = leaves.get(0);
                runPipeline(leaf);

            }

            if (res.returnStatus == POStatus.STATUS_NULL) {
                return;
            }

            if (res.returnStatus == POStatus.STATUS_ERR) {
                int errCode = 2093;
                String msg = "Encountered error in package operator while processing group.";
                throw new ExecException(msg, errCode, PigException.BUG);
            }

        }

    }

}
