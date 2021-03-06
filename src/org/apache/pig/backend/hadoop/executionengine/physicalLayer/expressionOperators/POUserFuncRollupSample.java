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

package org.apache.pig.backend.hadoop.executionengine.physicalLayer.expressionOperators;

import static org.apache.pig.PigConfiguration.TIME_UDFS_PROP;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.pig.Accumulator;
import org.apache.pig.Algebraic;
import org.apache.pig.EvalFunc;
import org.apache.pig.FuncSpec;
import org.apache.pig.PigException;
import org.apache.pig.PigWarning;
import org.apache.pig.TerminatingAccumulator;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.POStatus;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.PhysicalOperator;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.Result;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhyPlanVisitor;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.util.MonitoredUDFExecutor;
import org.apache.pig.builtin.MonitoredUDF;
import org.apache.pig.builtin.RollupDimensions;
import org.apache.pig.data.DataType;
import org.apache.pig.data.SchemaTupleClassGenerator.GenContext;
import org.apache.pig.data.SchemaTupleFactory;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.data.TupleMaker;
import org.apache.pig.impl.PigContext;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.apache.pig.impl.plan.NodeIdGenerator;
import org.apache.pig.impl.plan.OperatorKey;
import org.apache.pig.impl.plan.VisitorException;
import org.apache.pig.impl.util.UDFContext;
import org.apache.pig.tools.pigstats.PigStatusReporter;

public class POUserFuncRollupSample extends ExpressionOperator {
    private static final Log LOG = LogFactory.getLog(POUserFuncRollupSample.class);
    private final static String TIMING_COUNTER = "approx_microsecs";
    private final static String INVOCATION_COUNTER = "approx_invocations";
    private final static int TIMING_FREQ = 100;
    private final static TupleFactory tf = TupleFactory.getInstance();

    private transient String counterGroup;
    /**
     *
     */
    private static final long serialVersionUID = 1L;
    transient EvalFunc func;
    transient private String[] cacheFiles = null;

    FuncSpec funcSpec;
    FuncSpec origFSpec;
    public static final byte INITIAL = 0;
    public static final byte INTERMEDIATE = 1;
    public static final byte FINAL = 2;
    private boolean initialized = false;
    private MonitoredUDFExecutor executor = null;

    private PhysicalOperator referencedOperator = null;
    private boolean isAccumulationDone;
    private String signature;
    private boolean haveCheckedIfTerminatingAccumulator;

    private long numInvocations = 0L;
    private boolean doTiming = false;

    private static final String ROLLUP_UDF = RollupDimensions.class.getName();
    private boolean rollupH2IRGoptimizable = false;
    private int pivot = -1;

    protected int rollupFieldIndex = 0;
    private int rollupOldFieldIndex = 0;
    private int dimensionSize = 0;
    private boolean isSampler = false;
    private ArrayList<String> mrcube = new ArrayList<String>();
    
    private int chunkSize = -1;
    private double rate = 0;
    
    public static long tCastProj = 0;
    
    private long tPreCast = 0;
    
    public void setIsSampler(boolean is) {
        this.isSampler = is;
    }
    
    public void setMRCube(ArrayList<String> partition) {
        for (String i : partition)
            mrcube.add(i);
    }
    
    public boolean getIsSampler() {
        return this.isSampler;
    }

    public void setPivot(int pvt) {
        this.pivot = pvt;
    }

    public int getPivot() {
        return this.pivot;
    }
    
    public void setChunkSize(int cs) {
        this.chunkSize = cs;
    }
    
    public int getChunkSize() {
        return this.chunkSize;
    }
    
    public void setRate(double r) {
        this.rate = r;
    }
    
    public double getRate() {
        return this.rate;
    }

    public void setDimensionSize(int ds) {
        this.dimensionSize = ds;
    }

    public int getDimensionSize() {
        return this.dimensionSize;
    }

    public void setRollupH2IRGOptimizable(boolean check) {
        this.rollupH2IRGoptimizable = check;
    }

    public boolean getRollupH2IRGOptimizable() {
        return this.rollupH2IRGoptimizable;
    }

    public void setRollupOldFieldIndex(int rofi) {
        this.rollupOldFieldIndex = rofi;
    }

    public int getRollupOldFieldIndex() {
        return this.rollupOldFieldIndex;
    }

    public void setRollupFieldIndex(int rfi) {
        this.rollupFieldIndex = rfi;
    }

    public int getRollupFieldIndex() {
        return this.rollupFieldIndex;
    }

    public PhysicalOperator getReferencedOperator() {
        return referencedOperator;
    }

    public void setReferencedOperator(PhysicalOperator referencedOperator) {
        this.referencedOperator = referencedOperator;
    }

    public POUserFuncRollupSample(OperatorKey k, int rp, List<PhysicalOperator> inp) {
        super(k, rp);
        inputs = inp;

    }

    public POUserFuncRollupSample(OperatorKey k, int rp, List<PhysicalOperator> inp,
            FuncSpec funcSpec) {
        this(k, rp, inp, funcSpec, null);
    }

    public POUserFuncRollupSample(OperatorKey k, int rp, List<PhysicalOperator> inp,
            FuncSpec funcSpec, EvalFunc func) {
        super(k, rp);
        super.setInputs(inp);
        this.funcSpec = funcSpec;
        this.origFSpec = funcSpec;
        this.func = func;
        instantiateFunc(funcSpec);
    }
    
    public void changeToSumHIISample() {
        String longsum = "org.apache.pig.builtin.LongSum";
        FuncSpec s = new FuncSpec(longsum);
        instantiateFunc(s);
        this.funcSpec = s;
        this.origFSpec = s;
    }
    
    public FuncSpec getOrigFSpec() {
        return this.origFSpec;
    }
    
    public void setOrigFSpec(FuncSpec s) {
        this.origFSpec = s;
    }

    public void setFuncSpec(FuncSpec s) {
        this.funcSpec = s;
    }
    
    public void setEvalFunc(EvalFunc s) {
        this.func = s;
    }
    
    public void instantiateFunc(FuncSpec fSpec) {
        this.func = (EvalFunc) PigContext.instantiateFuncFromSpec(fSpec);
        this.setSignature(signature);
        this.setFuncInputSchema(signature);
        if (func.getClass().isAnnotationPresent(MonitoredUDF.class)) {
            executor = new MonitoredUDFExecutor(func);
        }
        // the next couple of initializations do not work as intended for the
        // following reasons
        // the reporter and pigLogger are member variables of PhysicalOperator
        // when instanitateFunc is invoked at deserialization time, both
        // reporter and pigLogger are null. They are set during map and reduce
        // calls,
        // making the initializations here basically useless. Look at the
        // processInput
        // method where these variables are re-initialized. At that point, the
        // PhysicalOperator
        // is set up correctly with the reporter and pigLogger references
        this.func.setReporter(getReporter());
        this.func.setPigLogger(pigLogger);
    }

    private transient TupleMaker inputTupleMaker;
    private boolean usingSchemaTupleFactory;

    @Override
    public Result processInput() throws ExecException {

        // Make sure the reporter is set, because it isn't getting carried
        // across in the serialization (don't know why). I suspect it's as
        // cheap to call the setReporter call everytime as to check whether I
        // have (hopefully java will inline it).
        
        if (!initialized) {
            func.setReporter(getReporter());
            func.setPigLogger(pigLogger);
            Configuration jobConf = UDFContext.getUDFContext().getJobConf();
            if (jobConf != null) {
                doTiming = "true".equalsIgnoreCase(jobConf.get(TIME_UDFS_PROP,
                        "false"));
                counterGroup = funcSpec.toString();
            }
            // We initialize here instead of instantiateFunc because this is
            // called
            // when actual processing has begun, whereas a function can be
            // instantiated
            // on the frontend potentially (mainly for optimization)
            Schema tmpS = func.getInputSchema();
            if (tmpS != null) {
                // Currently, getInstanceForSchema returns null if no class was
                // found. This works fine...
                // if it is null, the default will be used. We pass the context
                // because if it happens that
                // the same Schema was generated elsewhere, we do not want to
                // override user expectations
                inputTupleMaker = SchemaTupleFactory.getInstance(tmpS, false,
                        GenContext.UDF);
                if (inputTupleMaker == null) {
                    LOG.debug("No SchemaTupleFactory found for Schema [" + tmpS
                            + "], using default TupleFactory");
                    usingSchemaTupleFactory = false;
                } else {
                    LOG.debug("Using SchemaTupleFactory for Schema: " + tmpS);
                    usingSchemaTupleFactory = true;
                }

                // In the future, we could optionally use SchemaTuples for
                // output as well
            }

            if (inputTupleMaker == null) {
                inputTupleMaker = TupleFactory.getInstance();
            }

            initialized = true;
        }

        Result res = new Result();
        if (input == null && (inputs == null || inputs.size() == 0)) {
            res.returnStatus = POStatus.STATUS_EOP;
            return res;
        }

        // Should be removed once the model is clear
        if (getReporter() != null) {
            getReporter().progress();
        }

        if (isInputAttached()) {
            res.result = input;
            res.returnStatus = POStatus.STATUS_OK;
            detachInput();
            return res;
        } else {
            // we decouple this because there may be cases where the size is
            // known and it isn't a schema
            // tuple factory
            boolean knownSize = usingSchemaTupleFactory;
            int knownIndex = 0;
            res.result = inputTupleMaker.newTuple();

            Result temp = null;

            for (PhysicalOperator op : inputs) {
                tPreCast = System.currentTimeMillis();
                temp = op.getNext(op.getResultType());
                tCastProj += System.currentTimeMillis() - tPreCast;
                if (temp.returnStatus != POStatus.STATUS_OK) {
                    return temp;
                }
                if (op instanceof POProject
                        && op.getResultType() == DataType.TUPLE) {
                    POProject projOp = (POProject) op;
                    if (projOp.isProjectToEnd()) {
                        Tuple trslt = (Tuple) temp.result;
                        Tuple rslt = (Tuple) res.result;
                        for (int i = 0; i < trslt.size(); i++) {
                            if (knownSize) {
                                rslt.set(knownIndex++, trslt.get(i));
                            } else {
                                rslt.append(trslt.get(i));
                            }
                        }
                        continue;
                    }
                }
                if (knownSize) {
                    ((Tuple) res.result).set(knownIndex++, temp.result);
                } else {
                    ((Tuple) res.result).append(temp.result);
                }
            }
            res.returnStatus = temp.returnStatus;
            return res;
        }
    }

    private boolean isEarlyTerminating = false;

    private void setIsEarlyTerminating() {
        isEarlyTerminating = true;
    }

    private boolean isEarlyTerminating() {
        return isEarlyTerminating;
    }

    private boolean isTerminated = false;

    private boolean hasBeenTerminated() {
        return isTerminated;
    }

    private void earlyTerminate() {
        isTerminated = true;
    }

    private Result getNext() throws ExecException {
        Result result = processInput();
        long startNanos = 0;
        boolean timeThis = doTiming && (numInvocations++ % TIMING_FREQ == 0);
        if (timeThis) {
            startNanos = System.nanoTime();
            PigStatusReporter.getInstance()
                    .getCounter(counterGroup, INVOCATION_COUNTER)
                    .increment(TIMING_FREQ);

        }
        try {
            if (result.returnStatus == POStatus.STATUS_OK) {
                Tuple t = (Tuple) result.result;

                // For backward compatibility, we short-circuit tuples whose
                // fields are all null. (See PIG-3679)
                boolean allNulls = true;
                for (int i = 0; i < t.size(); i++) {
                    if (!t.isNull(i)) {
                        allNulls = false;
                        break;
                    }
                }
                if (allNulls) {
                    pigLogger
                            .warn(this,
                                    "All the input values are null, skipping the invocation of UDF",
                                    PigWarning.SKIP_UDF_CALL_FOR_NULL);
                    Schema outputSchema = func.outputSchema(func
                            .getInputSchema());
                    // If the output schema is tuple (i.e. multiple fields are
                    // to be returned), we return a tuple where every field is
                    // null.
                    if (outputSchema != null
                            && outputSchema.getField(0).type == DataType.TUPLE) {
                        result.result = tf
                                .newTuple(outputSchema.getField(0).schema
                                        .size());
                        // Otherwise, we simply return null since it can be cast
                        // to
                        // any data type.
                    } else {
                        result.result = null;
                    }
                    return result;
                }

                if (isAccumulative()) {
                    if (isAccumStarted()) {
                        if (!haveCheckedIfTerminatingAccumulator) {
                            haveCheckedIfTerminatingAccumulator = true;
                            if (func instanceof TerminatingAccumulator<?>)
                                setIsEarlyTerminating();
                        }

                        if (!hasBeenTerminated()
                                && isEarlyTerminating()
                                && ((TerminatingAccumulator<?>) func)
                                        .isFinished()) {
                            earlyTerminate();
                        }

                        if (hasBeenTerminated()) {
                            result.returnStatus = POStatus.STATUS_EARLY_TERMINATION;
                            result.result = null;
                            isAccumulationDone = false;
                        } else {
                            ((Accumulator) func)
                                    .accumulate((Tuple) result.result);
                            result.returnStatus = POStatus.STATUS_BATCH_OK;
                            result.result = null;
                            isAccumulationDone = false;
                        }
                    } else {
                        if (isAccumulationDone) {
                            // PORelationToExprProject does not return
                            // STATUS_EOP
                            // so that udf gets called both when isAccumStarted
                            // is first true and then set to false, even
                            // when the input relation is empty.
                            // so the STATUS_EOP has to be sent from POUserFunc,
                            // after the results have been sent.
                            result.result = null;
                            result.returnStatus = POStatus.STATUS_EOP;
                        } else {
                            result.result = ((Accumulator) func).getValue();
                            result.returnStatus = POStatus.STATUS_OK;
                            ((Accumulator) func).cleanup();
                            isAccumulationDone = true;
                        }
                    }
                } else {
                    if (executor != null) {
                        result.result = executor
                                .monitorExec((Tuple) result.result);
                    } else {
                        if (funcSpec.getClassName().equals(ROLLUP_UDF) && this.rollupH2IRGoptimizable != false) {
                            ((RollupDimensions) func).setPivot(this.pivot);
                            ((RollupDimensions) func).setRollupH2IRGOptimizable(this.rollupH2IRGoptimizable);
                            ((RollupDimensions) func).setIsSampler(this.isSampler);
                            if(((RollupDimensions) func).mrcube.size()==0)
                                ((RollupDimensions) func).setMRCube(this.mrcube);
                        }
                        result.result = func.exec((Tuple) result.result);
                    }
                }
            }
            if (timeThis) {
                PigStatusReporter
                        .getInstance()
                        .getCounter(counterGroup, TIMING_COUNTER)
                        .increment(
                                (Math.round((System.nanoTime() - startNanos) / 1000))
                                        * TIMING_FREQ);
            }
            return result;
        } catch (ExecException ee) {
            throw ee;
        } catch (IOException ioe) {
            int errCode = 2078;
            String msg = "Caught error from UDF: " + funcSpec.getClassName();
            String footer = " [" + ioe.getMessage() + "]";

            if (ioe instanceof PigException) {
                int udfErrorCode = ((PigException) ioe).getErrorCode();
                if (udfErrorCode != 0) {
                    errCode = udfErrorCode;
                    msg = ((PigException) ioe).getMessage();
                } else {
                    msg += " [" + ((PigException) ioe).getMessage() + " ]";
                }
            } else {
                msg += footer;
            }

            throw new ExecException(msg, errCode, PigException.BUG, ioe);
        } catch (IndexOutOfBoundsException ie) {
            int errCode = 2078;
            String msg = "Caught error from UDF: " + funcSpec.getClassName()
                    + ", Out of bounds access [" + ie.getMessage() + "]";
            throw new ExecException(msg, errCode, PigException.BUG, ie);
        }
    }

    @Override
    public Result getNextTuple() throws ExecException {
        return getNext();
    }

    @Override
    public Result getNextDataBag() throws ExecException {
        return getNext();
    }

    @Override
    public Result getNextInteger() throws ExecException {
        return getNext();
    }

    @Override
    public Result getNextBoolean() throws ExecException {

        return getNext();
    }

    @Override
    public Result getNextDataByteArray() throws ExecException {

        return getNext();
    }

    @Override
    public Result getNextDouble() throws ExecException {
        return getNext();
    }

    @Override
    public Result getNextBigInteger() throws ExecException {
        return getNext();
    }

    @Override
    public Result getNextBigDecimal() throws ExecException {
        return getNext();
    }

    @Override
    public Result getNextFloat() throws ExecException {
        return getNext();
    }

    @Override
    public Result getNextLong() throws ExecException {
        return getNext();
    }

    @Override
    public Result getNextDateTime() throws ExecException {

        return getNext();
    }

    @Override
    public Result getNextMap() throws ExecException {
        return getNext();
    }

    @Override
    public Result getNextString() throws ExecException {
        return getNext();
    }

    public void setAlgebraicFunction(byte Function) throws ExecException {
        // This will only be used by the optimizer for putting correct functions
        // in the mapper,
        // combiner and reduce. This helps in maintaining the physical plan as
        // is without the
        // optimiser having to replace any operators.
        // You wouldn't be able to make two calls to this function on the same
        // algebraic EvalFunc as
        // func is being changed.
        switch (Function) {
        case INITIAL:
            funcSpec = new FuncSpec(getInitial());
            break;
        case INTERMEDIATE:
            funcSpec = new FuncSpec(getIntermed());
            break;
        case FINAL:
            funcSpec = new FuncSpec(getFinal());
            break;
        }
        funcSpec.setCtorArgs(origFSpec.getCtorArgs());
        instantiateFunc(funcSpec);
        setResultType(DataType.findType(((EvalFunc<?>) func).getReturnType()));
    }

    public String getInitial() throws ExecException {
        instantiateFunc(origFSpec);
        if (func instanceof Algebraic) {
            return ((Algebraic) func).getInitial();
        } else {
            int errCode = 2072;
            String msg = "Attempt to run a non-algebraic function"
                    + " as an algebraic function";
            throw new ExecException(msg, errCode, PigException.BUG);
        }
    }

    public String getIntermed() throws ExecException {
        instantiateFunc(origFSpec);
        if (func instanceof Algebraic) {
            return ((Algebraic) func).getIntermed();
        } else {
            int errCode = 2072;
            String msg = "Attempt to run a non-algebraic function"
                    + " as an algebraic function";
            throw new ExecException(msg, errCode, PigException.BUG);
        }
    }

    public String getFinal() throws ExecException {
        instantiateFunc(origFSpec);
        if (func instanceof Algebraic) {
            return ((Algebraic) func).getFinal();
        } else {
            int errCode = 2072;
            String msg = "Attempt to run a non-algebraic function"
                    + " as an algebraic function";
            throw new ExecException(msg, errCode, PigException.BUG);
        }
    }

    public Type getReturnType() {
        return func.getReturnType();
    }

    public void finish() {
        func.finish();
        if (executor != null) {
            executor.terminate();
        }
    }

    public Schema outputSchema(Schema input) {
        return func.outputSchema(input);
    }

    public Boolean isAsynchronous() {
        return func.isAsynchronous();
    }

    @Override
    public String name() {
        return "POUserFunc" + "(" + func.getClass().getName() + ")" + "["
                + DataType.findTypeName(resultType) + "]" + " - "
                + mKey.toString();
    }

    @Override
    public boolean supportsMultipleInputs() {

        return true;
    }

    @Override
    public boolean supportsMultipleOutputs() {

        return false;
    }

    @Override
    public void visit(PhyPlanVisitor v) throws VisitorException {

        v.visitUserFuncRollupSample(this);
    }

    public FuncSpec getFuncSpec() {
        return funcSpec;
    }

    public String[] getCacheFiles() {
        return cacheFiles;
    }

    public void setCacheFiles(String[] cf) {
        cacheFiles = cf;
    }

    public boolean combinable() {
        return (func instanceof Algebraic);
    }

    @Override
    public POUserFuncRollupSample clone() throws CloneNotSupportedException {
        // Inputs will be patched up later by PhysicalPlan.clone()
        POUserFuncRollupSample clone = new POUserFuncRollupSample(new OperatorKey(mKey.scope,
                NodeIdGenerator.getGenerator().getNextNodeId(mKey.scope)),
                requestedParallelism, null, funcSpec.clone());
        clone.setResultType(resultType);
        clone.signature = signature;
        clone.setPivot(pivot);
        clone.setDimensionSize(dimensionSize);
        clone.setRollupFieldIndex(rollupFieldIndex);
        clone.setRollupOldFieldIndex(rollupOldFieldIndex);
        clone.setRollupH2IRGOptimizable(rollupH2IRGoptimizable);
        clone.setChunkSize(chunkSize);
        clone.setRate(rate);
        return clone;
    }

    private void readObject(ObjectInputStream is) throws IOException,
            ClassNotFoundException {
        is.defaultReadObject();
        instantiateFunc(funcSpec);
    }

    /**
     * Get child expression of this expression
     */
    @Override
    public List<ExpressionOperator> getChildExpressions() {
        return null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void setAccumStart() {
        if (isAccumulative() && !isAccumStarted()) {
            super.setAccumStart();
            ((Accumulator) func).cleanup();
        }
    }

    @Override
    public void setResultType(byte resultType) {
        this.resultType = resultType;
    }

    @Override
    public Tuple illustratorMarkup(Object in, Object out, int eqClassIndex) {
        return (Tuple) out;
    }

    public EvalFunc getFunc() {
        return func;
    }

    public void setSignature(String signature) {
        this.signature = signature;
        if (this.func != null) {
            this.func.setUDFContextSignature(signature);
        }
    }

    /**
     * Sets EvalFunc's inputschema based on the signature
     * 
     * @param signature
     */
    public void setFuncInputSchema(String signature) {
        Properties props = UDFContext.getUDFContext().getUDFProperties(
                func.getClass());
        Schema tmpS = (Schema) props.get("pig.evalfunc.inputschema."
                + signature);
        if (tmpS != null) {
            this.func.setInputSchema(tmpS);
        }
    }

}
