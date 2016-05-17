/*
 * Copyright (C) 2016 QAware GmbH
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package de.qaware.chronix.solr.query.analysis;

import com.google.common.base.Strings;
import de.qaware.chronix.Schema;
import de.qaware.chronix.converter.KassiopeiaSimpleConverter;
import de.qaware.chronix.converter.common.MetricTSSchema;
import de.qaware.chronix.converter.serializer.ProtoBufKassiopeiaSimpleSerializer;
import de.qaware.chronix.solr.query.ChronixQueryParams;
import de.qaware.chronix.solr.query.analysis.functions.ChronixAnalysis;
import de.qaware.chronix.timeseries.MetricTimeSeries;
import de.qaware.chronix.timeseries.dt.DoubleList;
import de.qaware.chronix.timeseries.dt.LongList;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Function;

/**
 * Class to build documents using the given analysis or aggregation
 *
 * @author f.lautenschlager
 */
public final class AnalysisDocumentBuilder {

    private AnalysisDocumentBuilder() {
        //avoid instances
    }

    /**
     * Collects the given document and groups them using the join function result
     *
     * @param docs         the found documents that should be grouped by the join function
     * @param joinFunction the join function
     * @return the grouped documents
     */
    public static Map<String, List<SolrDocument>> collect(SolrDocumentList docs, Function<SolrDocument, String> joinFunction) {
        Map<String, List<SolrDocument>> collectedDocs = new HashMap<>();

        for (SolrDocument doc : docs) {
            String key = joinFunction.apply(doc);

            if (!collectedDocs.containsKey(key)) {
                collectedDocs.put(key, new ArrayList<>());
            }

            collectedDocs.get(key).add(doc);
        }


        return collectedDocs;
    }

    /**
     * Builds a solr document that is needed for the response from the aggregated time series
     *
     * @param timeSeries       the time series
     * @param analysisValueMap a map with executed analyses and values
     * @param key              the join key
     * @return the resulting solr document
     */
    public static SolrDocument buildDocument(MetricTimeSeries timeSeries, AnalysisValueMap analysisValueMap, String key, boolean dataShouldReturned) {


        int analysesCount = analysisValueMap.size();
        boolean returnDocument = false;


        //First check if a document is needed
        for (int i = 0; i < analysesCount; i++) {
            double value = analysisValueMap.getValue(i);

            if (!returnDocument && value > 0) {
                // For aggregations we always return the document
                returnDocument = true;
            }
        }

        if (!returnDocument) {
            return null;
        }

        SolrDocument doc = convert(timeSeries, dataShouldReturned);
        addAnalysesAndResults(analysisValueMap, analysesCount, doc);
        //add the join key
        doc.put(ChronixQueryParams.JOIN_KEY, key);

        return doc;
    }

    private static void addAnalysesAndResults(AnalysisValueMap analysisValueMap, int analysesCount, SolrDocument doc) {
        for (int i = 0; i < analysesCount; i++) {
            ChronixAnalysis analysis = analysisValueMap.getAnalysis(i);
            double value = analysisValueMap.getValue(i);
            String identifier = analysisValueMap.getIdentifier(i);
            String nameWithLeadingUnderscore;
            if (Strings.isNullOrEmpty(identifier)) {
                nameWithLeadingUnderscore = "_" + analysis.getType().name().toLowerCase();

            } else {
                nameWithLeadingUnderscore = "_" + analysis.getType().name().toLowerCase() + "_" + identifier;
            }

            //Add some information about the analysis
            doc.put(i + "_" + ChronixQueryParams.FUNCTION + nameWithLeadingUnderscore, value);
            doc.put(i + "_" + ChronixQueryParams.FUNCTION_ARGUMENTS + nameWithLeadingUnderscore, analysis.getArguments());
        }
    }

    /**
     * Collects the documents into a single time series.
     * Merges the time series attributes using a {@link Set}.
     * Arrays are added as a single entry in the result attributes.
     *
     * @param queryStart the user query start
     * @param queryEnd   the user query end
     * @param documents  the lucene documents
     * @return a metric time series that holds all the points
     */
    public static MetricTimeSeries collectDocumentToTimeSeries(long queryStart, long queryEnd, List<SolrDocument> documents) {
        //Collect all document of a time series

        LongList timestamps = null;
        DoubleList values = null;
        Map<String, Object> attributes = new HashMap<>();
        String metric = null;

        for (SolrDocument doc : documents) {
            MetricTimeSeries ts = convert(doc, queryStart, queryEnd);

            //Performance optimization. Avoiding fine grained growing.
            if (timestamps == null) {
                int size = ts.size();
                if (size < 1000) {
                    //well we have a small time series
                    size = 1000;
                }
                int calcAmountOfPoints = documents.size() * size;
                timestamps = new LongList(calcAmountOfPoints);
                values = new DoubleList(calcAmountOfPoints);
            }

            timestamps.addAll(ts.getTimestampsAsArray());
            values.addAll(ts.getValuesAsArray());

            //we use the metric of the first time series.
            //metric is the default join key.
            if (metric == null) {
                metric = ts.getMetric();
            }
            merge(attributes, ts.getAttributesReference());
        }

        return new MetricTimeSeries.Builder(metric)
                .points(timestamps, values)
                .attributes(attributes)
                .build();
    }

    /**
     * Merges to sets of time series attributes.
     * The result is set for each key holding the values.
     * If the other value is a collection, than all values
     * of the collection are added instead of the collection object.
     *
     * @param merged     the merged attributes
     * @param attributes the attributes of the other time series
     */
    private static void merge(Map<String, Object> merged, Map<String, Object> attributes) {

        for (HashMap.Entry<String, Object> newEntry : attributes.entrySet()) {

            String key = newEntry.getKey();

            //we ignore the version in the result
            if (key.equals(ChronixQueryParams.SOLR_VERSION_FIELD)) {
                continue;
            }

            if (!merged.containsKey(key)) {
                merged.put(key, new HashSet<>());
            }

            Set<Object> values = (Set<Object>) merged.get(key);
            Object value = newEntry.getValue();

            //Check if the value is a collection.
            //If it is a collection we add all values instead of adding a collection object
            if (value instanceof Collection && !values.contains(value)) {
                values.addAll((Collection) value);
            } else if (!values.contains(value)) {
                //Otherwise we have a single value or an array.
                values.add(value);
            }
            //otherwise we ignore the value
        }
    }


    /**
     * Converts the given Lucene document in a metric time series
     *
     * @param doc        - the lucene document
     * @param queryStart - the query start
     * @param queryEnd   - the query end
     * @return a metric time series
     */
    private static MetricTimeSeries convert(SolrDocument doc, long queryStart, long queryEnd) {


        String metric = doc.getFieldValue(MetricTSSchema.METRIC).toString();
        long tsStart = (long) doc.getFieldValue(Schema.START);
        long tsEnd = (long) doc.getFieldValue(Schema.END);
        byte[] data = ((ByteBuffer) doc.getFieldValue(Schema.DATA)).array();

        MetricTimeSeries.Builder ts = new MetricTimeSeries.Builder(metric);

        for (Map.Entry<String, Object> field : doc) {
            if (MetricTSSchema.isUserDefined(field.getKey())) {
                if (field.getValue() instanceof ByteBuffer) {
                    ts.attribute(field.getKey(), ((ByteBuffer) field.getValue()).array());
                } else {
                    ts.attribute(field.getKey(), field.getValue());
                }

            }
        }

        ProtoBufKassiopeiaSimpleSerializer.from(data, tsStart, tsEnd, queryStart, queryEnd, ts);
        return ts.build();
    }


    private static SolrDocument convert(MetricTimeSeries timeSeries, boolean withData) {

        SolrDocument doc = new SolrDocument();

        if (withData) {
            new KassiopeiaSimpleConverter().to(timeSeries).getFields().forEach(doc::addField);
        } else {
            timeSeries.attributes().forEach(doc::addField);
            //add the metric field as it is not stored in the attributes
            doc.addField(MetricTSSchema.METRIC, timeSeries.getMetric());
            doc.addField(Schema.START, timeSeries.getStart());
            doc.addField(Schema.END, timeSeries.getEnd());
        }

        return doc;
    }

}
