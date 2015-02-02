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
package org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Reducer.Context;
import org.apache.pig.PigException;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigGenericMapBaseRollupSample;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.POStatus;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.PhysicalOperator;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.Result;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.expressionOperators.ExpressionOperator;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.expressionOperators.POProject;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.expressionOperators.PORelationToExprProject;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.expressionOperators.POUserFunc;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhyPlanVisitor;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhysicalPlan;
import org.apache.pig.builtin.RollupDimensions;
import org.apache.pig.data.AccumulativeBag;
import org.apache.pig.data.BagFactory;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.DataType;
import org.apache.pig.data.InternalCachedBag;
import org.apache.pig.data.SchemaTupleClassGenerator.GenContext;
import org.apache.pig.data.SchemaTupleFactory;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.data.TupleMaker;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.apache.pig.impl.plan.DependencyOrderWalker;
import org.apache.pig.impl.plan.NodeIdGenerator;
import org.apache.pig.impl.plan.OperatorKey;
import org.apache.pig.impl.plan.VisitorException;
import org.apache.pig.pen.util.ExampleTuple;
import org.apache.pig.pen.util.LineageTracer;

/**
 * This class provides a new ForEach physical operator to handle the ROLLUP with
 * hybrid IRG when the Rollup Optimizer is activated. This class contains almost
 * the same as POForEach class excepts some functions for the Hybrid IRG stuffs.
 */
// We intentionally skip type checking in backend for performance reasons
@SuppressWarnings("unchecked")
public class PORollupH2IRGForEach extends POForEach {
    private static final long serialVersionUID = 1L;

    private static final Log log = LogFactory
            .getLog(PORollupH2IRGForEach.class);

    public List<PhysicalPlan> inputPlans;
    protected List<PhysicalOperator> opsToBeReset;

    protected static final TupleFactory mTupleFactory = TupleFactory
            .getInstance();

    // Since the plan has a generate, this needs to be maintained
    // as the generate can potentially return multiple tuples for
    // same call.
    protected boolean processingPlan = false;

    // its holds the iterators of the databags given by the input expressions
    // which need flattening.
    transient protected Iterator<Tuple>[] its = null;

    // This holds the outputs given out by the input expressions of any datatype
    protected Object[] bags = null;

    // This is the template whcih contains tuples and is flattened out in
    // createTuple() to generate the final output
    protected Object[] data = null;

    // store result types of the plan leaves
    protected byte[] resultTypes = null;

    // store whether or not an accumulative UDF has terminated early
    protected BitSet earlyTermination = null;

    // array version of isToBeFlattened - this is purely
    // for optimization - instead of calling isToBeFlattened.get(i)
    // we can do the quicker array access - isToBeFlattenedArray[i].
    // Also we can store "boolean" values rather than "Boolean" objects
    // so we can also save on the Boolean.booleanValue() calls
    protected boolean[] isToBeFlattenedArray;

    ExampleTuple tIn = null;
    protected int noItems;

    protected PhysicalOperator[] planLeafOps = null;

    protected transient AccumulativeTupleBuffer buffer;

    protected Tuple inpTuple;

    private Schema schema;

    // start adding new variables

    // The first tuple that stores the value of the previous Rollup Dimension
    // for the first IRG
    protected Tuple prevRollupDimension = null;

    // The second tuple that stores the value of the previous Rollup Dimension
    // for the second IRG
    protected Tuple prevRollupDimension2 = null;

    protected Tuple currentRollupDimension = null;

    // This holds the payload values for the first IRG
    protected DataBag[][] tmpResult;

    // This holds the payload values for the second IRG
    protected DataBag[][] tmpResult2;

    // This holds the result tuples for the first IRG
    public Result[] returnRes;

    // This holds the result tuples for the second IRG
    protected Result[] returnRes2;

    // To check if we can work on the second IRG or not
    protected boolean secondPass = false;

    // The pivot position of the rollup operation
    protected int pivot = -1;

    // To check if we finished the first IRG or not
    protected boolean finIRG1 = false;

    // To check if we finished the second IRG or not
    protected boolean finIRG2 = false;

    protected int noUserFunc = 0;

    protected int dimensionSize = 0;

    // These variables below are used in case the rollup operation has been
    // moved to the end of the operation list.
    protected int rollupFieldIndex = 0;

    protected boolean modified_pivot = false;

    protected boolean onlyIRG = false;

    public int conditionPosition = 0;

    protected int rollupSize = 0;

    protected int rollupOldFieldIndex = 0;

    protected Integer output_index[] = null;

    protected boolean finished = false;

    protected boolean isSampler = false;

    protected boolean isIRGSample = false;

    protected Path d[] = null;

    protected FSDataOutputStream out[] = null;

    protected Path sum = null;

    protected FSDataOutputStream sumout = null;

    protected ArrayList<Result> test = null;

    public ArrayList<ArrayList<Tuple>> al = null;

    public long[] tmpCombine;

    protected static final BagFactory mBagFactory = BagFactory.getInstance();

    protected int numReducers = 0;

    private double theta = 0.9;

    protected int lenSample = 0;

    protected long mapIR = 0;

    protected long mapOR = 0;

    protected float aMIR = 0;

    protected float aMOR = 0;

    protected int mapNo = 0;

    protected int countMap = 0;

    protected int countCbn = 0;

    protected boolean canOut = false;

    public long COR[] = null;

    protected long ROR[] = null;
    // finish adding new variables

    // USED FOR MRCUBE
    private HashMap<Integer, String> mrcube = new HashMap<Integer, String>();

    protected ArrayList<ArrayList<Tuple>> bucRegion = null;
    
    protected long bucRegionLength[] = null;

    public static long rollupEstimation = 0;

    public int count = 0;

    public long countArr = 0;

    public long addTime = 0;

    public boolean arrayDiff[] = null;

    protected ArrayList<Tuple> constructedBUC = new ArrayList<Tuple>();

    protected ArrayList<Tuple> bucGroupOutput = new ArrayList<Tuple>();
    
    protected long bucGroupOutputLength = 0;
    
    protected Tuple bucCurrent = mTupleFactory.newTuple();
    
    protected Tuple bucKeyCurrent = mTupleFactory.newTuple();
    
    protected Tuple bucNext = mTupleFactory.newTuple();
    
    protected Tuple bucKeyNext = mTupleFactory.newTuple();
    
    protected Tuple comparePrevious = mTupleFactory.newTuple();
    
    protected Tuple compareKeyPrevious = mTupleFactory.newTuple();
    
    protected Tuple compareKeyCurrent = mTupleFactory.newTuple();
    
    //protected Tuple tmpKey = mTupleFactory.newTuple();
    
    protected Tuple reconstructed = mTupleFactory.newTuple();
    protected Tuple reconstructedKey = mTupleFactory.newTuple();
    
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

    protected void setTupleNull(Tuple z) {
        for (int i = 0; i < z.size(); i++)
            try {
                z.set(i, null);
            } catch (ExecException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
    }
    
    protected boolean checkTupleNull(Tuple z) {
        int count = 0;
        for (int i = 0; i < z.size(); i++)
            try {
                if (z.get(i) == null)
                    count++;
            } catch (ExecException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        if (count == z.size())
            return true;
        return false;
    }
    
    public void initBUCRegion() {
        bucRegion = new ArrayList<ArrayList<Tuple>>();
        arrayDiff = new boolean[mrcube.size()];
        bucRegionLength = new long[mrcube.size()];
        for (int i = 0; i < mrcube.size(); i++) {
            bucRegion.add(new ArrayList<Tuple>());
            arrayDiff[i] = false;
            bucRegionLength[i] = 0;
        }
    }

    public void setMRCube(ArrayList<String> partition) {
        for (String i : partition) {
            String splitted[] = i.split(" ");
            mrcube.put(Integer.parseInt(splitted[2]), splitted[0] + " "
                    + splitted[splitted.length - 1]);
        }
        initBUCRegion();
    }

    /**
     * We create a template for output the fields in a tuple in case the rollup
     * operation has been moved to the end of the operation list
     * 
     * @param len
     */
    public void outputIndexInit(int len) {
        output_index = new Integer[len];
        for (int i = 0; i < len - this.rollupOldFieldIndex; i++)
            if (i < this.rollupOldFieldIndex)
                output_index[i] = i;
            else
                output_index[i] = i + rollupSize;

        int count = this.rollupOldFieldIndex;

        for (int i = len - this.rollupFieldIndex; i < len; i++)
            output_index[i] = count++;
    }

    public void setOnlyIRG() {
        onlyIRG = true;
    }

    /**
     * Set the original index of the first field of Rollup operation In case the
     * rollup operation has been moved to the end of the operation list
     * 
     * @param rofi
     */
    public void setRollupOldFieldIndex(int rofi) {
        this.rollupOldFieldIndex = rofi;
    }

    public int getRollupOldFieldIndex() {
        return this.rollupOldFieldIndex;
    }

    public void setRollupSize(int rs) {
        this.rollupSize = rs;
    }

    public int getRollupSize() {
        return this.rollupSize;
    }

    public void setDimensionSize(int ds) {
        this.dimensionSize = ds;
    }

    public int getDimensionSize() {
        return this.dimensionSize;
    }

    /**
     * Set the updated index of the first field of Rollup operation and also
     * update the new pivot position due to the change of the rollup operation
     * position In case the rollup operation has been moved to the end of the
     * operation list
     * 
     * @param rfi
     */
    public void setRollupFieldIndex(int rfi) {
        this.rollupFieldIndex = rfi;
        pivot = pivot + rollupFieldIndex;
        conditionPosition = pivot;
    }

    public int getRollupFieldIndex() {
        return this.rollupFieldIndex;
    }

    public void setPivot(int pvt) {
        this.pivot = pvt;
    }

    public int getPivot() {
        return this.pivot;
    }

    public void markSampler() {
        isSampler = true;
    }

    public void setNumReducer(int nr) {
        this.numReducers = nr;
    }

    public int getNumReducer() {
        return this.numReducers;
    }

    public void testFinish() throws ExecException {
        System.out.println("HAHAHA! I'm done!");
    }

    public PORollupH2IRGForEach(OperatorKey k) {
        this(k, -1, null, null);
    }

    public PORollupH2IRGForEach(OperatorKey k, int rp) {
        this(k, rp, null, null);
    }

    public PORollupH2IRGForEach(OperatorKey k, List inp) {
        this(k, -1, inp, null);
    }

    public PORollupH2IRGForEach(OperatorKey k, int rp, List<PhysicalPlan> inp,
            List<Boolean> isToBeFlattened) {
        super(k, rp);
        setUpFlattens(isToBeFlattened);
        this.inputPlans = inp;
        opsToBeReset = new ArrayList<PhysicalOperator>();
        getLeaves();
    }

    public PORollupH2IRGForEach(OperatorKey operatorKey,
            int requestedParallelism, List<PhysicalPlan> innerPlans,
            List<Boolean> flattenList, Schema schema) {
        this(operatorKey, requestedParallelism, innerPlans, flattenList);
        this.schema = schema;
    }

    public void visit(PhyPlanVisitor v) throws VisitorException {
        v.visitPOForEach(this);
    }

    @Override
    public String name() {
        return getAliasString() + "New Rollup H2IRG For Each" + " ("
                + getFlatStr() + ")" + "[" + DataType.findTypeName(resultType)
                + "]" + " - " + mKey.toString();
    }

    String getFlatStr() {
        if (isToBeFlattenedArray == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Boolean b : isToBeFlattenedArray) {
            sb.append(b);
            sb.append(',');
        }
        if (sb.length() > 0) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    @Override
    public void setAccumulative() {
        super.setAccumulative();
        for (PhysicalPlan p : inputPlans) {
            Iterator<PhysicalOperator> iter = p.iterator();
            while (iter.hasNext()) {
                PhysicalOperator po = iter.next();
                if (po instanceof ExpressionOperator
                        || po instanceof PODistinct) {
                    po.setAccumulative();
                }
            }
        }
    }

    @Override
    public void setAccumStart() {
        super.setAccumStart();
        for (PhysicalPlan p : inputPlans) {
            Iterator<PhysicalOperator> iter = p.iterator();
            while (iter.hasNext()) {
                PhysicalOperator po = iter.next();
                if (po instanceof ExpressionOperator
                        || po instanceof PODistinct) {
                    po.setAccumStart();
                }
            }
        }
    }

    @Override
    public void setAccumEnd() {
        super.setAccumEnd();
        for (PhysicalPlan p : inputPlans) {
            Iterator<PhysicalOperator> iter = p.iterator();
            while (iter.hasNext()) {
                PhysicalOperator po = iter.next();
                if (po instanceof ExpressionOperator
                        || po instanceof PODistinct) {
                    po.setAccumEnd();
                }
            }
        }
    }

    private boolean isEarlyTerminated() {
        return isEarlyTerminated;
    }

    private void earlyTerminate() {
        isEarlyTerminated = true;
    }

    /**
     * initial the ArrayList al initial the tmpCombine array
     * 
     * @param len
     */
    private void initEstimatedFile(int len) {
        al = new ArrayList<ArrayList<Tuple>>();
        for (int i = 0; i < len + 1; i++) {
            ArrayList<Tuple> single = new ArrayList<Tuple>();
            al.add(single);
        }
        tmpCombine = new long[len];
        for (int i = 0; i < len; i++)
            tmpCombine[i] = 0;
    }

    public static class TupleComparator implements Comparator<Tuple> {

        @Override
        public int compare(Tuple o1, Tuple o2) {
            return o1.compareTo(o2);
        }

    }

    /**
     * Calls getNext on the generate operator inside the nested physical plan
     * and returns it maintaining an additional state to denote the begin and
     * end of the nested plan processing.
     **/
    @Override
    public Result getNextTuple() throws ExecException {
        try {
            Result res = null;
            Result inp = null;
            // The nested plan is under processing
            // So return tuples that the generate oper
            // returns

            if (bucGroupOutputLength != 0) {
                for (int i = 0; i < bucGroupOutputLength;) {
                    res = new Result();
                    res.returnStatus = POStatus.STATUS_OK;
                    res.result = (Tuple) bucGroupOutput.get(i);
                    for (int j = 0; j < bucGroupOutputLength - 1; j++)
                        bucGroupOutput.set(j, bucGroupOutput.get(j+1));
                    bucGroupOutputLength--;
                    //System.out.println(res.result);
                    return res;
                }
            }

            if (processingPlan) {
                while (true) {
                    res = processPlan();

                    if (res.returnStatus == POStatus.STATUS_OK) {
                        return res;
                    }
                    if (res.returnStatus == POStatus.STATUS_EOP) {
                        processingPlan = false;
                        for (PhysicalPlan plan : inputPlans) {
                            plan.detachInput();
                        }
                        break;
                    }
                    if (res.returnStatus == POStatus.STATUS_ERR) {
                        return res;
                    }
                    if (res.returnStatus == POStatus.STATUS_NULL) {
                        continue;
                    }
                }
            }
            // The nested plan processing is done or is
            // yet to begin. So process the input and start
            // nested plan processing on the input tuple
            // read
            while (true) {
                inp = processInput();
                if (inp.returnStatus == POStatus.STATUS_EOP
                        || inp.returnStatus == POStatus.STATUS_ERR) {
                    return inp;
                }
                if (inp.returnStatus == POStatus.STATUS_NULL) {
                    continue;
                }

                attachInputToPlans((Tuple) inp.result);
                inpTuple = (Tuple) inp.result;

                for (PhysicalOperator po : opsToBeReset) {
                    po.reset();
                }

                if (isAccumulative()) {
                    for (int i = 0; i < inpTuple.size(); i++) {
                        if (inpTuple.getType(i) == DataType.BAG) {
                            // we only need to check one bag, because all the
                            // bags
                            // share the same buffer
                            buffer = ((AccumulativeBag) inpTuple.get(i))
                                    .getTuplebuffer();
                            break;
                        }
                    }

                    setAccumStart();
                    while (true) {
                        if (!isEarlyTerminated() && buffer.hasNextBatch()) {
                            try {
                                buffer.nextBatch();
                            } catch (IOException e) {
                                throw new ExecException(e);
                            }
                        } else {
                            inpTuple = ((POPackage.POPackageTupleBuffer) buffer)
                                    .illustratorMarkup(null, inpTuple, 0);
                            // buffer.clear();
                            setAccumEnd();
                        }

                        res = processPlan();

                        if (res.returnStatus == POStatus.STATUS_BATCH_OK) {
                            // attach same input again to process next batch
                            attachInputToPlans((Tuple) inp.result);
                        } else if (res.returnStatus == POStatus.STATUS_EARLY_TERMINATION) {
                            // if this bubbled up, then we just need to pass a
                            // null value through the pipe
                            // so that POUserFunc will properly return the
                            // values
                            attachInputToPlans(null);
                            earlyTerminate();
                        } else {
                            break;
                        }
                    }

                } else {
                    res = processPlan();
                }

                processingPlan = true;

                if (res.returnStatus == POStatus.STATUS_OK
                        && mrcube.size() != 0) {
                    // count null
                    // get
                    Tuple tmp = (Tuple) res.result;
                    Tuple key = (Tuple) tmp.get(0);
                    int count = 0;
                    for (int i = 0; i < key.size() - 1; i++)
                        if (key.get(i) == null)
                            count++;
                    String index = mrcube.get(count);
                    String splitted[] = index.split(" ");
                    bucRegionLength[Integer.parseInt(splitted[0])]++;
                    partialBuc(tmp, Integer.parseInt(splitted[0]));
                    //System.out.println(res.result);
                    //for(int i = 0; i < bucRegion.size(); i++) {
                        //System.out.println("i: " + i + " - size: " + bucRegion.get(i).size());
                    //}
                }

                return res;
            }
        } catch (RuntimeException e) {
            //for(int i = 0; i < bucRegion.size(); i++) {
            //    System.out.println("i: " + i + " - size: " + bucRegion.get(i).size());
            //}
            throw new ExecException("Error while executing ForEach at "
                    + this.getOriginalLocations(), e);
        }
    }

    public void partialBuc(Tuple current, int index) {

        int startIndex = 0;
        int stopIndex = 0;
        for (Integer i : mrcube.keySet()) {
            String value = mrcube.get(i);
            String splitted[] = value.split(" ");
            if (Integer.parseInt(splitted[0]) == index) {
                startIndex = dimensionSize - Integer.parseInt(splitted[1]);
                stopIndex = dimensionSize - i;
                break;
            }
        }

        
        /*if (bucRegionLength[index] > bucRegion.get(index).size()) {
            bucRegion.get(index).add(current);
        } else {
            setTuple(current, bucRegion.get(index).get((int) (bucRegionLength[index] - 1)));
        }*/
        
        //if (bucRegion.get(index).size() == 0)
            //bucRegion.get(index).add(current);
        //else {
        if (bucRegionLength[index] == 1) {
            if (bucRegionLength[index] > bucRegion.get(index).size()) {
                bucRegion.get(index).add(current);
            } else {
                setTuple(current, bucRegion.get(index).get((int) (bucRegionLength[index] - 1)));
            }
        }
        else {
            boolean diff = false;
            setTuple(bucRegion.get(index).get((int) (bucRegionLength[index] - 2)), comparePrevious);
            for (int i = 0; i < startIndex; i++) {
                try {
                    setTuple((Tuple)current.get(0), compareKeyCurrent);
                    setTuple((Tuple)comparePrevious.get(0), compareKeyPrevious);
                    if (!compareKeyCurrent.get(i).equals(compareKeyPrevious.get(i))) {
                        diff = true;
                        break;
                    }
                } catch (ExecException e) {
                    e.printStackTrace();
                }
            }
            if (diff == true) {//
                if(startIndex < stopIndex) {
                    bucRegionLength[index]--;
                    bucGroup(bucRegion.get(index), index, startIndex, stopIndex);
                    setTuple(current, bucRegion.get(index).get(0));
                    bucRegionLength[index] = 1;
                } else {
                    bucRegionLength[index] = 1;
                }
            } else {
                if (bucRegionLength[index] > bucRegion.get(index).size()) {
                    bucRegion.get(index).add(current);
                } else {
                    setTuple(current, bucRegion.get(index).get((int) (bucRegionLength[index] - 1)));
                }
            }
        }
    }

    public ArrayList<Tuple> finalBuc(Context oc) {
        for (int i = 0; i < bucRegion.size(); i++) {
            partialBuc(oc, bucRegion.get(i), i);
        }
        return constructedBUC;
    }

    public ArrayList<Tuple> finalBucCleanUp(Context oc, FSDataOutputStream pOut) {
        for (int i = 0; i < bucRegion.size(); i++) {
            bucGroupCleanup(oc, pOut, bucRegion.get(i), i);
        }
        return constructedBUC;
    }

    public void bucGroup(ArrayList<Tuple> part, int index, int startIndex,
            int stopIndex) {

        while (startIndex < stopIndex) {
            try {
                if (bucRegionLength[index] > 1) {
                    setTuple(part.get(0), bucCurrent);
                    long val = (Long) bucCurrent.get(1);
                    long valTmp = 0;
                    boolean diff = false;
                    for (int i = 0; i < bucRegionLength[index] - 1; i++) {
                        setTuple(part.get(i), bucCurrent);
                        setTuple((Tuple)bucCurrent.get(0), bucKeyCurrent);
                        setTuple(part.get(i+1), bucNext);
                        setTuple((Tuple)bucNext.get(0), bucKeyNext);
                        valTmp = (Long) bucCurrent.get(1);
                        for (int j = 0; j < startIndex; j++)
                            if (!bucKeyCurrent.get(j).equals(bucKeyNext.get(j))) {
                                diff = true;
                                break;
                            }

                        if (diff == false) {
                            val += (Long) bucNext.get(1);
                            if (startIndex == 0 && i != part.size() - 1)
                                val -= (Long) bucNext.get(1);
                            setTupleNull(bucNext);
                        } else {
                            Tuple reconstructed = mTupleFactory.newTuple();
                            Tuple reconstructedKey = mTupleFactory.newTuple();
                            for (int j = 0; j < dimensionSize; j++) {
                                if (j < startIndex)
                                    reconstructedKey.append(bucKeyCurrent.get(j));
                                else
                                    reconstructedKey.append(null);
                            }
                            if (index == 0)
                                reconstructedKey.append((long) -1);
                            else
                                reconstructedKey.append((long) 1);
                            reconstructed.append(reconstructedKey);
                            if (val < valTmp)
                                reconstructed.append(valTmp);
                            else
                                reconstructed.append(val);
                            bucGroupOutputLength++;
                            if (bucGroupOutputLength > bucGroupOutput.size()) {
                                bucGroupOutput.add(reconstructed);
                            } else {
                                bucGroupOutput.set((int) (bucGroupOutputLength - 1), reconstructed);
                            }
                            val = 0;
                            diff = false;
                        }
                    }
                    if (diff == false && val != 0) {
                        Tuple reconstructed = mTupleFactory.newTuple();
                        Tuple reconstructedKey = mTupleFactory.newTuple();
                        for (int j = 0; j < dimensionSize; j++) {
                            if (j < startIndex)
                                reconstructedKey.append(bucKeyNext.get(j));
                            else
                                reconstructedKey.append(null);
                        }
                        if (index == 0)
                            reconstructedKey.append((long) -1);
                        else
                            reconstructedKey.append((long) 1);
                        reconstructed.append(reconstructedKey);
                        reconstructed.append(val);
                        bucGroupOutputLength++;
                        if (bucGroupOutputLength > bucGroupOutput.size()) {
                            bucGroupOutput.add(reconstructed);
                        } else {
                            bucGroupOutput.set((int) (bucGroupOutputLength - 1), reconstructed);
                        }
                    }
                    if(!checkTupleNull(bucNext)) {
                        Tuple reconstructed = mTupleFactory.newTuple();
                        Tuple reconstructedKey = mTupleFactory.newTuple();
                        for (int j = 0; j < dimensionSize; j++) {
                            if (j < startIndex)
                                reconstructedKey.append(bucKeyNext.get(j));
                            else
                                reconstructedKey.append(null);
                        }
                        if (index == 0)
                            reconstructedKey.append((long) -1);
                        else
                            reconstructedKey.append((long) 1);
                        val = 0;
                        val += (Long) bucNext.get(1);

                        reconstructed.append(reconstructedKey);
                        reconstructed.append(val);
                        bucGroupOutputLength++;
                        if (bucGroupOutputLength > bucGroupOutput.size()) {
                            bucGroupOutput.add(reconstructed);
                        } else {
                            bucGroupOutput.set((int) (bucGroupOutputLength - 1), reconstructed);
                        }
                    }
                } else {
                    if(bucRegionLength[index] > 0) {
                        Tuple reconstructed = mTupleFactory.newTuple();
                        Tuple reconstructedKey = mTupleFactory.newTuple();
                        setTuple(part.get(0),bucCurrent);
                        setTuple((Tuple)bucCurrent.get(0), bucKeyCurrent);
                        long val = (Long) bucCurrent.get(1);
                        for (int j = 0; j < dimensionSize; j++) {
                            if (j < startIndex)
                                reconstructedKey.append(bucKeyCurrent.get(j));
                            else
                                reconstructedKey.append(null);
                        }
                        if (index == 0)
                            reconstructedKey.append((long) -1);
                        else
                            reconstructedKey.append((long) 1);
                        reconstructed.append(reconstructedKey);
                        reconstructed.append(val);
                        bucGroupOutputLength++;
                    if (bucGroupOutputLength > bucGroupOutput.size()) {
                        bucGroupOutput.add(reconstructed);
                    } else {
                        bucGroupOutput.set((int) (bucGroupOutputLength - 1), reconstructed);
                        //setTuple(reconstructed, bucGroupOutput.get((int) (bucGroupOutputLength - 1)));
                    }
                }
                }
                startIndex++;
            } catch (ExecException e) {

            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    public void bucGroupCleanup(Context oc, FSDataOutputStream pOut, ArrayList<Tuple> part, int index) {
        int startIndex = 0;
        int stopIndex = 0;
        for (Integer i : mrcube.keySet()) {
            String value = mrcube.get(i);
            String splitted[] = value.split(" ");
            if (Integer.parseInt(splitted[0]) == index) {
                startIndex = dimensionSize - Integer.parseInt(splitted[1]);
                stopIndex = dimensionSize - i;
                break;
            }
        }

        while (startIndex < stopIndex) {
            try {
                if (bucRegionLength[index] > 1) {
                    setTuple(part.get(0), bucCurrent);
                    long val = (Long) bucCurrent.get(1);
                    long valTmp = 0;
                    boolean diff = false;
                    for (int i = 0; i < bucRegionLength[index] - 1; i++) {
                        setTuple(part.get(i), bucCurrent);
                        setTuple((Tuple)bucCurrent.get(0), bucKeyCurrent);
                        setTuple(part.get(i+1), bucNext);
                        setTuple((Tuple)bucNext.get(0), bucKeyNext);
                        valTmp = (Long) bucCurrent.get(1);
                        for (int j = 0; j < startIndex; j++)
                            if (!bucKeyCurrent.get(j).equals(bucKeyNext.get(j))) {
                                diff = true;
                                break;
                            }

                        if (diff == false) {
                            val += (Long) bucNext.get(1);
                            //if (startIndex == 0 && i != part.size() - 1)
                                ///val -= (Long) bucNext.get(1);
                            setTupleNull(bucNext);
                        } else {
                            Tuple reconstructed = mTupleFactory.newTuple();
                            Tuple reconstructedKey = mTupleFactory.newTuple();
                            for (int j = 0; j < dimensionSize; j++) {
                                if (j < startIndex)
                                    reconstructedKey.append(bucKeyCurrent.get(j));
                                else
                                    reconstructedKey.append(null);
                            }
                            if (index == 0) {
                                reconstructed.append(reconstructedKey);
                                if (val < valTmp)
                                    reconstructed.append(valTmp);
                                else
                                    reconstructed.append(val);
                                oc.write(null, reconstructed);
                            }
                            else {
                                reconstructed.append(reconstructedKey);
                                if (val < valTmp)
                                    reconstructed.append(valTmp);
                                else
                                    reconstructed.append(val);
                                pOut.writeBytes(reconstructed.toDelimitedString("\t") + "\n");
                                //oc.write(null, reconstructed);
                            }
                            val = 0;
                            diff = false;
                        }
                    }
                    if (diff == false && val != 0) {
                        Tuple reconstructed = mTupleFactory.newTuple();
                        Tuple reconstructedKey = mTupleFactory.newTuple();
                        for (int j = 0; j < dimensionSize; j++) {
                            if (j < startIndex)
                                reconstructedKey.append(bucKeyNext.get(j));
                            else
                                reconstructedKey.append(null);
                        }
                        if (index == 0) {
                            reconstructed.append(reconstructedKey);
                            reconstructed.append(val);
                            oc.write(null, reconstructed);
                        }
                        else {
                            reconstructed.append(reconstructedKey);
                            reconstructed.append(val);
                            pOut.writeBytes(reconstructed.toDelimitedString("\t") + "\n");
                            //oc.write(null, reconstructed);
                        }
                    }
                    if(!checkTupleNull(bucNext)) {
                        Tuple reconstructed = mTupleFactory.newTuple();
                        Tuple reconstructedKey = mTupleFactory.newTuple();
                        for (int j = 0; j < dimensionSize; j++) {
                            if (j < startIndex)
                                reconstructedKey.append(bucKeyNext.get(j));
                            else
                                reconstructedKey.append(null);
                        }
                        if (index == 0) {
                            //reconstructedKey.append((long) -1);
                            val = 0;
                            val += (Long) bucNext.get(1);

                            reconstructed.append(reconstructedKey);
                            reconstructed.append(val);
                            oc.write(null, reconstructed);
                        }
                        else {
                            val = 0;
                            val += (Long) bucNext.get(1);

                            reconstructed.append(reconstructedKey);
                            reconstructed.append(val);
                            pOut.writeBytes(reconstructed.toDelimitedString("\t") + "\n");
                            //oc.write(null, reconstructed);
                        }
                    }
                } else {
                    if(bucRegionLength[index] > 0) {
                        Tuple reconstructed = mTupleFactory.newTuple();
                        Tuple reconstructedKey = mTupleFactory.newTuple();
                        setTuple(part.get(0),bucCurrent);
                        setTuple((Tuple)bucCurrent.get(0), bucKeyCurrent);
                        long val = (Long) bucCurrent.get(1);
                        for (int j = 0; j < dimensionSize; j++) {
                            if (j < startIndex)
                                reconstructedKey.append(bucKeyCurrent.get(j));
                            else
                                reconstructedKey.append(null);
                        }
                        if (index == 0) {
                            //reconstructedKey.append((long) -1);
                            reconstructed.append(reconstructedKey);
                            reconstructed.append(val);
                            oc.write(null, reconstructed);
                        }
                        else {
                            reconstructed.append(reconstructedKey);
                            reconstructed.append(val);
                            pOut.writeBytes(reconstructed.toDelimitedString("\t") + "\n");
                            //oc.write(null, reconstructed);
                        }
                }
                }
                startIndex++;
            } catch (ExecException e) {

            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        }
    }

    public void partialBuc(Context oc, ArrayList<Tuple> part, int index) {
        // Collections.sort(part, (new TupleComparator()));

        //for (Integer i : mrcube.keySet())
            //System.out.println("key: " + i + " val: " + mrcube.get(i));

        int startIndex = 0;
        int stopIndex = 0;
        for (Integer i : mrcube.keySet()) {
            String value = mrcube.get(i);
            String splitted[] = value.split(" ");
            if (Integer.parseInt(splitted[0]) == index) {
                startIndex = dimensionSize - Integer.parseInt(splitted[1]);
                stopIndex = dimensionSize - i;
                break;
            }
        }

        HashMap<String, Long> res = new HashMap<String, Long>();

        while (startIndex < stopIndex) {
            System.out.println("startIndex: " + startIndex + " stopIndex: "
                    + stopIndex);
            try {
                long val = 0;
                boolean diff = false;
                Tuple current = null;
                Tuple keyCurrent = null;
                Tuple next = null;
                Tuple keyNext = null;
                for (int i = 0; i < part.size() - 1; i++) {
                    current = part.get(i);
                    keyCurrent = (Tuple) current.get(0);
                    next = part.get(i + 1);
                    keyNext = (Tuple) next.get(0);
                    val += (Long) current.get(1);
                    for (int j = 0; j < startIndex; j++)
                        if (!keyCurrent.get(j).equals(keyNext.get(j))) {
                            diff = true;
                            if (val != (Long) current.get(1))
                                val -= (Long) current.get(1);
                            break;
                        }

                    if (diff == false) {
                        val += (Long) next.get(1);
                        if (startIndex == 0 && i != part.size() - 2)
                            val -= (Long) next.get(1);
                        next = null;
                    } else {
                        Tuple reconstructed = mTupleFactory.newTuple();
                        Tuple reconstructedKey = mTupleFactory.newTuple();
                        for (int j = 0; j < dimensionSize; j++) {
                            if (j < startIndex)
                                reconstructedKey.append(keyCurrent.get(j));
                            else
                                reconstructedKey.append(null);
                        }
                        if (index == 0)
                            reconstructedKey.append((long) -1);
                        else
                            reconstructedKey.append((long) 1);
                        reconstructed.append(reconstructedKey);
                        reconstructed.append(val);
                        // System.out.println(reconstructed);
                        // constructedBUC.add(reconstructed);
                        oc.write(null, reconstructed);
                        val = 0;
                        diff = false;
                    }
                }
                if (diff == false && val != 0) {
                    Tuple reconstructed = mTupleFactory.newTuple();
                    Tuple reconstructedKey = mTupleFactory.newTuple();
                    for (int j = 0; j < dimensionSize; j++) {
                        if (j < startIndex)
                            reconstructedKey.append(keyNext.get(j));
                        else
                            reconstructedKey.append(null);
                    }
                    if (index == 0)
                        reconstructedKey.append((long) -1);
                    else
                        reconstructedKey.append((long) 1);
                    reconstructed.append(reconstructedKey);
                    reconstructed.append(val);
                    // System.out.println(reconstructed);
                    // constructedBUC.add(reconstructed);
                    oc.write(null, reconstructed);

                    reconstructed = mTupleFactory.newTuple();
                    reconstructedKey = mTupleFactory.newTuple();
                }
                if (next != null) {
                    Tuple reconstructed = mTupleFactory.newTuple();
                    Tuple reconstructedKey = mTupleFactory.newTuple();
                    for (int j = 0; j < dimensionSize; j++) {
                        if (j < startIndex)
                            reconstructedKey.append(keyNext.get(j));
                        else
                            reconstructedKey.append(null);
                    }
                    if (index == 0)
                        reconstructedKey.append((long) -1);
                    else
                        reconstructedKey.append((long) 1);
                    val = 0;
                    val += (Long) next.get(1);

                    reconstructed.append(reconstructedKey);
                    reconstructed.append(val);
                    // System.out.println(reconstructed);
                    // constructedBUC.add(reconstructed);
                    oc.write(null, reconstructed);

                    reconstructed = mTupleFactory.newTuple();
                    reconstructedKey = mTupleFactory.newTuple();
                }
                startIndex++;
            } catch (ExecException e) {

            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    private boolean isEarlyTerminated = false;
    private TupleMaker<? extends Tuple> tupleMaker;

    private boolean knownSize = false;

    /**
     * We create a new tuple for the final flattened tuple, in case the rollup
     * operation has been moved to the end of the operation list, we re-order
     * the fields as the same order as the input's by using the output_index we
     * initialized before.
     * 
     * @param data
     *            array that is the template for the final flattened tuple
     * @return the final flattened tuple
     */

    protected Result processPlan() throws ExecException {
        if (schema != null && tupleMaker == null) {
            // Note here that if SchemaTuple is currently turned on, then any
            // UDF's in the chain
            // must follow good practices. Namely, they should not append to the
            // Tuple that comes
            // out of an iterator (a practice which is fairly common, but is not
            // recommended).
            tupleMaker = SchemaTupleFactory.getInstance(schema, false,
                    GenContext.FOREACH);
            if (tupleMaker != null) {
                knownSize = true;
            }
        }
        if (tupleMaker == null) {
            tupleMaker = TupleFactory.getInstance();
        }

        Result res = new Result();

        // We check if all the databags have exhausted the tuples. If so we
        // enforce the reading of new data by setting data and its to null
        if (its != null) {
            boolean restartIts = true;
            for (int i = 0; i < noItems; ++i) {
                if (its[i] != null && isToBeFlattenedArray[i] == true) {
                    restartIts &= !its[i].hasNext();
                }
            }
            // this means that all the databags have reached their last
            // elements. so we need to force reading of fresh databags
            if (restartIts) {
                its = null;
                data = null;
            }
        }

        if (its == null) {
            // getNext being called for the first time OR starting with a set of
            // new data from inputs
            its = new Iterator[noItems];
            bags = new Object[noItems];
            earlyTermination = new BitSet(noItems);

            for (int i = 0; i < noItems; ++i) {
                // Getting the iterators
                // populate the input data
                Result inputData = null;
                switch (resultTypes[i]) {
                case DataType.BAG:
                case DataType.TUPLE:
                case DataType.BYTEARRAY:
                case DataType.MAP:
                case DataType.BOOLEAN:
                case DataType.INTEGER:
                case DataType.DOUBLE:
                case DataType.LONG:
                case DataType.FLOAT:
                case DataType.BIGINTEGER:
                case DataType.BIGDECIMAL:
                case DataType.DATETIME:
                case DataType.CHARARRAY:
                    inputData = planLeafOps[i].getNext(resultTypes[i]);
                    /*
                     * if (resultTypes[i] == DataType.TUPLE && mrcube.size() !=
                     * 0) { Tuple tmp = (Tuple) inputData.result; if (tmp !=
                     * null) { if (tmp.size() > dimensionSize) { Tuple
                     * lessThanOne = mTupleFactory.newTuple(); for (int k = 0; k
                     * < dimensionSize; k++) lessThanOne.append(tmp.get(k));
                     * inputData.result = lessThanOne; } } }
                     */

                    break;
                default: {
                    int errCode = 2080;
                    String msg = "Foreach currently does not handle type "
                            + DataType.findTypeName(resultTypes[i]);
                    throw new ExecException(msg, errCode, PigException.BUG);
                }

                }

                // we accrue information about what accumulators have early
                // terminated
                // in the case that they all do, we can finish
                if (inputData.returnStatus == POStatus.STATUS_EARLY_TERMINATION) {
                    if (!earlyTermination.get(i))
                        earlyTermination.set(i);

                    continue;
                }

                if (inputData.returnStatus == POStatus.STATUS_BATCH_OK) {
                    continue;
                }

                if (inputData.returnStatus == POStatus.STATUS_EOP) {
                    // we are done with all the elements. Time to return.
                    its = null;
                    bags = null;
                    return inputData;
                }
                // if we see a error just return it
                if (inputData.returnStatus == POStatus.STATUS_ERR) {
                    return inputData;
                }

                // Object input = null;

                bags[i] = inputData.result;

                if (inputData.result instanceof DataBag
                        && isToBeFlattenedArray[i]) {
                    its[i] = ((DataBag) bags[i]).iterator();
                } else {
                    its[i] = null;
                }
            }
        }

        // if accumulating, we haven't got data yet for some fields, just return
        if (isAccumulative() && isAccumStarted()) {
            if (earlyTermination.cardinality() < noItems) {
                res.returnStatus = POStatus.STATUS_BATCH_OK;
            } else {
                res.returnStatus = POStatus.STATUS_EARLY_TERMINATION;
            }
            return res;
        }

        while (true) {
            if (data == null) {
                // getNext being called for the first time or starting on new
                // input data
                // we instantiate the template array and start populating it
                // with data
                data = new Object[noItems];
                for (int i = 0; i < noItems; ++i) {
                    if (isToBeFlattenedArray[i] && bags[i] instanceof DataBag) {
                        if (its[i].hasNext()) {
                            data[i] = its[i].next();
                        } else {
                            // the input set is null, so we return. This is
                            // caught above and this function recalled with
                            // new inputs.
                            its = null;
                            data = null;
                            res.returnStatus = POStatus.STATUS_NULL;
                            return res;
                        }
                    } else {
                        data[i] = bags[i];
                    }

                }
                if (getReporter() != null) {
                    getReporter().progress();
                }
                // createTuple(data);
                res.result = createTuple(data);
                res.returnStatus = POStatus.STATUS_OK;
                return res;
            } else {
                // we try to find the last expression which needs flattening and
                // start iterating over it
                // we also try to update the template array
                for (int index = noItems - 1; index >= 0; --index) {
                    if (its[index] != null && isToBeFlattenedArray[index]) {
                        if (its[index].hasNext()) {
                            data[index] = its[index].next();
                            res.result = createTuple(data);
                            res.returnStatus = POStatus.STATUS_OK;
                            return res;
                        } else {
                            // reset this index's iterator so cross product can
                            // be achieved
                            // we would be resetting this way only for the
                            // indexes from the end
                            // when the first index which needs to be flattened
                            // has reached the
                            // last element in its iterator, we won't come here
                            // - instead, we reset
                            // all iterators at the beginning of this method.
                            its[index] = ((DataBag) bags[index]).iterator();
                            data[index] = its[index].next();
                        }
                    }
                }
            }
        }

        // return null;
    }

    /**
     *
     * @param data
     *            array that is the template for the final flattened tuple
     * @return the final flattened tuple
     */
    protected Tuple createTuple(Object[] data) throws ExecException {
        Tuple out = tupleMaker.newTuple();

        int idx = 0;
        for (int i = 0; i < data.length; ++i) {
            Object in = data[i];

            if (isToBeFlattenedArray[i] && in instanceof Tuple) {
                Tuple t = (Tuple) in;
                int size = t.size();
                for (int j = 0; j < size; ++j) {
                    if (knownSize) {
                        out.set(idx++, t.get(j));
                    } else {
                        out.append(t.get(j));
                    }
                }
            } else {
                if (knownSize) {
                    out.set(idx++, in);
                } else {
                    out.append(in);
                }
            }
        }
        if (inpTuple != null) {
            return illustratorMarkup(inpTuple, out, 0);
        } else {
            return illustratorMarkup2(data, out);
        }
    }

    /**
     * Make a deep copy of this operator.
     * 
     * @throws CloneNotSupportedException
     */
    @Override
    public PORollupH2IRGForEach clone() throws CloneNotSupportedException {
        List<PhysicalPlan> plans = new ArrayList<PhysicalPlan>(inputPlans
                .size());
        for (PhysicalPlan plan : inputPlans) {
            plans.add(plan.clone());
        }
        List<Boolean> flattens = null;
        if (isToBeFlattenedArray != null) {
            flattens = new ArrayList<Boolean>(isToBeFlattenedArray.length);
            for (boolean b : isToBeFlattenedArray) {
                flattens.add(b);
            }
        }

        List<PhysicalOperator> ops = new ArrayList<PhysicalOperator>(
                opsToBeReset.size());
        for (PhysicalOperator op : opsToBeReset) {
            ops.add(op);
        }
        PORollupH2IRGForEach clone = new PORollupH2IRGForEach(new OperatorKey(
                mKey.scope, NodeIdGenerator.getGenerator().getNextNodeId(
                        mKey.scope)), requestedParallelism, plans, flattens);
        clone.setOpsToBeReset(ops);
        clone.setResultType(getResultType());
        clone.addOriginalLocation(alias, getOriginalLocations());
        clone.setPivot(pivot);
        clone.setRollupFieldIndex(rollupFieldIndex);
        clone.setRollupOldFieldIndex(rollupOldFieldIndex);
        clone.setRollupSize(rollupSize);
        clone.setDimensionSize(dimensionSize);
        return clone;
    }

    protected void attachInputToPlans(Tuple t) {
        // super.attachInput(t);
        for (PhysicalPlan p : inputPlans) {
            p.attachInput(t);
        }
    }

    public void getLeaves() {
        if (inputPlans != null) {
            int i = -1;
            if (isToBeFlattenedArray == null) {
                isToBeFlattenedArray = new boolean[inputPlans.size()];
            }
            planLeafOps = new PhysicalOperator[inputPlans.size()];
            for (PhysicalPlan p : inputPlans) {
                ++i;
                PhysicalOperator leaf = p.getLeaves().get(0);
                planLeafOps[i] = leaf;
                if (leaf instanceof POProject
                        && leaf.getResultType() == DataType.TUPLE
                        && ((POProject) leaf).isProjectToEnd()) {
                    isToBeFlattenedArray[i] = true;
                }
            }
        }
        // we are calculating plan leaves
        // so lets reinitialize
        reInitialize();
    }

    private void reInitialize() {
        if (planLeafOps != null) {
            noItems = planLeafOps.length;
            resultTypes = new byte[noItems];
            for (int i = 0; i < resultTypes.length; i++) {
                resultTypes[i] = planLeafOps[i].getResultType();
            }
        } else {
            noItems = 0;
            resultTypes = null;
        }

        if (inputPlans != null) {
            for (PhysicalPlan pp : inputPlans) {
                try {
                    ResetFinder lf = new ResetFinder(pp, opsToBeReset);
                    lf.visit();
                } catch (VisitorException ve) {
                    String errMsg = "Internal Error:  Unexpected error looking for nested operators which need to be reset in FOREACH";
                    throw new RuntimeException(errMsg, ve);
                }
            }
        }
    }

    public List<PhysicalPlan> getInputPlans() {
        return inputPlans;
    }

    public void setInputPlans(List<PhysicalPlan> plans) {
        inputPlans = plans;
        planLeafOps = null;
        getLeaves();
    }

    public void addInputPlan(PhysicalPlan plan, boolean flatten) {
        inputPlans.add(plan);
        // add to planLeafOps
        // copy existing leaves
        PhysicalOperator[] newPlanLeafOps = new PhysicalOperator[planLeafOps.length + 1];
        for (int i = 0; i < planLeafOps.length; i++) {
            newPlanLeafOps[i] = planLeafOps[i];
        }
        // add to the end
        newPlanLeafOps[planLeafOps.length] = plan.getLeaves().get(0);
        planLeafOps = newPlanLeafOps;

        // add to isToBeFlattenedArray
        // copy existing values
        boolean[] newIsToBeFlattenedArray = new boolean[isToBeFlattenedArray.length + 1];
        for (int i = 0; i < isToBeFlattenedArray.length; i++) {
            newIsToBeFlattenedArray[i] = isToBeFlattenedArray[i];
        }
        // add to end
        newIsToBeFlattenedArray[isToBeFlattenedArray.length] = flatten;
        isToBeFlattenedArray = newIsToBeFlattenedArray;

        // we just added a leaf - reinitialize
        reInitialize();
    }

    public void setToBeFlattened(List<Boolean> flattens) {
        setUpFlattens(flattens);
    }

    public List<Boolean> getToBeFlattened() {
        List<Boolean> result = null;
        if (isToBeFlattenedArray != null) {
            result = new ArrayList<Boolean>();
            for (int i = 0; i < isToBeFlattenedArray.length; i++) {
                result.add(isToBeFlattenedArray[i]);
            }
        }
        return result;
    }

    public boolean inProcessing() {
        return processingPlan;
    }

    protected void setUpFlattens(List<Boolean> isToBeFlattened) {
        if (isToBeFlattened == null) {
            isToBeFlattenedArray = null;
        } else {
            isToBeFlattenedArray = new boolean[isToBeFlattened.size()];
            int i = 0;
            for (Iterator<Boolean> it = isToBeFlattened.iterator(); it
                    .hasNext();) {
                isToBeFlattenedArray[i++] = it.next();
            }
        }
    }

    /**
     * Visits a pipeline and calls reset on all the nodes. Currently only pays
     * attention to limit nodes, each of which need to be told to reset their
     * limit.
     */
    protected class ResetFinder extends PhyPlanVisitor {

        ResetFinder(PhysicalPlan plan, List<PhysicalOperator> toBeReset) {
            super(plan,
                    new DependencyOrderWalker<PhysicalOperator, PhysicalPlan>(
                            plan));
        }

        @Override
        public void visitDistinct(PODistinct d) throws VisitorException {
            // FIXME: add only if limit is present
            opsToBeReset.add(d);
        }

        @Override
        public void visitLimit(POLimit limit) throws VisitorException {
            opsToBeReset.add(limit);
        }

        @Override
        public void visitSort(POSort sort) throws VisitorException {
            // FIXME: add only if limit is present
            opsToBeReset.add(sort);
        }

        /*
         * (non-Javadoc)
         * 
         * @see
         * org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans
         * .PhyPlanVisitor
         * #visitProject(org.apache.pig.backend.hadoop.executionengine
         * .physicalLayer.expressionOperators.POProject)
         */
        @Override
        public void visitProject(POProject proj) throws VisitorException {
            if (proj instanceof PORelationToExprProject) {
                opsToBeReset.add(proj);
            }
        }
    }

    /**
     * @return the opsToBeReset
     */
    public List<PhysicalOperator> getOpsToBeReset() {
        return opsToBeReset;
    }

    /**
     * @param opsToBeReset
     *            the opsToBeReset to set
     */
    public void setOpsToBeReset(List<PhysicalOperator> opsToBeReset) {
        this.opsToBeReset = opsToBeReset;
    }

    protected Tuple illustratorMarkup2(Object[] in, Object out) {
        if (illustrator != null) {
            ExampleTuple tOut = new ExampleTuple((Tuple) out);
            illustrator.getLineage().insert(tOut);
            boolean synthetic = false;
            for (Object tIn : in) {
                synthetic |= ((ExampleTuple) tIn).synthetic;
                illustrator.getLineage().union(tOut, (Tuple) tIn);
            }
            illustrator.addData(tOut);
            int i;
            for (i = 0; i < noItems; ++i) {
                if (((DataBag) bags[i]).size() < 2) {
                    break;
                }
            }
            if (i >= noItems && !illustrator.getEqClassesShared()) {
                illustrator.getEquivalenceClasses().get(0).add(tOut);
            }
            tOut.synthetic = synthetic;
            return tOut;
        } else {
            return (Tuple) out;
        }
    }

    @Override
    public Tuple illustratorMarkup(Object in, Object out, int eqClassIndex) {
        if (illustrator != null) {
            ExampleTuple tOut = new ExampleTuple((Tuple) out);
            illustrator.addData(tOut);
            if (!illustrator.getEqClassesShared()) {
                illustrator.getEquivalenceClasses().get(0).add(tOut);
            }
            LineageTracer lineageTracer = illustrator.getLineage();
            lineageTracer.insert(tOut);
            tOut.synthetic = ((ExampleTuple) in).synthetic;
            lineageTracer.union((ExampleTuple) in, tOut);
            return tOut;
        } else {
            return (Tuple) out;
        }
    }

}