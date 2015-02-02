package org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.partitioners;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.lib.partition.HashPartitioner;
import org.apache.pig.PigConfiguration;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.hadoop.HDataType;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.io.NullableTuple;
import org.apache.pig.impl.io.PigNullableWritable;

public class RollupH2IRGAutoPivotPartitioner extends
        HashPartitioner<PigNullableWritable, Writable> implements Configurable {

    protected MessageDigest m = null;
    protected int choosen = 0;
    protected HashMap<String, Integer> hm;
    protected int pivot = 0;
    protected int rollupFieldIndex = 0;
    protected int rollupOldFieldIndex = 0;
    protected boolean pivotZero = false;
    protected int length = 0;
    public static long tLookup = 0;
    public static long hashTime = 0;
    public long sTime = 0;
    protected HashMap<Integer, Integer> mrcube = new HashMap<Integer, Integer>();
    protected Tuple t;
    protected TupleFactory mTupleFactory = TupleFactory.getInstance();
    public RollupH2IRGAutoPivotPartitioner() throws NoSuchAlgorithmException {
        //m = MessageDigest.getInstance("MD5");
    }

    @Override
    public Configuration getConf() {
        return null;
    }

    @Override
    public int getPartition(PigNullableWritable key, Writable value,
            int numPartitions) {
        int count = 0;
        for (int i = 0; i < ((Tuple) key.getValueAsPigType()).size(); i++)
            try {
                if (((Tuple) key.getValueAsPigType()).get(i) == null)
                    count++;
            } catch (ExecException e) {
                e.printStackTrace();
            }
        
        t = mTupleFactory.newTuple();
        int stopIndex = 0;
        
        if (count == 0) {
            stopIndex = ((Tuple) key.getValueAsPigType()).size() - mrcube.get(count) - 1;
            for (int i = 0; i < stopIndex; i++) {
                try {
                    t.append(((Tuple) key.getValueAsPigType()).get(i));
                }
                catch (Exception e) {
                    
                }
            }
            return (t.hashCode() & Integer.MAX_VALUE) % numPartitions;
        }
        else {
            return (key.hashCode() & Integer.MAX_VALUE) % numPartitions;
        }
    }

    @Override
    public void setConf(Configuration conf) {
        Path pPivot = new Path("/tmp/partition/mrcube");
        FileSystem fs;
        try {
            fs = FileSystem.get(conf);
            BufferedReader br = new BufferedReader(new InputStreamReader(fs
                    .open(pPivot)));
            String line = null;
            while ((line = br.readLine()) != null) {
                String splitted[] = line.split(" ");
                mrcube.put(Integer.parseInt(splitted[2]), Integer.parseInt(splitted[splitted.length - 1]));
            }
            br.close();

        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
}
