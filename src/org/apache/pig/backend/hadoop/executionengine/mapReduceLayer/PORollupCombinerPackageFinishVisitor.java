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

import java.io.IOException;

import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.PhysicalOperator;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhyPlanVisitor;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhysicalPlan;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POForEach;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.PORollupCombinerPackage;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.PORollupH2IRGForEach;
import org.apache.pig.impl.plan.PlanWalker;
import org.apache.pig.impl.plan.VisitorException;

/**
 * This class provides the remaining results of Hybrid 2IRG approach
 * when the job goes to clean-up
 */
public class PORollupCombinerPackageFinishVisitor extends PhyPlanVisitor {

    public PORollupCombinerPackageFinishVisitor(PhysicalPlan plan, PlanWalker<PhysicalOperator, PhysicalPlan> walker) {
        super(plan, walker);
    }

    @Override
    public void visitRollupCombinerPackage(PORollupCombinerPackage hforeach) throws VisitorException {
        super.visitRollupCombinerPackage(hforeach);
        try {
                ((PORollupCombinerPackage)hforeach).testFinish();
        } catch (ExecException ex) {
            throw new VisitorException(ex);
            }
    }
}