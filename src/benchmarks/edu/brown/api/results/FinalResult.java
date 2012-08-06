package edu.brown.api.results;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.voltdb.catalog.Database;

import edu.brown.api.results.BenchmarkResults.Result;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.statistics.Histogram;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.JSONSerializable;
import edu.brown.utils.JSONUtil;
import edu.brown.utils.MathUtil;

public class FinalResult implements JSONSerializable {
    private static final Logger LOG = Logger.getLogger(FinalResult.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
    
    public long duration;
    public long txnTotalCount;
    public double txnTotalPerSecond;
    public long txnMinCount;
    public double txnMinPerSecond;
    public long txnMaxCount;
    public double txnMaxPerSecond;
    public double stddevTxnPerSecond;

    public double totalAvgLatency;
    public double totalStdDevLatency;
    public double totalMinLatency;
    public double totalMaxLatency;
    
    /** TransactionName -> Results */
    public final Map<String, EntityResult> txnResults = new HashMap<String, EntityResult>();
    /** ClientName -> Results */
    public final Map<String, EntityResult> clientResults = new HashMap<String, EntityResult>();

    
    public FinalResult(BenchmarkResults results) {
        
        // Final Transactions Per Second
        this.duration = results.getTotalDuration();
        
        this.txnTotalCount = 0;
        this.txnMinCount = Long.MAX_VALUE;
        this.txnMaxCount = 0;
        
        Histogram<String> clientCounts = new Histogram<String>(true);
        Histogram<String> txnCounts = new Histogram<String>(true);
        
        double intervalTotals[] = results.computeIntervalTotals();
        if (debug.get()) LOG.debug("INTERVAL TOTALS: " + Arrays.toString(intervalTotals));
        for (int i = 0; i < intervalTotals.length; i++) {
            intervalTotals[i] /= (results.m_pollIntervalInMillis / 1000.0);
        } // FOR
        if (debug.get()) LOG.debug("INTERVAL TPS: " + Arrays.toString(intervalTotals));
        this.stddevTxnPerSecond = MathUtil.stdev(intervalTotals);
        
        for (String clientName : results.getClientNames()) {
            clientCounts.set(clientName, 0);
            for (String txnName : results.getTransactionNames()) {
                if (txnCounts.contains(txnName) == false) txnCounts.set(txnName, 0);
                Result[] rs = results.getResultsForClientAndTransaction(clientName, txnName);
                for (Result r : rs) {
                    this.txnTotalCount += r.transactionCount;
                    clientCounts.put(clientName, r.transactionCount);
                    txnCounts.put(txnName, r.transactionCount);
                } // FOR
            } // FOR
        } // FOR
        this.txnTotalPerSecond = this.txnTotalCount / (double)this.duration * 1000.0;
        
        // Min/Max Transactions Per Second
        for (int i = 0; i < results.getCompletedIntervalCount(); i++) {
            long txnCount = 0;
            for (String client : results.getClientNames()) {
                for (String txn : results.getTransactionNames()) {
                    Result[] rs = results.getResultsForClientAndTransaction(client, txn);
                    if (i < rs.length) txnCount += rs[i].transactionCount;
                } // FOR (txn)
            } // FOR (client)
            if (debug.get())
                LOG.debug(String.format("[%02d] minTxnCount = %d <-> %d", i, this.txnMinCount, txnCount));
            this.txnMinCount = Math.min(this.txnMinCount, txnCount);
            this.txnMaxCount = Math.max(this.txnMaxCount, txnCount);
        } // FOR
        double interval = results.getIntervalDuration() / 1000.0d;
        this.txnMinPerSecond = this.txnMinCount / interval;
        this.txnMaxPerSecond = this.txnMaxCount / interval;
        
        List<Integer> latencies = new ArrayList<Integer>();
        
        // TRANSACTION RESULTS
        for (String txnName : txnCounts.values()) {
            List<Integer> l = results.getLatenciesForTransaction(txnName);
            EntityResult er = new EntityResult(this.txnTotalCount, this.duration, txnCounts.get(txnName), l);
            this.txnResults.put(txnName, er);
            latencies.addAll(l);
        } // FOR
        
        // CLIENTS RESULTS
        for (String clientName : results.getClientNames()) {
            List<Integer> l = results.getLatenciesForClient(clientName);
            EntityResult er = new EntityResult(this.txnTotalCount, this.duration, clientCounts.get(clientName), l);
            this.clientResults.put(clientName.replace("client-", ""), er);
        } // FOR
        
        // LATENCY TOTALS
        this.totalMinLatency = Collections.min(latencies).doubleValue();
        this.totalMaxLatency = Collections.max(latencies).doubleValue();
        this.totalAvgLatency = MathUtil.sum(latencies) / (double)latencies.size();
        this.totalStdDevLatency = MathUtil.stdev(CollectionUtil.toDoubleArray(latencies));
    }
    
    public long getDuration() {
        return this.duration;
    }
    public long getTotalTxnCount() {
        return this.txnTotalCount;
    }
    public double getTotalTxnPerSecond() {
        return this.txnTotalPerSecond;
    }
    public long getMinTxnCount() {
        return this.txnMinCount;
    }
    public double getMinTxnPerSecond() {
        return this.txnMinPerSecond;
    }
    public long getMaxTxnCount() {
        return this.txnMaxCount;
    }
    public double getMaxTxnPerSecond() {
        return this.txnMaxPerSecond;
    }
    public double getStandardDeviationTxnPerSecond() {
        return this.stddevTxnPerSecond;
    }
    public Collection<String> getTransactionNames() {
        return this.txnResults.keySet();
    }
    
    public double getTotalAvgLatency() {
        return this.totalAvgLatency;
    }
    public double getTotalStdDevLatency() {
        return this.totalStdDevLatency;
    }
    public double getTotalMinLatency() {
        return this.totalMinLatency;
    }
    public double getTotalMaxLatency() {
        return this.totalMaxLatency;
    }

    public EntityResult getTransactionResult(String txnName) {
        return this.txnResults.get(txnName);
    }
    public Collection<String> getClientNames() {
        return this.clientResults.keySet();
    }
    public EntityResult getClientResult(String clientName) {
        return this.clientResults.get(clientName);
    }
    // ----------------------------------------------------------------------------
    // SERIALIZATION METHODS
    // ----------------------------------------------------------------------------
    @Override
    public void load(File input_path, Database catalog_db) throws IOException {
        JSONUtil.load(this, catalog_db, input_path);
    }
    @Override
    public void save(File output_path) throws IOException {
        JSONUtil.save(this, output_path);
    }
    @Override
    public String toJSONString() {
        return (JSONUtil.toJSONString(this));
    }
    @Override
    public void toJSON(JSONStringer stringer) throws JSONException {
        JSONUtil.fieldsToJSON(stringer, this, FinalResult.class, JSONUtil.getSerializableFields(this.getClass()));
    }
    @Override
    public void fromJSON(JSONObject json_object, Database catalog_db) throws JSONException {
        JSONUtil.fieldsFromJSON(json_object, catalog_db, this, FinalResult.class, true, JSONUtil.getSerializableFields(this.getClass()));
    }
}