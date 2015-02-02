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
package org.apache.pig.builtin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pig.EvalFunc;
import org.apache.pig.data.BagFactory;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.DataType;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.logicalLayer.FrontendException;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.apache.pig.impl.logicalLayer.schema.Schema.FieldSchema;

import com.google.common.collect.Lists;

/**
 * Produces a DataBag with hierarchy of values (from the most detailed level of
 * aggregation to most general level of aggregation) of the specified dimensions
 * For example, (a, b, c) will produce the following bag:
 * 
 * <pre>
 * { (a, b, c), (a, b, null), (a, null, null), (null, null, null) }
 * </pre>
 */
public class RollupDimensions extends EvalFunc<DataBag> {

    private static BagFactory bf = BagFactory.getInstance();
    private static TupleFactory tf = TupleFactory.getInstance();
    private final String allMarker;
    private int pivot = -1;
    private boolean rollupH2IRGoptimizable = false;
    private boolean isSampler = false;
    public static long rollupTime = 0;
    public long sTime = 0;
    public ArrayList<String> mrcube = new ArrayList<String>();

    public RollupDimensions() {
        this(null);
    }

    public RollupDimensions(String allMarker) {
        super();
        this.allMarker = allMarker;
    }

    public void setMRCube(ArrayList<String> partition) {
        for (String i : partition)
            mrcube.add(i);
    }

    public void setRollupH2IRGOptimizable(boolean check) {
        this.rollupH2IRGoptimizable = check;
    }

    public boolean getRollupH2IRGOptimizable() {
        return this.rollupH2IRGoptimizable;
    }

    public void setPivot(int pvt) throws IOException {
        this.pivot = pvt;
    }

    public void setIsSampler(boolean is) {
        this.isSampler = is;
    }

    public boolean getIsSampler() {
        return this.isSampler;
    }

    @Override
    public DataBag exec(Tuple tuple) throws IOException {
        sTime = System.currentTimeMillis();
        List<Tuple> result = Lists.newArrayListWithCapacity(tuple.size() + 1);
        CubeDimensions.convertNullToUnknown(tuple);
        result.add(tuple);
        iterativelyRollup(result, tuple);
        rollupTime += System.currentTimeMillis() - sTime;
        return bf.newDefaultBag(result);
    }

    private void iterativelyRollup(List<Tuple> result, Tuple input)
            throws IOException {

        Tuple tempTup = tf.newTuple(input.getAll());
        if (mrcube.size() == 0) {
            for (int i = input.size() - 1; i >= 0; i--) {
                tempTup.set(i, allMarker);
                result.add(tf.newTuple(tempTup.getAll()));
            }
        } else {
            for (String batch : mrcube) {
                Tuple tmp = tf.newTuple(input.getAll());
                String splitted[] = batch.split(" ");
                if (!splitted[1].equals("0")) {
                    int count = Integer.parseInt(splitted[2]);
                    for (int i = input.size() - 1; i >= (input.size() - count); i--)
                        tmp.set(i, allMarker);
                    result.add(tf.newTuple(tmp.getAll()));
                }
            }
        }
    }

    @Override
    public Schema outputSchema(Schema input) {
        // "dimensions" string is the default namespace assigned to the output
        // schema. this can be overridden by specifying user defined schema
        // names in foreach operator. if user defined schema names are not
        // specified then the output schema of foreach operator using this UDF
        // will have "dimensions::" namespace for all fields in the tuple
        try {
            return new Schema(
                    new FieldSchema("dimensions", input, DataType.BAG));
        } catch (FrontendException e) {
            // we are specifying BAG explicitly, so this should not happen.
            throw new RuntimeException(e);
        }
    }
}
